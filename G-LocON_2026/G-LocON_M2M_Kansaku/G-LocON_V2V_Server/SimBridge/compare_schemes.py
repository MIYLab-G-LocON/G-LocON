"""接続相手の決め方の比較: 本システム（進行先の交差点・ETA）と 従来G-LocON（自車の周りの距離）.

    python compare_schemes.py [--seed 1] [--tag s1]
    python compare_schemes.py --period 0.75 --tag p075        # 交通量を変える（車の発生間隔 [秒]。既定のシナリオは 1.5）
    python compare_schemes.py --es-count 40 --tag es40        # エッジサーバの数を変える（交通量の多い交差点から順に選ぶ）

--period / --es-count を付けると，scenario/ のファイルは変えずに，その条件の交通流・エッジサーバ配置をその場で作って使う
（サーバは起動しないので，エッジサーバをいくつにしても PC の負荷は変わらない）。

同じ SUMO の走行（V2Vなし・急停止あり）の上で，複数の方式を同時に計算する。
車の動きは全方式で完全に同じなので，差は「誰とつなぐか」の決め方だけから生じる（通信の損失・遅延は無いものとする）。

方式:
    eta_t<τ>_d<δ>   本システム: エッジサーバ交差点への ETA < τ で参加，通過して δ 離れ遠ざかったら離脱。
                    同じ交差点グループの車どうしがつながる
    eta_t<τ>_d<δ>_j<ρ>  上に加えて，交差点までの直線距離が ρ 未満（参加円の中）なら ETA に関係なく参加（今の既定は ρ=100）
                    （渋滞でゆっくり進む車は ETA が大きくなり，交差点のすぐ手前にいても参加しないため）
    dist_r<R>       従来G-LocON: 各車が T 秒ごとにサーバへ問い合わせ，半径 R 以内の車とつながる。
                    元のアプリ（G-LocON_2024 の MainActivity）は searchRange=100m，位置の更新2回ごと（約2秒）に問い合わせる

出力 out/compare_<tag>/:
    schemes.csv   方式ごとの指標（下記）
    pairs_<方式>.csv  交差点で出会った2台ごとの「つながっていたか・何秒前からか」

指標:
    peers_mean / peers_p95  1台が同時につながっている相手の数（平均・95%）
    msgs_per_veh_s          位置情報の送信数（1台1秒あたり。相手1台につき毎秒1通）
    setups_per_veh_min      新しく相手とつながった回数（1台1分あたり）。多いほどつなぎ直しが多い
    conn_sec_median         1回の接続が続いた時間（中央値）
    ctrl_per_veh_min        サーバとの制御メッセージ数（1台1分あたり。数え方は count_control を参照）
    precision               つながっている相手のうち，関係のある相手の割合
    recall                  関係のある相手のうち，つながっている割合
        「関係のある相手」= この先 H 秒（既定10秒）以内に同じ交差点に入る車，または 100m 以内の前後の車
    met_n / met_connected / met_lead3 / met_lead_median
        交差点で出会った2台（同じ交差点を 5秒以内に続けて通った2台）について，
        先の車が着いた時点でつながっていた割合，3秒以上前からつながっていた割合，つながってからの時間（中央値）。
        _es はエッジサーバのある交差点だけ，_all はエリア内の全交差点
    noshare_pct             ルートが1か所も交わらない相手との接続の割合（別の道を走っていて近くを通っただけの相手）
    far_pct                 接続の間に一度も 50m 以内に近づかなかった接続の割合
    wasted_pct / wasted_per_veh_min
        無駄な接続: ルートが交わらない，または一度も 50m 以内に近づかなかった接続の割合と，1台1分あたりの回数。
        （交わらない道で 50m 以内を並走しただけの相手も無駄に数える）
    short_pct               10秒以下で切れた接続の割合
    pairs / reconnect_pct   一度でもつながった2台の組の数，そのうち2回以上つながり直した組の割合
    reconn_per_veh_min      つなぎ直し（同じ2台の2回目以降の接続）の回数（1台1分あたり）
    reconn_quick_pct        つなぎ直しのうち，切れてから10秒以内につながり直したもの（境目での出入りなど）
    reconn_near_pct         つなぎ直しのうち，切れている間も 50m 以内にいたもの（近くにいるのに切れていた）
    reconn_wasted_pct       つなぎ直しのうち，つなぎ直した後の接続が無駄だったもの
    reconn_gap_median       切れてからつながり直すまでの時間（中央値）
    enc_n / enc_connected / enc_lead3 / enc_lead_median
        すれ違った2台（距離が初めて 30m 未満になった2台。交差点かどうかに関係なく位置だけで決める）について，
        その時点でつながっていた割合，3秒以上前からつながっていた割合，つながってからの時間（中央値）。
        _fast は近づく速さが 10 m/s 以上の2台だけ
    hz_followers / hz_coverage / hz_gap_median
        急停止した車に後ろから近づいた車のうち，急停止した車とつながっていた割合と，つながった時点の距離（中央値）
"""
import argparse
import csv
import itertools
import math
import os

