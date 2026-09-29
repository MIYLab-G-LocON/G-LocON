"""段階1-b: 交通流シナリオの作成.

    python make_scenario.py [--main-vph 300] [--bg-period 4] [--end 900] [--seed 1]

作るもの（scenario/）:
    routes.rou.xml   実験ルート（アプリと同じ始点→終点）の車列＋逆方向＋周辺の背景交通
    hazards.json     急停止イベント（エッジサーバ交差点の手前で先行車が急停止）
    scenario.sumocfg SUMO設定

車両モデルは IDM（快適な減速度 3.0 m/s^2 を超える減速は危険時のみ起きる）とし，
反応の遅れ(actionStepLength=1.0秒)と速度のばらつき(speedFactor)を持たせている。
"""
import argparse
import csv
import json
import os
import subprocess
import sys

import common

VTYPES = """    <vType id="car" vClass="passenger" carFollowModel="IDM"
           accel="2.6" decel="3.0" emergencyDecel="9.0" apparentDecel="3.0"
           tau="1.0" actionStepLength="1.0" minGap="2.0" delta="4"
           speedFactor="normc(1.0,0.1,0.8,1.2)" length="4.5"/>
"""


def nearest_edge(net, lat, lon, radius=80):
    x, y = net.convertLonLat2XY(lon, lat)
    cands = net.getNeighboringEdges(x, y, radius)
    cands = [(e, d) for e, d in cands if e.allows("passenger")]
    if not cands:
        sys.exit(f"({lat},{lon}) の近くに道路が見つかりません")
    return min(cands, key=lambda c: c[1])[0]


