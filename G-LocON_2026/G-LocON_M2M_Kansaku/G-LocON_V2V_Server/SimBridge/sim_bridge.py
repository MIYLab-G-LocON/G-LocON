"""段階2: SUMO とアプリ・サーバをつなぐリアルタイムブリッジ.

    # モードA: 実機3台がSUMOの車に乗る（他の車は走るだけ）
    python sim_bridge.py --phones 3
    # モードB: 全車両を仮想クライアントとしてエッジサーバに参加させ，実機1台で目視
    python sim_bridge.py --phones 1 --virtual --gui
    # 実機なし・PCだけでモードBを試す（ホットスポット不要）
    python sim_bridge.py --phones 0 --virtual --gui --local

前提: MasterServer と EdgeServer（scenario/edge_servers.csv の全交差点）が起動していること
      （python start_servers.py で一括起動できる）。

■ スマホ（アプリのSUMOモード）とのやりとり（UDP, 既定ポート 55700）
    スマホ → ブリッジ  SIM_HELLO      {peerID}            車の割り当てを要求（割り当てまで2秒ごと）
                       SIM_ROUTE_REQ  {peerID}            ルートを再送してほしい
                       SIM_BYE        {peerID}            終了
    ブリッジ → スマホ  SIM_ROUTE      {vehicleId, intersections:[{intersectionId,lat,lon}], destLat, destLon}
                       SIM_LOCATION   {vehicleId, latitude, longitude, speed[m/s], bearing, simTime}（1秒ごと）
                       SIM_END        {vehicleId}         車が目的地に着いた（次の車が割り当てられる）

■ 仮想クライアント（--virtual）
    SUMOの車1台ごとに専用のUDPソケットを持ち，アプリと同じ手順・同じ形式で
      INTERSECTION_QUERY（MasterServer）→ ETA<30秒でJOIN／30m離れて遠ざかったらLEAVE／15秒ごとKEEPALIVE
    を送る。エッジサーバから届くメンバー一覧・追加・離脱通知でグループを管理し，
    グループ内の実機へ位置（SendLocation）を1秒ごとに送る（実機の地図に仮想車両として表示される）。
    peerID は "sim-<車両ID>"。

■ 出力（out/live_<日時>/）
    events.csv       JOIN/LEAVE送信・返信受信・離脱通知・割り当てなどの時系列
    consistency.csv  各仮想クライアントが持つグループ一覧と，ブリッジが把握している正解（その交差点にJOIN中の仮想クライアント）の比較
    summary.txt      一覧一致率・JOIN応答時間・取りこぼしなどの集計
"""
import argparse
import csv
import json
import math
import os
import selectors
import socket
import time

import common

KEEPALIVE_SEC = 15.0
UPDATE_SEC = 1.0          # アプリと同じ1秒ごとの位置更新
MIN_SPEED = 1.0           # アプリと同じ（ETA計算の最低速度）
PASS_RADIUS_M = 20.0      # アプリと同じ: この距離まで近づいたら交差点を「通過済み」とする
PASS_LOOKAHEAD = 3        # アプリと同じ: 通過判定はまだ通過していない最初の交差点からこの個数先まで
GRACE_SEC = 2.0           # 一覧の一致判定で，直近のJOIN/LEAVEを通知待ちとして除外する時間
PALETTE = [(230, 25, 75), (60, 180, 75), (255, 225, 25), (0, 130, 200), (245, 130, 48),
           (145, 30, 180), (70, 240, 240), (240, 50, 230), (210, 245, 60), (0, 128, 128)]


def hubeny(lat1, lon1, lat2, lon2):
    """アプリの HubenyDistance と同等の距離[m]（短距離なので正距円筒近似で十分）."""
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    m = math.cos(math.radians((lat1 + lat2) / 2))
    return 6378137.0 * math.hypot(dlat, dlon * m)