import common
import hazard_eval
from run_scenario import RouteIndex

RELEVANT_HORIZON = 10.0     # この秒数以内に同じ交差点に入る2台を「関係がある」とする
FOLLOW_M = 100.0            # 前後の車を「関係がある」とする距離
MEET_WINDOW = 5.0           # 同じ交差点をこの秒数以内に続けて通った2台を「出会った」とする
CLOSE_M = 30.0              # 2台の距離がこれ未満になったら「すれ違った」とする
FAST_CLOSING = 10.0         # 近づく速さ（2台の速度の差の大きさ）[m/s] がこれ以上を「速い接近」とする
FAR_M = 50.0                # 接続の間に一度もこの距離まで近づかなかった接続を「近づかないまま終わった接続」とする
SHORT_SEC = 10.0            # この秒数以下で切れた接続を「短い接続」とする
LEAD_OK = 3.0               # 出会う何秒前からつながっていれば「事前につながっていた」とするか


def pair(a, b):
    return (a, b) if a < b else (b, a)


class Scheme:
    """方式ごとの接続状態と集計."""

    def __init__(self, name):
        self.name = name
        self.peers = {}             # 車 -> つながっている相手の集合（その車が位置を送る相手）
        self.since = {}             # (a,b) -> つながった時刻
        self.durations = []
        self.setups = 0
        self.ctrl = 0
        self.peer_counts = []       # 毎秒・1台ごとの相手の数
        self.tp = self.conn_n = self.rel_n = 0
        self.noshare = 0
        self.pair_times = {}        # (a,b) -> その2台がつながった回数（つなぎ直しの数え上げ）
        self.cur = {}               # (a,b) -> 今の接続の記録 {k: 何回目, mind: 最も近づいた距離, noshare, gap, gap_mind}
        self.gap = {}               # (a,b) -> [前の接続が切れた時刻, 切れている間に最も近づいた距離]
        self.ended = []             # 終わった接続の記録（上に dur: 続いた秒数 を足したもの）
        self.enc = []               # すれ違い [(近づく速さ, 何秒前からつながっていたか or None)]

    def connected(self):
        s = set()
        for v, ps in self.peers.items():
            for p in ps:
                s.add(pair(v, p))
        return s

    def account(self, now, alive, relevant, xy, nodes):
        cur = self.connected()

        def dist(p):
            a, b = p
            if a in xy and b in xy:
                return math.hypot(xy[a][0] - xy[b][0], xy[a][1] - xy[b][1])
            return None

        # 切れている間（前の接続が終わってから）の最も近づいた距離
        for p in list(self.gap):
            if p in cur:
                continue
            d = dist(p)
            if d is None:
                del self.gap[p]            # どちらかが走り終えた
            elif d < self.gap[p][1]:
                self.gap[p][1] = d
        for p in cur:
            if p not in self.since:
                self.since[p] = now
                self.setups += 1
                k = self.pair_times[p] = self.pair_times.get(p, 0) + 1
                noshare = p[0] in nodes and p[1] in nodes and not (nodes[p[0]] & nodes[p[1]])
                if noshare:
                    self.noshare += 1       # ルートが1か所も交わらない相手との接続
                g = self.gap.pop(p, None)
                self.cur[p] = {"k": k, "mind": 1e9, "noshare": noshare,
                               "gap": None if g is None else now - g[0],
                               "gap_mind": None if g is None else g[1]}
            d = dist(p)
            if d is not None and d < self.cur[p]["mind"]:
                self.cur[p]["mind"] = d
        for p in [p for p in self.since if p not in cur]:
            dur = now - self.since.pop(p)
            self.durations.append(dur)
            c = self.cur.pop(p)
            c["dur"] = dur
            self.ended.append(c)
            self.gap[p] = [now, 1e9]
        for v in alive:
            self.peer_counts.append(len(self.peers.get(v, ())))
        self.conn_n += len(cur)
        self.rel_n += len(relevant)
        self.tp += len(cur & relevant)