def osrm_junctions():
    """edge_servers.csv に並んでいる OSRM 交差点（ルート順）の SUMO交差点ID."""
    jmap = {}
    with open(common.INTERSECTION_MAP, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            jmap[r["intersectionId"]] = r["junctionId"]
    seq = []
    for iid, *_ in common.read_edge_servers(include_commented=True):   # ファイル順＝OSRMのルート順
        j = jmap.get(iid)
        if j and (not seq or seq[-1] != j):
            seq.append(j)
    return seq


def route_edges(net, a, b, via):
    """a→b を，アプリ側（OSRM）と同じ交差点 via を順に通るルートにする."""
    cur, end = nearest_edge(net, *a), nearest_edge(net, *b)
    edges = [cur]
    for jid in via:
        node = net.getNode(jid)
        if cur.getToNode() == node:
            continue
        best = None
        for cand in node.getIncoming():
            if not cand.allows("passenger"):
                continue
            path, cost = net.getShortestPath(cur, cand, vClass="passenger")
            if path and (best is None or cost < best[1]):
                best = (path, cost)
        if best is None:
            print(f"  注意: 交差点 {jid} へ到達できないため飛ばします")
            continue
        edges += best[0][1:]
        cur = edges[-1]
    path, _ = net.getShortestPath(cur, end, vClass="passenger")
    if not path:
        sys.exit("終点へのルートが見つかりません")
    edges += path[1:]
    return [e.getID() for e in edges]


def active_junctions():
    rows = []
    with open(common.INTERSECTION_MAP, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if r["active"] == "1":
                rows.append(r)
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--main-vph", type=float, default=300, help="実験ルートの交通量 [台/時]")
    ap.add_argument("--bg-period", type=float, default=4.0, help="背景交通の発生間隔 [秒]（小さいほど多い）")
    ap.add_argument("--end", type=int, default=900, help="車両を発生させる時間 [秒]")
    ap.add_argument("--seed", type=int, default=1)
    a = ap.parse_args()

    net = common.load_net()
    via = osrm_junctions()
    fwd = route_edges(net, common.ROUTE_START, common.ROUTE_END, via)
    bwd = route_edges(net, common.ROUTE_END, common.ROUTE_START, list(reversed(via)))
    print(f"実験ルート: {len(fwd)} エッジ, 逆方向: {len(bwd)} エッジ")

    vtypes = os.path.join(common.SCENARIO_DIR, "vtypes.add.xml")
    with open(vtypes, "w", encoding="utf-8") as f:
        f.write("<additional>\n" + VTYPES + "</additional>\n")

    # 背景交通（ランダムトリップ）
    bg = os.path.join(common.SCENARIO_DIR, "background.rou.xml")
    tools = os.path.join(common.sumo_home(), "tools")
    subprocess.run([sys.executable, os.path.join(tools, "randomTrips.py"),
                    "-n", common.NET_FILE, "-o", os.path.join(common.SCENARIO_DIR, "background.trips.xml"),
                    "-r", bg, "-e", str(a.end), "-p", str(a.bg_period), "--seed", str(a.seed),
                    "--fringe-factor", "5", "--min-distance", "300", "--validate",
                    "--edge-permission", "passenger", "--trip-attributes", 'type="car" departLane="best" departSpeed="max"',
                    "--prefix", "bg", "--additional-file", vtypes],
                   check=True, stdout=subprocess.DEVNULL)

    # duarouter が vType を書き出すため除去（vtypes.add.xml で一元定義）
    import re
    with open(bg, encoding="utf-8") as f:
        txt = f.read()
    txt = re.sub(r"<vType\b[^>]*?(/>|>.*?</vType>)\s*", "", txt, flags=re.S)
    with open(bg, "w", encoding="utf-8") as f:
        f.write(txt)

    main = os.path.join(common.SCENARIO_DIR, "main.rou.xml")
    with open(main, "w", encoding="utf-8") as f:
        f.write("<routes>\n")
        f.write(f'    <route id="main_fwd" edges="{" ".join(fwd)}"/>\n')
        f.write(f'    <route id="main_bwd" edges="{" ".join(bwd)}"/>\n')
        f.write(f'    <flow id="fwd" type="car" route="main_fwd" begin="0" end="{a.end}" '
                f'vehsPerHour="{a.main_vph}" departLane="best" departSpeed="max"/>\n')
        f.write(f'    <flow id="bwd" type="car" route="main_bwd" begin="0" end="{a.end}" '
                f'vehsPerHour="{a.main_vph / 2}" departLane="best" departSpeed="max"/>\n')
        f.write("</routes>\n")

    # 急停止イベント: 実験ルートの特定の車両が，エッジサーバ交差点の手前 trigger_m で急停止する。
    # 車両IDと場所を固定するため，V2Vなし／ありで全く同じ急停止が起きる（対応のある比較）
    js = active_junctions()
    n_fwd = int(a.main_vph * a.end / 3600)
    n_bwd = int(a.main_vph / 2 * a.end / 3600)
    hazards = []
    for k, n in enumerate(range(4, n_fwd - 5, 3)):
        j = js[k % len(js)]
        hazards.append({"vehicle": f"fwd.{n}", "intersectionId": j["intersectionId"], "junction": j["junctionId"],
                        "trigger_m": 40 + 20 * (k % 4), "stop_sec": 12, "decel": 8.0})
    for k, n in enumerate(range(3, n_bwd - 3, 3)):
        j = js[k % len(js)]
        hazards.append({"vehicle": f"bwd.{n}", "intersectionId": j["intersectionId"], "junction": j["junctionId"],
                        "trigger_m": 40 + 20 * (k % 4), "stop_sec": 12, "decel": 8.0})
    with open(os.path.join(common.SCENARIO_DIR, "hazards.json"), "w", encoding="utf-8") as f:
        json.dump(hazards, f, ensure_ascii=False, indent=1)

    with open(os.path.join(common.SCENARIO_DIR, "scenario.sumocfg"), "w", encoding="utf-8") as f:
        f.write(f"""<configuration>
    <input>
        <net-file value="area.net.xml"/>
        <route-files value="main.rou.xml,background.rou.xml"/>
        <additional-files value="vtypes.add.xml"/>
    </input>
    <time><begin value="0"/><end value="{a.end + 300}"/><step-length value="0.5"/></time>
    <processing><time-to-teleport value="120"/><step-method.ballistic value="true"/></processing>
    <random_number><seed value="{a.seed}"/></random_number>
</configuration>
""")
    print(f"急停止イベント {len(hazards)} 件, 交差点 {[j['intersectionId'] for j in js]}")
    print("scenario/ に routes と scenario.sumocfg を作成しました")


if __name__ == "__main__":
    main()