class Intersection:
    """アプリの navigation.Intersection と同じ状態を持つ."""

    def __init__(self, iid, lat, lon):
        self.iid, self.lat, self.lon = iid, lat, lon
        self.es = None                      # (ip, port)
        self.dist = 0.0
        self.prev = -1.0
        self.joined = False
        self.left = False
        self.passed = False

    def set_dist(self, d):
        self.prev, self.dist = self.dist, d

    def should_leave(self):
        # アプリと同じ: 通過済みで，δ以上離れ，かつ遠ざかっている
        return self.passed and self.prev >= 0 and self.dist >= common.LEAVE_DIST_M and self.dist > self.prev


class Bridge:
    def __init__(self, a, traci, net, route_ix, logw):
        self.a, self.t, self.net, self.logw = a, traci, net, logw
        self.route_ix = route_ix            # junctionId -> (iid, lat, lon)
        self.sel = selectors.DefaultSelector()
        self.clients = {}                   # vid -> VirtualClient
        self.phones = {}                    # addr -> Phone
        self.phone_vehicles = {}            # vid -> Phone
        self.joined_truth = {}              # iid -> set(peerID)  ブリッジが把握するJOIN中の仮想クライアント
        self.changed = {}                   # (iid, peerID) -> 最後にJOIN/LEAVEしたシミュレーション時刻
        self.es_index = {iid: k for k, iid in enumerate(sorted(common.read_sim_edge_servers()))}
        self.stats = {"join_sent": 0, "join_replied": 0, "join_rtt": [], "leave_sent": 0,
                      "peer_left_rx": 0, "nat_register_rx": 0, "check": 0, "match": 0,
                      "missing": 0, "extra": 0, "query_fail": 0}
        self.phone_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.phone_sock.bind((a.bind_host, a.bind_port))
        self.phone_sock.setblocking(False)
        self.sel.register(self.phone_sock, selectors.EVENT_READ, ("phone", None))

    def now(self):
        return self.t.simulation.getTime()

    def log(self, *row):
        self.logw.writerow([f"{self.now():.1f}", f"{time.time():.3f}", *row])

    # ---------- ルート上の交差点（アプリへ渡すもの・仮想クライアントが使うもの） ----------
    def route_intersections(self, vid):
        route = self.t.vehicle.getRoute(vid)
        idx = max(self.t.vehicle.getRouteIndex(vid), 0)
        seq, seen = [], set()
        # 先頭は今いる道路の始点（アプリの OSRM ルートが出発地点の交差点から始まるのと同じ）。
        # これが無いと，ルートが後で出発地点を通り直すとき，その交差点が出発直後に「通過済み」になり，
        # それより手前の交差点が飛ばされてしまう
        nodes = [self.net.getEdge(route[idx]).getFromNode().getID()] + \
                [self.net.getEdge(e).getToNode().getID() for e in route[idx:]]
        for j in nodes:
            info = self.route_ix.get(j)
            if info and info[0] not in seen:
                seen.add(info[0])
                seq.append(info)
        return seq

    # ---------- 受信処理 ----------
    def poll(self):
        for key, _ in self.sel.select(timeout=0):
            kind, owner = key.data
            sock = key.fileobj
            while True:
                try:
                    data, addr = sock.recvfrom(65535)
                except BlockingIOError:
                    break
                except OSError:
                    break
                try:
                    msg = json.loads(data.decode("utf-8"))
                except ValueError:
                    continue
                if kind == "phone":
                    self.on_phone(msg, addr)
                else:
                    owner.on_message(msg, addr)

    # ---------- スマホ ----------
    def on_phone(self, msg, addr):
        pt = msg.get("processType")
        ph = self.phones.get(addr)
        if pt == "SIM_HELLO":
            if ph is None:
                if len(self.phones) >= self.a.phones:
                    return
                ph = Phone(msg.get("peerID", "?"), addr)
                self.phones[addr] = ph
                self.log("PHONE_HELLO", ph.peer, "", f"{addr[0]}:{addr[1]}")
        elif pt == "SIM_ROUTE_REQ" and ph and ph.vid:
            self.send_route(ph)
        elif pt == "SIM_BYE" and ph:
            if ph.vid:
                self.phone_vehicles.pop(ph.vid, None)
                self.set_color(ph.vid, None)
            del self.phones[addr]
            self.log("PHONE_BYE", ph.peer, ph.vid or "", "")

    def send_phone(self, ph, obj):
        try:
            self.phone_sock.sendto(json.dumps(obj).encode("utf-8"), ph.addr)
        except OSError:
            pass

    def send_route(self, ph):
        seq = self.route_intersections(ph.vid)
        dest_edge = self.t.vehicle.getRoute(ph.vid)[-1]
        x, y = self.net.getEdge(dest_edge).getToNode().getCoord()
        dlon, dlat = self.net.convertXY2LonLat(x, y)
        self.send_phone(ph, {"processType": "SIM_ROUTE", "vehicleId": ph.vid,
                             "intersections": [{"intersectionId": i, "lat": la, "lon": lo} for i, la, lo in seq],
                             "destLat": dlat, "destLon": dlon})

    def assign_phones(self, departed):
        waiting = [p for p in self.phones.values() if p.vid is None]
        if not waiting:
            return
        es_j = {j for j, (iid, _, _) in self.route_ix.items() if iid in self.es_index}
        for vid in departed:
            if not waiting:
                break
            route = self.t.vehicle.getRoute(vid)
            n_es = sum(1 for e in route if self.net.getEdge(e).getToNode().getID() in es_j)
            if n_es < self.a.min_es_on_route:
                continue
            ph = waiting.pop(0)
            ph.vid = vid
            self.phone_vehicles[vid] = ph
            if vid in self.clients:          # 仮想クライアントより実機を優先
                self.clients.pop(vid).close("PHONE_TAKEOVER")
            self.log("PHONE_ASSIGN", ph.peer, vid, f"es_on_route={n_es}")
            self.send_route(ph)
            self.set_color(vid, "phone")

    def update_phones(self, arrived):
        for vid in list(self.phone_vehicles):
            ph = self.phone_vehicles[vid]
            if vid in arrived or vid not in self.alive:
                self.send_phone(ph, {"processType": "SIM_END", "vehicleId": vid})
                self.log("PHONE_END", ph.peer, vid, "")
                ph.vid = None
                del self.phone_vehicles[vid]
                continue
            x, y = self.t.vehicle.getPosition(vid)
            lon, lat = self.net.convertXY2LonLat(x, y)
            self.send_phone(ph, {"processType": "SIM_LOCATION", "vehicleId": vid,
                                 "latitude": lat, "longitude": lon,
                                 "speed": self.t.vehicle.getSpeed(vid),
                                 "bearing": self.t.vehicle.getAngle(vid), "simTime": self.now()})

    # ---------- 仮想クライアント ----------
    def update_virtual(self, departed, arrived):
        for vid in arrived:
            c = self.clients.pop(vid, None)
            if c:
                c.close("ARRIVED")
        for vid in departed:
            if vid in self.phone_vehicles or len(self.clients) >= self.a.virtual_max:
                continue
            seq = self.route_intersections(vid)
            if not seq:
                continue
            c = VirtualClient(self, vid, seq)
            self.clients[vid] = c
        for c in list(self.clients.values()):
            if c.vid not in self.alive:
                self.clients.pop(c.vid).close("VANISHED")
                continue
            c.tick()

    def check_consistency(self, w):
        """各仮想クライアントのグループ一覧（サーバから届いた内容）とブリッジ側の正解を比べる."""
        for c in self.clients.values():
            for iid, it in c.ix.items():
                if not it.joined or iid not in c.members or self.now() - c.join_t.get(iid, 0) < 3:
                    continue           # JOIN直後（返信待ち）は除く
                truth = self.joined_truth.get(iid, set()) - {c.peer}
                got = {p for p in c.members[iid] if p.startswith("sim-")}
                # 直近 GRACE 秒以内にJOIN/LEAVEした車は通知が届く途中の可能性があるため比較から除く
                recent = {p for p in truth ^ got
                          if self.now() - self.changed.get((iid, p), -1e9) < GRACE_SEC}
                truth, got = truth - recent, got - recent
                self.stats["check"] += 1
                self.stats["match"] += got == truth
                self.stats["missing"] += len(truth - got)
                self.stats["extra"] += len(got - truth)
                w.writerow([f"{self.now():.1f}", iid, c.peer, len(truth), len(got),
                            len(truth - got), len(got - truth)])

    # ---------- sumo-gui の色分け ----------
    def set_color(self, vid, kind):
        if not self.a.gui or vid not in self.alive:
            return
        if kind == "phone":
            col = (255, 0, 255)
        elif kind is None:
            col = (160, 160, 160)
        else:
            col = PALETTE[self.es_index.get(kind, 0) % len(PALETTE)]
        try:
            self.t.vehicle.setColor(vid, col + (255,))
        except Exception:
            pass

    def run(self):
        t = self.t
        step = t.simulation.getDeltaT()
        next_update = 0.0
        wall0, sim0 = time.time(), self.now()
        end = self.a.duration
        with open(os.path.join(self.a.out, "consistency.csv"), "w", newline="", encoding="utf-8") as cf:
            cw = csv.writer(cf)
            cw.writerow(["simTime", "intersectionId", "peer", "truth_n", "got_n", "missing", "extra"])
            while t.simulation.getMinExpectedNumber() > 0 and (end <= 0 or self.now() < end):
                t.simulationStep()
                self.alive = set(t.vehicle.getIDList())
                departed = t.simulation.getDepartedIDList()
                arrived = set(t.simulation.getArrivedIDList())
                for vid in departed:
                    self.set_color(vid, None)
                self.assign_phones(departed)
                if self.a.virtual:
                    self.update_virtual(departed, arrived)
                if self.now() >= next_update:
                    next_update = self.now() + UPDATE_SEC
                    self.update_phones(arrived)
                    if self.a.virtual:
                        self.check_consistency(cw)
                # 実時間に合わせる（受信処理はこの待ち時間の間に行う）
                target = wall0 + (self.now() - sim0) / self.a.speed
                while True:
                    self.poll()
                    rest = target - time.time()
                    if rest <= 0:
                        break
                    time.sleep(min(rest, 0.02))
            for c in list(self.clients.values()):
                c.close("END")