class EtaScheme(Scheme):
    """本システム: エッジサーバ交差点への ETA < τ で参加，通過して δ 離れ遠ざかったら離脱."""

    def __init__(self, tau, delta, junction_xy, routes, join_dist=0.0):
        super().__init__(f"eta_t{int(tau)}_j{int(join_dist)}_d{int(delta)}")
        self.tau, self.delta, self.j, self.routes = tau, delta, junction_xy, routes
        self.join_dist = join_dist      # 参加円の半径 ρ: 交差点までの直線距離がこれ未満なら，ETA に関係なく参加する（0 = なし）
        self.members = {iid: set() for iid in junction_xy}
        self.prev = {}
        self.asked = set()

    def update(self, t, now, alive, xy, speed):
        for v in alive:
            if v not in self.asked:           # ルートが決まったら MasterServer に問い合わせる（往復2通）
                self.asked.add(v)
                self.ctrl += 2
        for iid, (jx, jy) in self.j.items():
            mem = self.members[iid]
            for v in [m for m in mem if m not in alive]:
                mem.discard(v)
                self.ctrl += 1 + len(mem)      # LEAVE + 残りのメンバーへの離脱通知
            for v in alive:
                if v not in mem and iid not in self.routes.targets(v):
                    continue                   # この交差点を通らない車
                eu = math.hypot(xy[v][0] - jx, xy[v][1] - jy)
                prev = self.prev.get((iid, v))
                self.prev[(iid, v)] = eu
                if v in mem:
                    if eu >= self.delta and prev is not None and eu > prev:
                        mem.discard(v)
                        self.ctrl += 1 + len(mem)
                    continue
                d = self.routes.distance(v, iid)
                if d is None:
                    continue
                if d / max(speed[v], 1.0) < self.tau or eu < self.join_dist:
                    self.ctrl += 2 + len(mem)  # JOIN + メンバー一覧の返信 + 既存メンバーへの参加通知
                    mem.add(v)
        self.peers = {}
        for mem in self.members.values():
            for v in mem:
                self.peers.setdefault(v, set()).update(m for m in mem if m != v)


