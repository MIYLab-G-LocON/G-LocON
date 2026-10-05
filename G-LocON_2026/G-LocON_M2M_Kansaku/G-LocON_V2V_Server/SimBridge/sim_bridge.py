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
    ブリッジ → スマホ  SIM_ROUTE      {vehicleId, intersections:[{intersectionId,lat,lon}], shape:[[lat,lon],...], destLat, destLon,
                                       leaveDist, joinEta, joinDist}（shape は道の形。地図のルート線用）
                       SIM_LOCATION   {vehicleId, latitude, longitude, speed[m/s], bearing, simTime}（1秒ごと）
                       SIM_END        {vehicleId}         車が目的地に着いた（次の車が割り当てられる）
                       SIM_VEHICLES   {vehicles:[[peerID, lat, lon, bearing, 実機なら1], ...]}（1秒ごと）
                                      自車から --others-radius 以内の他の全車両（アプリの「表示:全車両」用。
                                      peerID は実機ならその端末名，それ以外は "sim-<車両ID>"）

■ 仮想クライアント（--virtual）
    SUMOの車1台ごとに専用のUDPソケットを持ち，アプリと同じ手順・同じ形式で
      INTERSECTION_QUERY（MasterServer）→ ETA<τ（既定15秒）でJOIN／通過後δ（既定60m）離れて遠ざかったらLEAVE／15秒ごとKEEPALIVE
    を送る。エッジサーバから届くメンバー一覧・追加・離脱通知でグループを管理し，
    グループ内の実機へ位置（SendLocation）を1秒ごとに送る（実機の地図に仮想車両として表示される）。
    peerID は "sim-<車両ID>"。

■ 車両制御（--control）
    off    : 急停止イベントを起こさない（既定。グルーピングの確認用）
    none   : 急停止イベントを起こすが，情報は誰にも届けない（V2Vなし。比較の下限）
    system : 急停止した車が，参加中の交差点グループのメンバーへ P2P で危険情報を送る
             （SendLocation に hazard を付けて1秒ごと，解消時に active=false）。
             受け取った車は，自分のルートの前方にその地点がある（接近している）ときだけ減速する。
             減速は VEHICLE_COMMAND（DECELERATE / RESUME）としてブリッジが SUMO に反映する。
             仮想クライアントは自分で判定してブリッジに依頼し，実機はアプリが判定して
             VEHICLE_COMMAND {command, hazardId} をブリッジへ送る。
    none / system では run_scenario.py と同じ SUMO 出力（ssm.xml・fcd.xml・collisions.xml）と hazard_log.csv を出すので，
    summarize.py で「V2Vなし／理想V2V／本システム」を同じ指標で比べられる。
    followers.csv には，急停止した車に後ろから近づいた車（正解）と，その車に減速指示が届いたかを記録する。
    --hazard-rule follower で，後続車がいるときだけ急停止させる（run_scenario.py と同じ）。

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
import hazard_eval

KEEPALIVE_SEC = 15.0
UPDATE_SEC = 1.0          # アプリと同じ1秒ごとの位置更新
MIN_SPEED = 1.0           # アプリと同じ（ETA計算の最低速度）
PASS_RADIUS_M = 20.0      # アプリと同じ: この距離まで近づいたら交差点を「通過済み」とする
PASS_LOOKAHEAD = 3        # アプリと同じ: 通過判定はまだ通過していない最初の交差点からこの個数先まで
GRACE_SEC = 2.0           # 一覧の一致判定で，直近のJOIN/LEAVEを通知待ちとして除外する時間
HAZARD_HOLD_SEC = 3.0     # 危険情報がこの時間届かなければ解消とみなして復帰する（解消通知の取りこぼし対策）
APPROACH_SIDE_M = 12.0    # 危険地点が自分のルートの線からこの距離以内なら「ルート上」とみなす
APPROACH_ANGLE_DEG = 60.0 # 危険車両の向きと，その地点での自分のルートの向きの差がこれ以内なら同じ方向
APPROACH_MAX_M = 400.0    # これより先の危険地点は対象外
PENDING_MAX_SEC = 15.0    # 表示の色替えを待つ最大時間（止まった車などで尻尾が円から出ない場合）
COLOR_STOPPED = (0, 0, 0)          # sumo-gui: 急停止中の車（黒。赤い円で囲む）
COLOR_SLOWING = (140, 80, 20)      # sumo-gui: 危険情報を受けて減速中の車（茶色。減速を始めたときにオレンジの円）
PALETTE = [(230, 25, 75), (60, 180, 75), (255, 225, 25), (0, 130, 200), (245, 130, 48),
           (145, 30, 180), (70, 240, 240), (240, 50, 230), (210, 245, 60), (0, 128, 128)]