class Phone:
    def __init__(self, peer, addr):
        self.peer, self.addr, self.vid = peer, addr, None


class VirtualClient:
    def __init__(self, br, vid, seq):
        self.br, self.vid, self.peer = br, vid, f"sim-{vid}"
        self.ix = {iid: Intersection(iid, la, lo) for iid, la, lo in seq}
        self.members = {}                  # iid -> set(peerID)
        self.addrs = {}                    # peerID -> (ip, port)  位置の送信先
        self.join_t = {}
        self.last_keep = {}
        self.join_sent_wall = {}
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind((br.a.client_bind, 0))
        self.sock.setblocking(False)
        self.port = self.sock.getsockname()[1]
        br.sel.register(self.sock, selectors.EVENT_READ, ("virtual", self))
        self.count = 0
        self.next_tick = br.now()
        self.queried = False
        self.query()

    # アプリの UserInfo 相当
    def user(self):
        x, y = self.br.t.vehicle.getPosition(self.vid)
        lon, lat = self.br.net.convertXY2LonLat(x, y)
        return lat, lon

    def send(self, obj, addr):
        try:
            self.sock.sendto(json.dumps(obj).encode("utf-8"), addr)
        except OSError:
            pass

    def query(self):
        self.send({"processType": "INTERSECTION_QUERY", "intersectionIds": list(self.ix)},
                  (self.br.a.master_host, self.br.a.master_port))

    def base(self, pt, iid, lat, lon):
        ip = self.br.a.advertise_ip
        return {"processType": pt, "intersectionId": iid, "publicIP": ip, "publicPort": self.port,
                "privateIP": ip, "privatePort": self.port, "latitude": lat, "longitude": lon,
                "peerID": self.peer}

    def tick(self):
        br = self.br
        if br.now() < self.next_tick:
            return
        self.next_tick = br.now() + UPDATE_SEC
        if not self.queried and self.count % 5 == 4:
            self.query()                   # MasterServerの返信が無ければ再問い合わせ
        self.count += 1
        lat, lon = self.user()
        speed = max(br.t.vehicle.getSpeed(self.vid), MIN_SPEED)
        seq = list(self.ix.values())       # ルート順（アプリの IntersectionManager.update と同じ判定）
        nxt = 0
        while nxt < len(seq) and seq[nxt].passed:
            nxt += 1
        last_passed = nxt - 1
        for k, it in enumerate(seq):
            it.set_dist(hubeny(lat, lon, it.lat, it.lon))
            if nxt <= k <= nxt + PASS_LOOKAHEAD and it.dist <= PASS_RADIUS_M:
                it.passed = True
                last_passed = max(last_passed, k)
        for k, it in enumerate(seq):
            eta = it.dist / speed
            if k < last_passed and not it.passed:
                # 保険: 後の交差点を通過したのに近づかないままだった → 通過したものとみなす
                it.passed = True
                if it.joined:
                    self.leave(it, lat, lon, "V_LEAVE_PASSED_NEXT")
                else:
                    it.left = True
                continue
            if not it.joined and not it.left and it.es:
                if eta < common.JOIN_ETA_SEC:
                    it.joined = True
                    msg = self.base("JOIN", it.iid, lat, lon)
                    msg["eta"] = eta
                    self.send(msg, it.es)
                    self.join_t[it.iid] = br.now()
                    self.join_sent_wall[it.iid] = time.time()
                    self.last_keep[it.iid] = br.now()
                    br.joined_truth.setdefault(it.iid, set()).add(self.peer)
                    br.changed[(it.iid, self.peer)] = br.now()
                    br.stats["join_sent"] += 1
                    br.log("V_JOIN", self.peer, it.iid, f"eta={eta:.1f}")
                    br.set_color(self.vid, it.iid)
                    self.show_state()
            elif it.joined and it.should_leave():
                self.leave(it, lat, lon, "V_LEAVE")
        for iid, t0 in self.last_keep.items():
            it = self.ix[iid]
            if it.joined and br.now() - t0 >= KEEPALIVE_SEC:
                self.send({"processType": "KEEPALIVE", "intersectionId": iid, "peerID": self.peer}, it.es)
                self.last_keep[iid] = br.now()
        # グループ内の実機へ位置を送る（仮想車両どうしは送らない＝負荷削減。--vloc-all で全員へ）
        targets = {}
        for iid, mem in self.members.items():
            if self.ix[iid].joined:
                for p in mem:
                    if br.a.vloc_all or not p.startswith("sim-"):
                        if p in self.addrs:
                            targets[p] = self.addrs[p]
        if targets:
            msg = {"processType": "SendLocation", "locationUpdateCount": self.count,
                   "latitude": lat, "longitude": lon, "peerID": self.peer,
                   "speed": br.t.vehicle.getSpeed(self.vid) * 3.6}
            for addr in targets.values():
                self.send(msg, addr)

    def show_state(self):
        """sumo-gui の車両の右クリック→「Show Parameter」に，グループの状態を表示する."""
        if not self.br.a.gui or self.vid not in self.br.alive:
            return
        t = self.br.t
        try:
            t.vehicle.setParameter(self.vid, "glocon.es_on_route",
                                   " ".join(i.iid for i in self.ix.values() if i.es) or "-")
            t.vehicle.setParameter(self.vid, "glocon.joined",
                                   " ".join(i.iid for i in self.ix.values() if i.joined) or "-")
            t.vehicle.setParameter(self.vid, "glocon.left",
                                   " ".join(i.iid for i in self.ix.values() if i.es and i.left) or "-")
        except Exception:
            pass

    def leave(self, it, lat, lon, ev):
        br = self.br
        it.joined, it.left = False, True
        self.send(self.base("LEAVE", it.iid, lat, lon), it.es)
        self.members.pop(it.iid, None)
        br.joined_truth.get(it.iid, set()).discard(self.peer)
        br.changed[(it.iid, self.peer)] = br.now()
        br.stats["leave_sent"] += 1
        br.log(ev, self.peer, it.iid, f"dist={it.dist:.0f}")
        others = [i for i in self.ix.values() if i.joined]
        br.set_color(self.vid, others[0].iid if others else None)
        self.show_state()

    def on_message(self, msg, addr):
        br = self.br
        pt = msg.get("processType")
        if pt == "EDGE_SERVER_LIST":
            self.queried = True
            for e in msg.get("edgeServers", []):
                it = self.ix.get(e["intersectionId"])
                if it:                                   # アプリと同じく完全一致のみ採用
                    it.es = (br.a.override_es_ip or e["ip"], int(e["port"]))
            br.log("V_QUERY_OK", self.peer, "", f"es={sum(1 for i in self.ix.values() if i.es)}")
            self.show_state()
            return
        iid = msg.get("intersectionId")
        if iid not in self.ix:
            return                                       # SendLocation・NAT登録パケットなど
        if pt == "getPeripheralUserInfoList":
            mem = set()
            for u in msg.get("userList", []):
                p = u.get("peerID")
                mem.add(p)
                self.addrs[p] = (u.get("publicIP"), int(u.get("publicPort")))
            self.members[iid] = mem
            if iid in self.join_sent_wall:
                br.stats["join_replied"] += 1
                br.stats["join_rtt"].append(time.time() - self.join_sent_wall.pop(iid))
            br.log("V_MEMBERS", self.peer, iid, f"n={len(mem)}")
        elif pt == "doUDPHolePunching":
            p = msg.get("peerID")
            self.members.setdefault(iid, set()).add(p)
            self.addrs[p] = (msg.get("publicIP"), int(msg.get("publicPort")))
            br.stats["nat_register_rx"] += 1
        elif pt == "peerLeft":
            self.members.get(iid, set()).discard(msg.get("peerID"))
            br.stats["peer_left_rx"] += 1

    def close(self, why):
        br = self.br
        if self.vid in br.alive:
            lat, lon = self.user()
        else:
            lat = lon = 0.0
        for it in self.ix.values():
            if it.joined:
                self.leave(it, lat, lon, f"V_LEAVE_{why}")
        try:
            br.sel.unregister(self.sock)
        except Exception:
            pass
        self.sock.close()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--phones", type=int, default=3, help="受け付ける実機の台数（モードA=3, モードB=1）")
    ap.add_argument("--virtual", action="store_true", help="全車両を仮想クライアントとしてエッジサーバに参加させる（モードB）")
    ap.add_argument("--virtual-max", type=int, default=300, help="同時に動かす仮想クライアントの上限")
    ap.add_argument("--vloc-all", action="store_true", help="仮想クライアントどうしにも位置を送る（既定は実機にだけ送る）")
    ap.add_argument("--gui", action="store_true", help="sumo-gui で表示し，グループごとに色分けする")
    ap.add_argument("--speed", type=float, default=1.0, help="実時間に対する進み方（1.0=実時間。動作確認用に大きくできる）")
    ap.add_argument("--duration", type=float, default=0, help="シミュレーション時間の上限[秒]（0=最後の車が着くまで）")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--min-es-on-route", type=int, default=1, help="実機に割り当てる車の条件（ルート上のエッジサーバ数）")
    ap.add_argument("--bind-host", default="0.0.0.0")
    ap.add_argument("--bind-port", type=int, default=55700, help="スマホからの接続を受けるポート")
    ap.add_argument("--master", default=f"{common.EDGE_SERVER_IP}:55556", help="MasterServer の IP:ポート")
    ap.add_argument("--advertise-ip", default=common.EDGE_SERVER_IP,
                    help="仮想クライアントが名乗るIP（スマホから届くPCのIP。ホットスポットなら 192.168.137.1）")
    ap.add_argument("--client-bind", default="0.0.0.0")
    ap.add_argument("--override-es-ip", default=None, help="（試験用）エッジサーバのIPを置き換える")
    ap.add_argument("--local", action="store_true",
                    help="PCだけで試す（ホットスポット不要）: MasterServer・エッジサーバ・仮想クライアントを 127.0.0.1 で通信させる")
    a = ap.parse_args()
    if a.local:
        a.master, a.advertise_ip, a.override_es_ip = "127.0.0.1:55556", "127.0.0.1", "127.0.0.1"
    a.master_host, a.master_port = a.master.split(":")[0], int(a.master.split(":")[1])
    a.out = os.path.join(common.OUT_DIR, time.strftime("live_%Y%m%d_%H%M%S"))
    os.makedirs(a.out, exist_ok=True)

    common.sumo_home()
    import traci
    net = common.load_net()
    route_ix = {}
    with open(common.INTERSECTIONS_CSV, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            route_ix[r["junctionId"]] = (r["intersectionId"], float(r["lat"]), float(r["lon"]))

    cfg = os.path.join(common.SCENARIO_DIR, "scenario.sumocfg")
    cmd = [common.sumo_bin("sumo-gui" if a.gui else "sumo"), "-c", cfg, "--seed", str(a.seed),
           "--no-step-log", "true", "--no-warnings", "true",
           "--tripinfo-output", os.path.join(a.out, "tripinfo.xml")]
    if a.gui:
        cmd += ["--start", "--quit-on-end", "--delay", "0", "--window-size", "1400,1000",
                "--gui-settings-file", os.path.join(common.HERE, "gui_settings.xml")]
    traci.start(cmd)
    if a.gui:
        # エッジサーバのある交差点に，グループの色と同じ色の印（ES0〜）を置く
        for k, iid in enumerate(sorted(common.read_sim_edge_servers())):
            x, y = traci.junction.getPosition(common.read_sim_edge_servers()[iid])
            col = PALETTE[k % len(PALETTE)] + (255,)
            # 円（半径15m）は縮小しても消えないよう多角形で描き，ラベル（ES番号）は印で表示する
            circle = [(x + 15 * math.cos(2 * math.pi * i / 24), y + 15 * math.sin(2 * math.pi * i / 24))
                      for i in range(24)]
            traci.polygon.add(f"ES{k}_area", circle, col, fill=True, polygonType="edgeServer", layer=5)
            traci.poi.add(f"ES{k}", x, y, col, poiType="edgeServer", layer=6, width=6, height=6)
    ef = open(os.path.join(a.out, "events.csv"), "w", newline="", encoding="utf-8")
    ew = csv.writer(ef)
    ew.writerow(["simTime", "wallTime", "event", "peer", "target", "detail"])
    br = Bridge(a, traci, net, route_ix, ew)
    print(f"SimBridge 起動: スマホ待ち受け {a.bind_host}:{a.bind_port}, 実機 {a.phones} 台, "
          f"仮想クライアント {'あり' if a.virtual else 'なし'}, 出力 {a.out}")
    try:
        br.run()
    except KeyboardInterrupt:
        print("中断しました")
    finally:
        try:
            traci.close()
        except Exception:
            pass
        ef.close()
        s = br.stats
        rtt = sorted(s["join_rtt"])
        lines = [
            f"JOIN送信 {s['join_sent']} / 返信受信 {s['join_replied']} "
            f"({100 * s['join_replied'] / max(s['join_sent'], 1):.1f}%)",
            f"JOIN応答時間 中央値 {1000 * rtt[len(rtt) // 2]:.1f} ms, 95% {1000 * rtt[int(len(rtt) * .95)]:.1f} ms"
            if rtt else "JOIN応答時間 -",
            f"LEAVE送信 {s['leave_sent']}, 新規参加通知 {s['nat_register_rx']}, 離脱通知 {s['peer_left_rx']}",
            f"グループ一覧の一致率 {100 * s['match'] / max(s['check'], 1):.1f}% "
            f"({s['match']}/{s['check']} 回), 欠け {s['missing']} 件, 余分 {s['extra']} 件",
        ]
        with open(os.path.join(a.out, "summary.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
        print("\n".join(lines))


if __name__ == "__main__":
    main()