class DistScheme(Scheme):
    """従来G-LocON: 各車が period 秒ごとにサーバへ問い合わせ，半径 R 以内の車を相手にする."""

    def __init__(self, radius, period):
        super().__init__(f"dist_r{int(radius)}")
        self.r, self.period = radius, period
        self.born = {}

    def update(self, t, now, alive, xy, speed):
        for v in list(self.peers):
            if v not in alive:
                del self.peers[v]
        cell = self.r
        grid = {}
        for v in alive:
            grid.setdefault((int(xy[v][0] // cell), int(xy[v][1] // cell)), []).append(v)
        for v in alive:
            if v not in self.born:
                self.born[v] = now
            if (now - self.born[v]) % self.period > 1e-6:
                # 問い合わせの間は前回の一覧のまま（いなくなった車だけ外す）
                self.peers[v] = {p for p in self.peers.get(v, ()) if p in alive}
                continue
            cx, cy = int(xy[v][0] // cell), int(xy[v][1] // cell)
            new = set()
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    for p in grid.get((cx + dx, cy + dy), ()):
                        if p != v and math.hypot(xy[p][0] - xy[v][0], xy[p][1] - xy[v][1]) <= self.r:
                            new.add(p)
            old = self.peers.get(v, set())
            # 問い合わせ（往復2通）+ 新しい相手1台につき，サーバ経由の接続依頼とその相手からの登録（2通）
            self.ctrl += 2 + 2 * len(new - old)
            self.peers[v] = new


def make_trips(out, period, end, min_distance, seed):
    """交通流をその場で作る（make_scenario.py と同じ作り方）."""
    import re
    import subprocess
    import sys
    trips = os.path.join(out, "trips.rou.xml")
    tools = os.path.join(common.sumo_home(), "tools")
    vtypes = os.path.join(common.SCENARIO_DIR, "vtypes.add.xml")
    subprocess.run([sys.executable, os.path.join(tools, "randomTrips.py"),
                    "-n", common.NET_FILE, "-o", os.path.join(out, "trips.trips.xml"),
                    "-r", trips, "-e", str(end), "-p", str(period), "--seed", str(seed),
                    "--fringe-factor", "3", "--min-distance", str(min_distance), "--validate",
                    "--edge-permission", "passenger",
                    "--trip-attributes", 'type="car" departLane="best" departSpeed="max"',
                    "--prefix", "v", "--additional-file", vtypes],
                   check=True, stdout=subprocess.DEVNULL)
    with open(trips, encoding="utf-8") as f:
        txt = f.read()
    txt = re.sub(r"<vType\b[^>]*?(/>|>.*?</vType>)\s*", "", txt, flags=re.S)
    with open(trips, "w", encoding="utf-8") as f:
        f.write(txt)
    return trips


def read_routes(trips):
    import xml.etree.ElementTree as ET
    return [(v.get("id"), float(v.get("depart")), v.find("route").get("edges").split())
            for v in ET.parse(trips).getroot().iter("vehicle") if v.find("route") is not None]


def choose_edge_servers(net, veh_routes, count, spacing):
    """交通量の多い交差点から順に count か所選ぶ（select_edge_servers.py --by traffic と同じ）. {junctionId: junctionId}"""
    import random
    with open(common.INTERSECTIONS_CSV, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    random.Random(1).shuffle(rows)
    cnt = {}
    for _, _, edges in veh_routes:
        for e in edges[:-1]:
            j = net.getEdge(e).getToNode().getID()
            cnt[j] = cnt.get(j, 0) + 1
    rows.sort(key=lambda r: -cnt.get(r["junctionId"], 0))

    def dist(p, q):
        dy = (float(p["lat"]) - float(q["lat"])) * 111_320
        dx = (float(p["lon"]) - float(q["lon"])) * 111_320 * math.cos(math.radians(float(p["lat"])))
        return math.hypot(dx, dy)

    chosen = []
    for r in rows:
        if all(dist(r, c) >= spacing for c in chosen):
            chosen.append(r)
        if len(chosen) == count:
            break
    return {r["junctionId"]: r["junctionId"] for r in chosen}


def hazard_candidates(net, veh_routes, jids, seed):
    """エッジサーバ交差点を通る車すべてを急停止の候補にする（make_scenario.py と同じ）."""
    import random
    es = set(jids.values())
    rng = random.Random(seed + 1000)
    out = []
    for vid, depart, edges in veh_routes:
        if depart < 60:
            continue
        for e in edges[1:]:
            j = net.getEdge(e).getToNode().getID()
            if j in es:
                out.append({"vehicle": vid, "intersectionId": j, "trigger_m": rng.choice([40, 60, 80, 100]),
                            "stop_sec": 12, "decel": 8.0})
                break
    return out


def upcoming(t, v, route, to_node, horizon):
    """この先 horizon 秒以内に入る交差点（junctionId）."""
    idx = max(t.vehicle.getRouteIndex(v), 0)
    road = t.vehicle.getRoadID(v)
    sp = max(t.vehicle.getSpeed(v), 1.0)
    node, ln = to_node[route[idx]]
    d = 0.0 if road.startswith(":") else max(ln - t.vehicle.getLanePosition(v), 0.0)
    out = []
    k = idx
    while d / sp < horizon:
        out.append(node)
        k += 1
        if k >= len(route):
            break
        node, ln = to_node[route[k]]
        d += ln
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--tag", default="")
    ap.add_argument("--taus", default="15,30,45", help="本システムの τ [秒]（カンマ区切り）")
    ap.add_argument("--deltas", default="100", help="本システムの δ [m]（カンマ区切り）")
    ap.add_argument("--join-dists", default="100",
                    help="本システムの参加円の半径 ρ [m]（0=なし。カンマ区切り。既定は100）")
    ap.add_argument("--etas", default="",
                    help="本システムの条件を τ:ρ:δ の組で指定（例 15:100:60,30:100:60）。指定すると --taus などは使わない")
    ap.add_argument("--radii", default="100,150,200,300",
                    help="従来G-LocONの検索半径 [m]（カンマ区切り。元のアプリ G-LocON_2024 の既定は100）")
    ap.add_argument("--search-period", type=float, default=2.0,
                    help="従来G-LocONの問い合わせ間隔 [秒]。元のアプリ（G-LocON_2024）は位置の更新2回ごと（約2秒）")
    ap.add_argument("--no-hazards", action="store_true", help="急停止を起こさない")
    ap.add_argument("--period", type=float, default=None, help="車両の発生間隔 [秒]（指定すると交通流をその場で作る）")
    ap.add_argument("--trip-seed", type=int, default=1, help="交通流（出発地・目的地）の乱数")
    ap.add_argument("--es-count", type=int, default=None, help="エッジサーバの数（指定すると交通量の多い順にその場で選ぶ）")
    ap.add_argument("--es-spacing", type=float, default=200.0, help="エッジサーバどうしの最小距離 [m]（--es-count のとき）")
    a = ap.parse_args()

    common.sumo_home()
    import traci
    out = os.path.join(common.OUT_DIR, "compare_" + (a.tag or f"s{a.seed}"))
    os.makedirs(out, exist_ok=True)
    cfg = os.path.join(common.SCENARIO_DIR, "scenario.sumocfg")
    net = common.load_net()
    cmd = [common.sumo_bin("sumo"), "-c", cfg, "--seed", str(a.seed), "--no-step-log", "true", "--no-warnings", "true"]
    trips = os.path.join(common.SCENARIO_DIR, "trips.rou.xml")
    if a.period is not None:
        trips = make_trips(out, a.period, 900, 800, a.trip_seed)
        cmd += ["--route-files", trips]
    veh_routes = read_routes(trips)
    if a.es_count is not None:
        jids = choose_edge_servers(net, veh_routes, a.es_count, a.es_spacing)
    else:
        jids = common.read_sim_edge_servers()
    custom = a.period is not None or a.es_count is not None
    traci.start(cmd)
    es_nodes = set(jids.values())
    junction_xy = {iid: traci.junction.getPosition(j) for iid, j in jids.items()}
    routes = RouteIndex(traci, net, jids)
    to_node = routes.to_node
    with open(common.INTERSECTIONS_CSV, encoding="utf-8") as f:
        all_nodes = {r["junctionId"] for r in csv.DictReader(f)}

    if a.etas:
        # τ:ρ:δ の組をそのまま指定（1つずつ変える比較用）
        schemes = [EtaScheme(float(t), float(d), junction_xy, routes, float(j))
                   for t, j, d in (x.split(":") for x in a.etas.split(","))]
    else:
        schemes = [EtaScheme(float(tau), float(dl), junction_xy, routes, float(jd))
                   for tau in a.taus.split(",") for dl in a.deltas.split(",") for jd in a.join_dists.split(",")]
    schemes += [DistScheme(float(r), a.search_period) for r in a.radii.split(",")]

    if a.no_hazards:
        hazards = []
    elif custom:
        hazards = hazard_candidates(net, veh_routes, jids, a.trip_seed)
    else:
        hazards = hazard_eval.load_hazards("follower")
    active, last_stop = [], {}
    foll = {}           # (急停止した車, 開始時刻, 後続車) -> {方式: つながった時点の距離}
    passes = {}         # junction -> [(時刻, 車)]
    last_idx = {}
    conn_log = {s.name: {} for s in schemes}     # 方式 -> pair -> [[開始, 終了]]
    veh_route = {}
    veh_nodes = {}      # 車 -> ルートが通る交差点・曲がり角（node）の集合

    step_len = traci.simulation.getDeltaT()
    end_t = traci.simulation.getEndTime()
    veh_seconds = 0
    seen_close = set()
    while traci.simulation.getMinExpectedNumber() > 0 and traci.simulation.getTime() < end_t:
        traci.simulationStep()
        now = traci.simulation.getTime()
        alive = set(traci.vehicle.getIDList())

        # 急停止（run_scenario.py --mode none --hazard-rule follower と同じ）
        for h in hazards:
            if h.get("done"):
                continue
            v = h["vehicle"]
            if v not in alive:
                if h.get("seen"):
                    h["done"] = True
                continue
            h["seen"] = True
            d = routes.distance(v, h["intersectionId"])
            if d is None:
                h["done"] = True
                continue
            spd = traci.vehicle.getSpeed(v)
            if d > h["trigger_m"] or spd < 3.0:
                continue
            if (not hazard_eval.may_stop(last_stop, h["intersectionId"], now)
                    or not hazard_eval.follower_exists(traci, v, alive)):
                continue
            hazard_eval.mark_stop(last_stop, h["intersectionId"], now)
            h["done"] = True
            traci.vehicle.setDecel(v, h["decel"])
            traci.vehicle.slowDown(v, 0.0, max(spd / h["decel"], 0.5))
            active.append({"veh": v, "t0": now, "until": now + h["stop_sec"] + spd / h["decel"]})
        for s in list(active):
            if s["veh"] not in alive:
                active.remove(s)
            elif now >= s["until"]:
                traci.vehicle.setSpeed(s["veh"], -1)
                traci.vehicle.setDecel(s["veh"], 3.0)
                active.remove(s)
            else:
                traci.vehicle.setSpeed(s["veh"], 0.0)

        # 交差点の通過（道路が次へ進んだ時点）
        for v in alive:
            if v not in veh_route:
                veh_route[v] = traci.vehicle.getRoute(v)
                veh_nodes[v] = {to_node[e][0] for e in veh_route[v]} | {net.getEdge(veh_route[v][0]).getFromNode().getID()}
            idx = traci.vehicle.getRouteIndex(v)
            li = last_idx.get(v)
            if li is not None and idx > li:
                for k in range(li, idx):
                    node = to_node[veh_route[v][k]][0]
                    if node in all_nodes:
                        passes.setdefault(node, []).append((now, v))
            last_idx[v] = idx

        if abs(now - round(now)) > 1e-6:
            continue                           # 接続の更新・集計は1秒ごと（アプリの位置更新と同じ）
        veh_seconds += len(alive)
        xy = {v: common.vehicle_xy(traci, v) for v in alive}
        speed = {v: traci.vehicle.getSpeed(v) for v in alive}
        vel = {}
        for v in alive:
            ang = math.radians(traci.vehicle.getAngle(v))
            vel[v] = (speed[v] * math.sin(ang), speed[v] * math.cos(ang))

        # 関係のある2台: この先 H 秒以内に同じ交差点に入る，または 100m 以内の前後
        at = {}
        for v in alive:
            for node in upcoming(traci, v, veh_route[v], to_node, RELEVANT_HORIZON):
                if node in all_nodes:
                    at.setdefault(node, []).append(v)
        relevant = set()
        for vs in at.values():
            for x, y in itertools.combinations(vs, 2):
                relevant.add(pair(x, y))
        for v in alive:
            ld = traci.vehicle.getLeader(v, FOLLOW_M)
            if ld and ld[0] and ld[1] <= FOLLOW_M:
                relevant.add(pair(v, ld[0]))

        # すれ違い: 2台の距離が初めて CLOSE_M 未満になった時点（交差点かどうかに関係なく，位置だけで決める）
        new_close = []
        cgrid = {}
        for v in alive:
            cgrid.setdefault((int(xy[v][0] // CLOSE_M), int(xy[v][1] // CLOSE_M)), []).append(v)
        for (cx, cy), vs in cgrid.items():
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    for q in cgrid.get((cx + dx, cy + dy), ()):
                        for v in vs:
                            if v < q and math.hypot(xy[v][0] - xy[q][0], xy[v][1] - xy[q][1]) < CLOSE_M:
                                p = (v, q)
                                if p not in seen_close:
                                    seen_close.add(p)
                                    va, vb = vel[v], vel[q]
                                    new_close.append((p, math.hypot(va[0] - vb[0], va[1] - vb[1])))

        # 急停止した車に後ろから近づいた車
        behind = []
        for s in active:
            hv = s["veh"]
            e, p = traci.vehicle.getRoadID(hv), traci.vehicle.getLanePosition(hv)
            if e.startswith(":"):
                continue
            for m in alive:
                if m == hv:
                    continue
                d = hazard_eval.gap_behind(traci, m, e, p)
                if d is None:
                    continue
                key = (hv, s["t0"], m)
                if key not in foll:
                    if s["until"] - now < hazard_eval.MIN_TIME_LEFT:
                        continue
                    foll[key] = {}
                behind.append((key, pair(hv, m), d))

        for sc in schemes:
            sc.update(traci, now, alive, xy, speed)
            sc.account(now, alive, relevant, xy, veh_nodes)
            cur = sc.connected()
            for p, closing in new_close:
                sc.enc.append((closing, now - sc.since[p] if p in sc.since else None))
            log = conn_log[sc.name]
            for p in cur:
                iv = log.setdefault(p, [])
                if iv and iv[-1][1] >= now - 1.0 - 1e-6:
                    iv[-1][1] = now
                else:
                    iv.append([now, now])
            for key, p, d in behind:
                if p in cur and sc.name not in foll[key]:
                    foll[key][sc.name] = d
    traci.close()

    # 交差点で出会った2台（同じ交差点を MEET_WINDOW 秒以内に続けて通った，別々の車）
    meets = []      # (交差点, 先に着いた時刻, a, b)
    for node, ps in passes.items():
        ps.sort()
        for i, (t1, v1) in enumerate(ps):
            for t2, v2 in ps[i + 1:]:
                if t2 - t1 > MEET_WINDOW:
                    break
                if v1 != v2:
                    meets.append((node, t1, v1, v2))

    rows = []
    for sc in schemes:
        log = conn_log[sc.name]
        pc = sorted(sc.peer_counts)
        dur = sorted(sc.durations + [end_t - t0 for t0 in sc.since.values()])
        res = {"scheme": sc.name, "period": a.period if a.period is not None else 1.5, "es_count": len(jids),
               "vehicles": len(veh_routes), "concurrent": round(veh_seconds / max(end_t, 1)),
               "peers_mean": round(sum(pc) / max(len(pc), 1), 2),
               "peers_p95": pc[int(len(pc) * .95)] if pc else 0,
               "msgs_per_veh_s": round(sum(pc) / max(veh_seconds, 1), 2),
               "setups_per_veh_min": round(2 * sc.setups / max(veh_seconds, 1) * 60, 2),
               "conn_sec_median": dur[len(dur) // 2] if dur else None,
               "ctrl_per_veh_min": round(sc.ctrl / max(veh_seconds, 1) * 60, 2),
               "precision": round(100 * sc.tp / max(sc.conn_n, 1), 1),
               "recall": round(100 * sc.tp / max(sc.rel_n, 1), 1)}
        with open(os.path.join(out, f"pairs_{sc.name}.csv"), "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f, lineterminator="\n")
            w.writerow(["junction", "edge_server", "t_first", "a", "b", "connected", "lead_sec"])
            for scope, nodes in (("es", es_nodes), ("all", all_nodes)):
                n = con = ok = 0
                leads = []
                for node, t1, v1, v2 in meets:
                    if node not in nodes:
                        continue
                    n += 1
                    lead = None
                    for s0, s1 in log.get(pair(v1, v2), ()):
                        if s0 <= t1 <= s1 + 1.0:
                            lead = t1 - s0
                            break
                    if lead is not None:
                        con += 1
                        ok += lead >= LEAD_OK
                        leads.append(lead)
                    if scope == "all":
                        w.writerow([node, int(node in es_nodes), f"{t1:.1f}", v1, v2,
                                    int(lead is not None), "" if lead is None else f"{lead:.1f}"])
                leads.sort()
                res[f"met_n_{scope}"] = n
                res[f"met_connected_{scope}"] = round(100 * con / max(n, 1), 1)
                res[f"met_lead3_{scope}"] = round(100 * ok / max(n, 1), 1)
                res[f"met_lead_median_{scope}"] = leads[len(leads) // 2] if leads else None
        # 無駄な接続・つなぎ直し・短い接続
        ended = list(sc.ended)
        for p, t0 in sc.since.items():
            c = dict(sc.cur[p]); c["dur"] = end_t - t0
            ended.append(c)
        n_end = max(len(ended), 1)
        npairs = max(len(sc.pair_times), 1)

        def wasted(c):      # ルートが交わらない，または一度も FAR_M 以内に近づかなかった
            return c["noshare"] or c["mind"] > FAR_M

        res["pairs"] = len(sc.pair_times)
        res["noshare_pct"] = round(100 * sum(1 for c in ended if c["noshare"]) / n_end, 1)
        res["far_pct"] = round(100 * sum(1 for c in ended if c["mind"] > FAR_M) / n_end, 1)
        res["wasted_pct"] = round(100 * sum(1 for c in ended if wasted(c)) / n_end, 1)
        res["wasted_per_veh_min"] = round(2 * sum(1 for c in ended if wasted(c)) / max(veh_seconds, 1) * 60, 2)
        res["short_pct"] = round(100 * sum(1 for c in ended if c["dur"] <= SHORT_SEC) / n_end, 1)
        # つなぎ直し（同じ2台の2回目以降の接続）を性質で分ける
        re = [c for c in ended if c["k"] >= 2 and c["gap"] is not None]
        n_re = max(len(re), 1)
        res["reconnect_pct"] = round(100 * sum(1 for n in sc.pair_times.values() if n >= 2) / npairs, 1)
        res["reconn_per_veh_min"] = round(2 * len(re) / max(veh_seconds, 1) * 60, 2)
        res["reconn_quick_pct"] = round(100 * sum(1 for c in re if c["gap"] <= SHORT_SEC) / n_re, 1)
        res["reconn_near_pct"] = round(100 * sum(1 for c in re if c["gap_mind"] <= FAR_M) / n_re, 1)
        res["reconn_wasted_pct"] = round(100 * sum(1 for c in re if wasted(c)) / n_re, 1)
        gaps_re = sorted(c["gap"] for c in re)
        res["reconn_gap_median"] = gaps_re[len(gaps_re) // 2] if gaps_re else None
        # すれ違い（距離が CLOSE_M 未満になった2台）: その時点でつながっていたか，何秒前からか
        for tag, sel in (("", lambda c: True), ("_fast", lambda c: c >= FAST_CLOSING)):
            e = [(c, l) for c, l in sc.enc if sel(c)]
            ls = sorted(l for _, l in e if l is not None)
            res[f"enc{tag}_n"] = len(e)
            res[f"enc{tag}_connected"] = round(100 * len(ls) / max(len(e), 1), 1)
            res[f"enc{tag}_lead3"] = round(100 * sum(1 for l in ls if l >= LEAD_OK) / max(len(e), 1), 1)
            res[f"enc{tag}_lead_median"] = ls[len(ls) // 2] if ls else None
        gaps = sorted(d[sc.name] for d in foll.values() if sc.name in d)
        res["hz_followers"] = len(foll)
        res["hz_coverage"] = round(100 * len(gaps) / max(len(foll), 1), 1)
        res["hz_gap_median"] = round(gaps[len(gaps) // 2], 1) if gaps else None
        rows.append(res)

    keys = list(rows[0].keys())
    with open(os.path.join(out, "schemes.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, keys, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)
    width = max(len(k) for k in keys)
    for k in keys:
        print(f"{k:<{width}}  " + "  ".join(f"{str(r[k]):>13}" for r in rows))
    print(f"車両・秒 {veh_seconds}（同時に走っている車の平均 {veh_seconds / max(end_t, 1):.0f} 台）→ {out}")


if __name__ == "__main__":
    main()