def hubeny(lat1, lon1, lat2, lon2):
    """アプリの HubenyDistance と同等の距離[m]（短距離なので正距円筒近似で十分）."""
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    m = math.cos(math.radians((lat1 + lat2) / 2))
    return 6378137.0 * math.hypot(dlat, dlon * m)


def project_on_path(path, cum, px, py, s_min=-1e9):
    """折れ線 path（[(x,y)]，cum=各点までの道のり）に点を射影した候補を返す.

    [(道のり s, 線からの距離 d, その区間の向き[度, 北=0の時計回り])] を s の小さい順に返す（s >= s_min のみ）.
    """
    out = []
    for i in range(len(path) - 1):
        (x1, y1), (x2, y2) = path[i], path[i + 1]
        dx, dy = x2 - x1, y2 - y1
        l2 = dx * dx + dy * dy
        if l2 <= 0:
            continue
        u = max(0.0, min(1.0, ((px - x1) * dx + (py - y1) * dy) / l2))
        s = cum[i] + u * math.sqrt(l2)
        if s < s_min:
            continue
        d = math.hypot(px - (x1 + u * dx), py - (y1 + u * dy))
        out.append((s, d, math.degrees(math.atan2(dx, dy)) % 360.0))
    return out


def angle_diff(a, b):
    return abs((a - b + 180.0) % 360.0 - 180.0)


