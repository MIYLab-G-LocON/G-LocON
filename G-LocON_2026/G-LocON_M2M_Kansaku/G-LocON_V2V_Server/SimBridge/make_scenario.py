"""段階1-b: 交通流シナリオの作成.

    python make_scenario.py [--main-vph 300] [--bg-period 4] [--end 900] [--seed 1]

作るもの（scenario/）:
    routes.rou.xml   実験ルート（アプリと同じ始点→終点）の車列＋逆方向＋周辺の背景交通
    hazards.json     急停止イベント（エッジサーバ交差点の手前で先行車が急停止）
    scenario.sumocfg SUMO設定

車両モデルは「V2Vの有無で差が出る」よう，完全な安全運転ではなく
運転のばらつき(sigma)・反応の遅れ(actionStepLength/tau)を持たせている。
"""
import argparse
import csv
import json
import os
import subprocess
import sys

import common

VTYPES = """    <vType id="car" vClass="passenger" carFollowModel="Krauss"
           accel="2.6" decel="4.5" emergencyDecel="9.0" apparentDecel="4.5"
           sigma="0.5" tau="1.0" actionStepLength="1.0" minGap="2.0"
           speedFactor="normc(1.0,0.1,0.8,1.2)" length="4.5"/>
"""


def nearest_edge(net, lat, lon, radius=80):
    x, y = net.convertLonLat2XY(lon, lat)
    cands = net.getNeighboringEdges(x, y, radius)
    cands = [(e, d) for e, d in cands if e.allows("passenger")]
    if not cands:
        sys.exit(f"({lat},{lon}) の近くに道路が見つかりません")
    return min(cands, key=lambda c: c[1])[0]


def route_edges(net, a, b):
    ea, eb = nearest_edge(net, *a), nearest_edge(net, *b)
    path, _ = net.getShortestPath(ea, eb, vClass="passenger")
    if not path:
        sys.exit("始点→終点のルートが見つかりません")
    return [e.getID() for e in path]


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
    fwd = route_edges(net, common.ROUTE_START, common.ROUTE_END)
    bwd = route_edges(net, common.ROUTE_END, common.ROUTE_START)
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

    # 急停止イベント: 各エッジサーバ交差点で，200秒以降に数回
    js = active_junctions()
    hazards = []
    t = 200
    while t < a.end - 100:
        for j in js:
            hazards.append({"time": t, "intersectionId": j["intersectionId"], "junction": j["junctionId"],
                            "upstream_m": [30, 120], "stop_sec": 12, "decel": 8.0})
            t += 60
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