def hazard_ahead(path, cum, me_xy, s_last, hx, hy, h_bearing):
    """危険地点が自分のルートの前方にあるか（アプリでも同じ判定ができるよう，ルートの線と位置・向きだけで決める）.

    戻り値: (前方までの道のり gap [m] または None, 自分の道のり s_me)
    """
    mine = project_on_path(path, cum, me_xy[0], me_xy[1], s_last - 5.0)
    if not mine:
        return None, s_last
    s_me = min(mine, key=lambda c: (c[1], c[0]))[0]
    for s, d, seg_bearing in project_on_path(path, cum, hx, hy, s_me):
        if d <= APPROACH_SIDE_M and angle_diff(seg_bearing, h_bearing) <= APPROACH_ANGLE_DEG:
            gap = s - s_me
            if 0 < gap <= APPROACH_MAX_M:
                return gap, s_me
            return None, s_me
    return None, s_me


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
        # 色は edge_servers.csv の行（ポート）の順。--move で交差点を移しても色は変わらない
        self.es_index = {iid: k for k, iid in enumerate(common.read_sim_edge_servers())}
        self.es_xy = {}                     # iid -> 交差点のSUMO座標（色替えの判定用）
        # LEAVE後の色替え待ち: vid -> (色, 離れた交差点のiid, 待ち始めた時刻)
        # sumo-gui は車を vehicle_exaggeration 倍に拡大して先端から後ろへ描くため，
        # LEAVE（車の中心で判定）した時点では描かれた車の後ろがまだ離脱円の中に見える。
        # 表示だけ，描かれた車の後端が円を出るまで色替えを待つ（LEAVEの送信・記録のタイミングは変えない）
        self.pending_color = {}
        self.base_col = {}                  # 車 -> グループの色（急停止・減速の色を戻すときに使う）
        self.exaggeration = common.gui_vehicle_exaggeration() if a.gui else 1.0
        self.stats = {"join_sent": 0, "join_replied": 0, "join_rtt": [], "leave_sent": 0,
                      "peer_left_rx": 0, "nat_register_rx": 0, "check": 0, "match": 0,
                      "missing": 0, "extra": 0, "query_fail": 0}
        # ---- 車両制御（--control none / system）
        self.hazards = []                   # 急停止イベントの予定（scenario/hazards.json）
        self.routes = None                  # run_scenario.RouteIndex（交差点までの道のり）
        self.hw = None                      # hazard_log.csv
        self.stopping = []                  # 急停止中の車 [{veh, until, iid, hid}]
        self.warned = {}                    # 減速中の車 vid -> {orig, hazards:{hid: 最後に受信した時刻}}
        self.via_cache = {}                 # (道路, 次の道路) → 交差点の中を通る線
        self.followers = None               # 急停止した車に近づいた車の記録（hazard_eval.FollowerTracker）
        self.last_stop = {}                 # 交差点 → 最後に急停止させた時刻（--hazard-rule follower）
        self.notified = set()               # (受信者, hid) 初回受信を記録済み
        self.ctl = {"stops": 0, "sent_ok": 0, "not_in_group": 0, "notified": 0, "decel": 0,
                    "ignored": 0, "latency": []}
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
        elif pt == "VEHICLE_COMMAND" and ph and ph.vid:
            # 実機のアプリが危険情報を受け取り，接近中と判定した（または解消した）
            self.vehicle_command(ph.vid, msg.get("command"), msg.get("hazardId", "?"),
                                 src=ph.peer, gap=msg.get("gap"))
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

    def route_shape(self, vid):
        """今いる道路から目的地までの道の形 [[緯度, 経度], ...]（アプリの地図のルート線用）."""
        pts = []
        for x, y in self.route_xy(vid):
            lon, lat = self.net.convertXY2LonLat(x, y)
            p = [round(lat, 6), round(lon, 6)]
            if not pts or pts[-1] != p:
                pts.append(p)
        return pts

    def junction_path(self, e1, e2):
        """道路 e1 から e2 へ交差点の中を通る線（SUMO座標）。右左折の曲線になる.

        道路の形だけをつなぐと，交差点の手前の端から次の道路の端へ直線で結ばれ，
        地図のルート線が角の敷地を斜めに横切ってしまう。
        """
        key = (e1, e2)
        if key not in self.via_cache:
            pts = []
            try:
                for k in range(self.t.edge.getLaneNumber(e1)):
                    link = next((ln for ln in self.t.lane.getLinks(f"{e1}_{k}")
                                 if ln[0].rsplit("_", 1)[0] == e2), None)
                    if link is None:
                        continue
                    via, guard = link[4], 0
                    while via and guard < 5:              # 交差点内の線は途中で分かれていることがある
                        pts += list(self.t.lane.getShape(via))
                        nxt = self.t.lane.getLinks(via)
                        via = nxt[0][4] if nxt else ""
                        guard += 1
                    break
            except Exception:
                pts = []
            self.via_cache[key] = pts
        return self.via_cache[key]

    def route_xy(self, vid):
        """今いる道路から目的地までの道の線（SUMO座標）。交差点の中の曲線も含む."""
        route = self.t.vehicle.getRoute(vid)
        idx = max(self.t.vehicle.getRouteIndex(vid), 0)
        pts = []
        for k, e in enumerate(route[idx:], idx):
            seg = list(self.net.getEdge(e).getShape())
            if k + 1 < len(route):
                seg += self.junction_path(e, route[k + 1])
            for p in seg:
                if not pts or pts[-1] != p:
                    pts.append(p)
        return pts

    def send_route(self, ph):
        seq = self.route_intersections(ph.vid)
        dest_edge = self.t.vehicle.getRoute(ph.vid)[-1]
        x, y = self.net.getEdge(dest_edge).getToNode().getCoord()
        dlon, dlat = self.net.convertXY2LonLat(x, y)
        self.send_phone(ph, {"processType": "SIM_ROUTE", "vehicleId": ph.vid, "leaveDist": common.LEAVE_DIST_M,
                             "joinEta": common.JOIN_ETA_SEC, "joinDist": common.JOIN_DIST_M,
                             "intersections": [{"intersectionId": i, "lat": la, "lon": lo} for i, la, lo in seq],
                             "shape": self.route_shape(ph.vid),
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
            self.mark_phone(vid, ph)

    def update_phones(self, arrived):
        riding = [v for v in self.phone_vehicles if v not in arrived and v in self.alive]
        pos = {}
        if riding and self.a.others_radius > 0:
            pos = {v: common.vehicle_xy(self.t, v) for v in self.alive}
        for vid in list(self.phone_vehicles):
            ph = self.phone_vehicles[vid]
            if vid in arrived or vid not in self.alive:
                self.send_phone(ph, {"processType": "SIM_END", "vehicleId": vid})
                self.log("PHONE_END", ph.peer, vid, "")
                ph.vid = None
                del self.phone_vehicles[vid]
                continue
            x, y = common.vehicle_xy(self.t, vid)            # 車の中心（スマホは車内にある）
            lon, lat = self.net.convertXY2LonLat(x, y)
            self.send_phone(ph, {"processType": "SIM_LOCATION", "vehicleId": vid,
                                 "latitude": lat, "longitude": lon,
                                 "speed": self.t.vehicle.getSpeed(vid),
                                 "bearing": self.t.vehicle.getAngle(vid), "simTime": self.now(),
                                 # 自分の車が急停止中なら知らせる（アプリが SendLocation に hazard を付けて送る）
                                 **({"hazard": ph.hazard} if ph.hazard else {})})
            if ph.hazard and not ph.hazard.get("active", True):
                ph.hazard = None                 # 解消は1回送れば十分
            if pos:
                self.send_others(ph, vid, pos)

    def send_others(self, ph, vid, pos):
        """自車の周りの全車両を送る（P2Pでつながっていない車も地図に出して違いを見るため）."""
        x0, y0 = pos[vid]
        r2 = self.a.others_radius ** 2
        near = sorted((((x - x0) ** 2 + (y - y0) ** 2), v) for v, (x, y) in pos.items()
                      if v != vid and (x - x0) ** 2 + (y - y0) ** 2 <= r2)[:300]
        rows = []
        for _, v in near:
            lon, lat = self.net.convertXY2LonLat(*pos[v])
            other = self.phone_vehicles.get(v)
            rows.append([other.peer if other else f"sim-{v}", round(lat, 6), round(lon, 6),
                         round(self.t.vehicle.getAngle(v)), 1 if other else 0])
        self.send_phone(ph, {"processType": "SIM_VEHICLES", "vehicles": rows})

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

    # ---------- 車両制御（急停止イベントと VEHICLE_COMMAND） ----------
    def hlog(self, iid, hv, event, target="", detail=""):
        if self.hw:
            self.hw.writerow([f"{self.now():.1f}", iid, hv, event, target, detail])

    def step_hazards(self):
        """急停止イベントの発生・維持・解除（run_scenario.py と同じ条件。車両IDと場所が同じなので比較できる）."""
        t, now = self.t, self.now()
        for h in self.hazards:
            if h.get("done"):
                continue
            v = h["vehicle"]
            if v not in self.alive:
                if h.get("seen"):
                    h["done"] = True
                    self.hlog(h["intersectionId"], v, "SKIP_ARRIVED")
                continue
            h["seen"] = True
            d = self.routes.distance(v, h["intersectionId"])
            if d is None:
                h["done"] = True
                self.hlog(h["intersectionId"], v, "SKIP_PASSED")
                continue
            spd = t.vehicle.getSpeed(v)
            if d > h["trigger_m"] or spd < 3.0:
                continue
            if self.a.hazard_rule == "follower":
                if (not hazard_eval.may_stop(self.last_stop, h["intersectionId"], now)
                        or not hazard_eval.follower_exists(t, v, self.alive)):
                    continue               # 後続車が現れるのを待つ（交差点を過ぎたら SKIP_PASSED）
                hazard_eval.mark_stop(self.last_stop, h["intersectionId"], now)
            h["done"] = True
            t.vehicle.setDecel(v, h["decel"])
            t.vehicle.slowDown(v, 0.0, max(spd / h["decel"], 0.5))
            hid = f"{v}@{now:.0f}"
            self.stopping.append({"veh": v, "until": now + h["stop_sec"] + spd / h["decel"],
                                  "iid": h["intersectionId"], "hid": hid, "t0": now})
            self.ctl["stops"] += 1
            self.apply_color(v)
            self.ping(v, (255, 0, 0), h["stop_sec"] + spd / h["decel"], 1)
            self.hlog(h["intersectionId"], v, "SUDDEN_STOP", "", f"speed={spd:.1f},dist={d:.1f}")
            if self.a.control == "system":
                self.set_hazard(v, {"id": hid, "intersectionId": h["intersectionId"], "active": True})
        for s in list(self.stopping):
            v = s["veh"]
            if v not in self.alive:
                self.stopping.remove(s)
                continue
            if now >= s["until"]:
                t.vehicle.setSpeed(v, -1)
                t.vehicle.setDecel(v, 3.0)
                self.stopping.remove(s)
                self.apply_color(v)
                self.hlog(s["iid"], v, "RESUME_HAZARD")
                if self.a.control == "system":
                    self.set_hazard(v, {"id": s["hid"], "intersectionId": s["iid"], "active": False})
            else:
                t.vehicle.setSpeed(v, 0.0)

    def set_hazard(self, vid, hz):
        """急停止した車（仮想クライアントまたは実機）に，危険情報を送らせる."""
        c = self.clients.get(vid)
        if c:
            c.hazard = hz
            if hz["active"]:
                c.announce_hazard_state()
        ph = self.phone_vehicles.get(vid)
        if ph:
            ph.hazard = hz
        if not c and not ph and hz["active"]:
            # 仮想クライアントも実機も付いていない車（--virtual なし，上限超過など）は送れない
            self.ctl["not_in_group"] += 1
            self.hlog(hz["intersectionId"], vid, "HAZARD_NO_CLIENT")

    def vehicle_command(self, vid, command, hid, src="", gap=None):
        """VEHICLE_COMMAND: 危険情報を受けて接近中と判定した車を減速させる／解消で戻す."""
        if vid not in self.alive:
            return
        now = self.now()
        w = self.warned.get(vid)
        if command == "DECELERATE":
            if w is None:
                w = self.warned[vid] = {"orig": self.t.vehicle.getMaxSpeed(vid), "hazards": {}}
                self.apply_color(vid)
                self.ping(vid, (255, 140, 0), 3.0, 2)
            if hid not in w["hazards"]:
                self.ctl["decel"] += 1
                self.hlog("", hid.split("@")[0], "DECELERATE", vid,
                          f"gap={gap:.1f}" if isinstance(gap, (int, float)) else f"src={src}")
            w["hazards"][hid] = now
        elif command == "RESUME" and w is not None:
            w["hazards"].pop(hid, None)

    def step_warned(self, step_len):
        """減速中の車の希望速度を段階的に下げ，危険情報が無くなったら元に戻す."""
        now = self.now()
        for vid, w in list(self.warned.items()):
            if vid not in self.alive:
                del self.warned[vid]
                continue
            for hid, last in list(w["hazards"].items()):
                if now - last > HAZARD_HOLD_SEC:
                    del w["hazards"][hid]
            if w["hazards"]:
                hazard_eval.slow_down_step(self.t, vid, self.a.warn_speed, step_len)
            else:
                self.t.vehicle.setMaxSpeed(vid, w["orig"])
                del self.warned[vid]
                self.apply_color(vid)
                self.hlog("", "", "RESUME", vid)

    def mark_phone(self, vid, ph):
        """sumo-gui で実機が乗っている車を目立たせる（紫の大きな円で囲み，ラベルに端末名を出す）."""
        if not self.a.gui:
            return
        try:
            self.t.vehicle.highlight(vid, (255, 0, 255, 255), size=25)
            self.t.vehicle.setParameter(vid, "glocon.phone", ph.peer)
            if self.a.follow_phone:
                self.t.gui.trackVehicle("View #0", vid)
                self.t.gui.setZoom("View #0", 400)
        except Exception:
            pass

    # ---------- sumo-gui の色分け ----------
    def color_after_leave(self, vid, kind, left_iid):
        """LEAVE 後の色替え。描かれた車の後端が離脱円を出てから替える（表示のみ）."""
        if not self.a.gui:
            return
        self.pending_color[vid] = (kind, left_iid, self.now())
        self.update_pending_colors()

    def update_pending_colors(self):
        for vid, (kind, iid, t0) in list(self.pending_color.items()):
            if vid not in self.alive:
                del self.pending_color[vid]
                continue
            if iid not in self.es_xy:
                self.es_xy[iid] = self.t.junction.getPosition(common.read_sim_edge_servers()[iid])
            jx, jy = self.es_xy[iid]
            x, y = self.t.vehicle.getPosition(vid)                    # 先端
            a = math.radians(self.t.vehicle.getAngle(vid))
            drawn = self.t.vehicle.getLength(vid) * self.exaggeration   # 描かれている車の長さ
            tx, ty = x - drawn * math.sin(a), y - drawn * math.cos(a)  # 描かれた車の後端
            if math.hypot(tx - jx, ty - jy) >= common.LEAVE_DIST_M or self.now() - t0 > PENDING_MAX_SEC:
                del self.pending_color[vid]
                self.set_color(vid, kind)

    def set_color(self, vid, kind):
        if not self.a.gui or vid not in self.alive:
            return
        if kind == "phone":
            col = (255, 0, 255)
        elif kind is None:
            col = (160, 160, 160)
        else:
            col = PALETTE[self.es_index.get(kind, 0) % len(PALETTE)]
        self.base_col[vid] = col
        self.apply_color(vid)

    def apply_color(self, vid):
        """車の色を塗る。急停止中は黒，危険情報を受けて減速中は茶色，それ以外はグループの色（表示のみ）."""
        if not self.a.gui or vid not in self.alive:
            return
        if any(s["veh"] == vid for s in self.stopping):
            col = COLOR_STOPPED
        elif vid in self.warned:
            col = COLOR_SLOWING
        else:
            col = self.base_col.get(vid, (160, 160, 160))
        try:
            self.t.vehicle.setColor(vid, col + (255,))
        except Exception:
            pass

    def ping(self, vid, col, sec, kind):
        """車を一定時間，円で囲んで目立たせる（急停止 = 赤，減速開始 = オレンジ。表示のみ）."""
        if not self.a.gui or vid not in self.alive:
            return
        try:
            self.t.vehicle.highlight(vid, col + (255,), 30, 255, max(sec, 1.0), kind)
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
                self.update_pending_colors()
                self.assign_phones(departed)
                if self.a.virtual:
                    self.update_virtual(departed, arrived)
                if self.a.control != "off":
                    self.step_hazards()        # 仮想クライアントを作った後（出発直後の急停止でも送れるように）
                if self.a.control == "system":
                    self.step_warned(step)
                if self.followers:
                    self.followers.step(self.now(), self.stopping, self.warned, self.alive)
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
        self.hazard = None                  # 乗っている車が急停止中なら {id, intersectionId, active}


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
        # 車両制御用: 自分のルートの線（SUMO座標）と，急停止中なら送る危険情報
        self.path, self.cum, self.s_me = [], [], 0.0
        self.hazard = None
        if br.a.control == "system":
            self.path = br.route_xy(vid)
            d = 0.0
            for i, p in enumerate(self.path):
                if i:
                    d += math.hypot(p[0] - self.path[i - 1][0], p[1] - self.path[i - 1][1])
                self.cum.append(d)
        self.query()

    # アプリの UserInfo 相当
    def user(self):
        x, y = common.vehicle_xy(self.br.t, self.vid)     # 車の中心（実機と同じ）
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
                if eta < common.JOIN_ETA_SEC or it.dist < common.JOIN_DIST_M:
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
                    br.pending_color.pop(self.vid, None)     # 前の交差点の色替え待ちは取り消す
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
                    # 急停止中（危険情報あり）は，仮想車両を含むグループの全員へ送る
                    if br.a.vloc_all or self.hazard or not p.startswith("sim-"):
                        if p in self.addrs:
                            targets[p] = self.addrs[p]
        if targets:
            msg = {"processType": "SendLocation", "locationUpdateCount": self.count,
                   "latitude": lat, "longitude": lon, "peerID": self.peer,
                   "speed": br.t.vehicle.getSpeed(self.vid) * 3.6}
            if self.hazard:
                msg["hazard"] = dict(self.hazard, latitude=lat, longitude=lon,
                                     bearing=br.t.vehicle.getAngle(self.vid), sentWall=time.time())
            for addr in targets.values():
                self.send(msg, addr)
        if self.hazard and not self.hazard["active"]:
            self.hazard = None                 # 解消は1回送れば十分（届かなくても受信側が時間切れで復帰する）

    def announce_hazard_state(self):
        """急停止した時点で，危険情報を届けられる相手（参加中のグループのメンバー）がいるかを記録する."""
        br = self.br
        n = len({p for iid, mem in self.members.items() if self.ix[iid].joined for p in mem if p in self.addrs})
        joined = [i.iid for i in self.ix.values() if i.joined]
        if joined:
            br.ctl["sent_ok"] += 1
            br.hlog(self.hazard["intersectionId"], self.vid, "HAZARD_SEND", "", f"groups={len(joined)},members={n}")
        else:
            br.ctl["not_in_group"] += 1
            br.hlog(self.hazard["intersectionId"], self.vid, "HAZARD_NOT_IN_GROUP")
        self.next_tick = br.now()              # 次の更新を待たずにすぐ送る

    def on_hazard(self, msg):
        """同じグループの車から届いた危険情報。接近中のときだけ減速を依頼する."""
        br, hz = self.br, msg["hazard"]
        hid = hz.get("id", "?")
        if not hz.get("active", True):
            br.vehicle_command(self.vid, "RESUME", hid)
            return
        if self.vid not in br.alive or not self.path:
            return
        hx, hy = br.net.convertLonLat2XY(hz["longitude"], hz["latitude"])
        gap, self.s_me = hazard_ahead(self.path, self.cum, common.vehicle_xy(br.t, self.vid), self.s_me,
                                      hx, hy, hz.get("bearing", 0.0))
        key = (self.vid, hid)
        if key not in br.notified:
            br.notified.add(key)
            br.ctl["notified"] += 1
            lat_ms = (time.time() - hz["sentWall"]) * 1000 if "sentWall" in hz else -1
            br.ctl["latency"].append(lat_ms)
            if gap is None:
                br.ctl["ignored"] += 1
            br.hlog(hz.get("intersectionId", ""), msg.get("peerID", "").replace("sim-", ""),
                    "NOTIFIED", self.vid, f"latency_ms={lat_ms:.1f},approaching={gap is not None}")
        if gap is not None:
            br.vehicle_command(self.vid, "DECELERATE", hid, gap=gap)

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
        br.color_after_leave(self.vid, others[0].iid if others else None, it.iid)
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
        if pt == "SendLocation":
            if "hazard" in msg and br.a.control == "system":
                self.on_hazard(msg)
            return
        iid = msg.get("intersectionId")
        if iid not in self.ix:
            return                                       # NAT登録パケットなど
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
    ap.add_argument("--join-eta", type=float, default=common.JOIN_ETA_SEC,
                    help="参加タイミング τ [秒]: 交差点までのETAがこれを下回ったらJOIN（実機にも同じ値を送る）。評価では 15 / 30 / 45")
    ap.add_argument("--control", choices=["off", "none", "system"], default="off",
                    help="車両制御: off=急停止なし（既定），none=急停止あり・通知なし，"
                         "system=急停止の情報をグループ経由(P2P)で送り，接近中の車だけ減速させる")
    ap.add_argument("--hazard-rule", choices=["fixed", "follower"], default="fixed",
                    help="急停止の起こし方（follower=後ろ20〜150mに後続車がいるときだけ。run_scenario.py と同じ）")
    ap.add_argument("--warn-speed", type=float, default=5.0, help="減速指示を受けた車の目標速度 [m/s]")
    ap.add_argument("--no-fcd", action="store_true", help="--control 時に fcd.xml を出力しない（急制動の集計ができなくなる）")
    ap.add_argument("--join-dist", type=float, default=common.JOIN_DIST_M,
                    help="参加円の半径 ρ [m]: 交差点までの直線距離がこれ未満なら ETA に関係なくJOIN（0 = なし。評価では 0 / 50 / 100 / 150）")
    ap.add_argument("--leave-dist", type=float, default=common.LEAVE_DIST_M,
                    help="離脱円の半径 δ [m]（実機にも同じ値を送る）。評価では 30 / 60 / 100")
    ap.add_argument("--follow-phone", action="store_true",
                    help="sumo-gui の画面を実機が乗っている車に追従させる（乗り換えても追従する）")
    ap.add_argument("--others-radius", type=float, default=400,
                    help="実機へ送る周りの全車両の範囲 [m]（アプリの「表示:全車両」用。0で送らない）")
    ap.add_argument("--speed", type=float, default=1.0, help="実時間に対する進み方（1.0=実時間。動作確認用に大きくできる）")
    ap.add_argument("--duration", type=float, default=0, help="シミュレーション時間の上限[秒]（0=最後の車が着くまで）")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--min-es-on-route", type=int, default=3,
                    help="実機に割り当てる車の条件（ルートが通るエッジサーバ交差点の数がこれ以上）")
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
    common.LEAVE_DIST_M = a.leave_dist
    common.JOIN_ETA_SEC = a.join_eta
    common.JOIN_DIST_M = a.join_dist
    a.out = os.path.join(common.OUT_DIR, time.strftime("live_%Y%m%d_%H%M%S")
                         + (f"_{a.control}" if a.control != "off" else "")
                         + f"_t{int(a.join_eta)}_j{int(a.join_dist)}_d{int(a.leave_dist)}"
                         + ("_hf" if a.control != "off" and a.hazard_rule == "follower" else ""))
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
    if a.control != "off":
        # run_scenario.py と同じ出力（summarize.py で V2Vなし／理想V2V と同じ指標で比べる）
        cmd += ["--device.ssm.probability", "1", "--device.ssm.measures", "TTC PET DRAC",
                "--device.ssm.thresholds", "3.0 2.0 3.0", "--device.ssm.range", "50",
                "--device.ssm.file", os.path.join(a.out, "ssm.xml"),
                "--device.emissions.probability", "1",
                "--collision.action", "warn", "--collision.check-junctions", "true",
                "--collision-output", os.path.join(a.out, "collisions.xml")]
        if not a.no_fcd:
            cmd += ["--fcd-output", os.path.join(a.out, "fcd.xml"), "--fcd-output.acceleration", "true"]
    if a.gui:
        cmd += ["--start", "--quit-on-end", "--delay", "0", "--window-size", "1400,1000",
                "--gui-settings-file", os.path.join(common.HERE, "gui_settings.xml")]
    traci.start(cmd)
    if a.gui:
        # エッジサーバのある交差点に，グループの色と同じ色の印（ES0〜）を置く
        for k, (iid, jid) in enumerate(common.read_sim_edge_servers().items()):   # 行（ポート）の順
            x, y = traci.junction.getPosition(jid)
            col = PALETTE[k % len(PALETTE)] + (255,)
            # 円（半径15m）は縮小しても消えないよう多角形で描き，ラベル（ES番号）は印で表示する
            circle = [(x + 15 * math.cos(2 * math.pi * i / 24), y + 15 * math.sin(2 * math.pi * i / 24))
                      for i in range(24)]
            traci.polygon.add(f"ES{k}_area", circle, col, fill=True, polygonType="edgeServer", layer=5)
            # 離脱円（半径 δ）の輪郭
            ring = [(x + common.LEAVE_DIST_M * math.cos(2 * math.pi * i / 48),
                     y + common.LEAVE_DIST_M * math.sin(2 * math.pi * i / 48)) for i in range(48)]
            traci.polygon.add(f"ES{k}_leave", ring, col, fill=False, polygonType="leaveCircle", layer=4, lineWidth=1.5)
            if common.JOIN_DIST_M > 0:      # 参加円（半径 ρ）の輪郭。細い線
                jring = [(x + common.JOIN_DIST_M * math.cos(2 * math.pi * i / 48),
                          y + common.JOIN_DIST_M * math.sin(2 * math.pi * i / 48)) for i in range(48)]
                traci.polygon.add(f"ES{k}_join", jring, col, fill=False, polygonType="joinCircle", layer=4, lineWidth=0.6)
            traci.poi.add(f"ES{k}", x, y, col, poiType="edgeServer", layer=6, width=6, height=6)
    ef = open(os.path.join(a.out, "events.csv"), "w", newline="", encoding="utf-8")
    ew = csv.writer(ef)
    ew.writerow(["simTime", "wallTime", "event", "peer", "target", "detail"])
    br = Bridge(a, traci, net, route_ix, ew)
    hf = None
    if a.control != "off":
        from run_scenario import RouteIndex
        br.hazards = hazard_eval.load_hazards(a.hazard_rule)
        br.followers = hazard_eval.FollowerTracker(traci, os.path.join(a.out, "followers.csv"))
        br.routes = RouteIndex(traci, net, common.read_sim_edge_servers())
        hf = open(os.path.join(a.out, "hazard_log.csv"), "w", newline="", encoding="utf-8")
        br.hw = csv.writer(hf)
        br.hw.writerow(["t", "intersectionId", "hazard_vehicle", "event", "target", "detail"])
    print(f"SimBridge 起動: スマホ待ち受け {a.bind_host}:{a.bind_port}, 実機 {a.phones} 台, "
          f"仮想クライアント {'あり' if a.virtual else 'なし'}, 参加 ETA<{a.join_eta:.0f}秒 または {a.join_dist:.0f} m 以内, 離脱円 {a.leave_dist:.0f} m, "
          f"車両制御 {a.control}, 出力 {a.out}")
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
        if hf:
            hf.close()
        if br.followers:
            br.followers.close()
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
        if a.control != "off":
            c = br.ctl
            lat = sorted(x for x in c["latency"] if x >= 0)
            lines.append(f"車両制御 {a.control}: 急停止 {c['stops']} 件")
            if a.control == "system":
                lines += [
                    f"  危険情報を送れた（グループ参加中）{c['sent_ok']} 件 / 送れなかった（未参加）{c['not_in_group']} 件",
                    f"  受信 {c['notified']} 台・件（接近中と判定して減速 {c['decel']}，初回受信時に対象外 {c['ignored']}）",
                    f"  通知の遅延 中央値 {lat[len(lat) // 2]:.1f} ms, 95% {lat[int(len(lat) * .95)]:.1f} ms"
                    if lat else "  通知の遅延 -",
                ]
        with open(os.path.join(a.out, "summary.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
        print("\n".join(lines))


if __name__ == "__main__":
    main()
