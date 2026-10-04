"""段階1-c: エリア内を自由に走る交通流と，急停止イベントを作る.

    python make_scenario.py [--period 1.5] [--end 900] [--hazards 40] [--seed 1]
    python make_scenario.py --trips-only     # 交通流だけ作る（エッジサーバを交通量で選ぶ前に使う）

手順: build_net.py → make_scenario.py --trips-only → select_edge_servers.py → make_scenario.py

作るもの（scenario/）:
    vtypes.add.xml    車両モデル
    trips.rou.xml     エリア内のランダムな出発地→目的地（全車両が自由に走行）
    hazards.json      急停止イベント（エッジサーバ交差点を通る車から選んだ 40 台が，その手前で急停止）
    hazard_candidates.json  急停止の候補（エッジサーバ交差点を通る車すべて。--hazard-rule follower で使う）
    scenario.sumocfg  SUMO設定

車両モデルは IDM（快適な減速度 3.0 m/s^2 を超える減速は危険時のみ起きる）とし，
反応の遅れ(actionStepLength=1.0秒)と速度のばらつき(speedFactor)を持たせている。
"""
import argparse
import json
import os
import random
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

import common

VTYPES = """    <vType id="car" vClass="passenger" carFollowModel="IDM"
           accel="2.6" decel="3.0" emergencyDecel="9.0" apparentDecel="3.0"
           tau="1.0" actionStepLength="1.0" minGap="2.0" delta="4"
           speedFactor="normc(1.0,0.1,0.8,1.2)" length="4.5"/>
"""


def write_cfg(a):
    with open(os.path.join(common.SCENARIO_DIR, "scenario.sumocfg"), "w", encoding="utf-8") as f:
        f.write(f"""<configuration>
    <input>
        <net-file value="area.net.xml"/>
        <route-files value="trips.rou.xml"/>
        <additional-files value="vtypes.add.xml"/>
    </input>
    <time><begin value="0"/><end value="{a.end + 300}"/><step-length value="0.5"/></time>
    <processing><time-to-teleport value="120"/><step-method.ballistic value="true"/></processing>
    <random_number><seed value="{a.seed}"/></random_number>
</configuration>
""")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--period", type=float, default=1.5, help="車両の発生間隔 [秒]（小さいほど交通量が多い）")
    ap.add_argument("--end", type=int, default=900, help="車両を発生させる時間 [秒]")
    ap.add_argument("--min-distance", type=float, default=800, help="出発地と目的地の最小距離 [m]")
    ap.add_argument("--trips-only", action="store_true", help="交通流だけ作り，急停止イベントは作らない")
    ap.add_argument("--hazards", type=int, default=40, help="急停止イベントの数")
    ap.add_argument("--seed", type=int, default=1)
    a = ap.parse_args()

    os.makedirs(common.SCENARIO_DIR, exist_ok=True)
    vtypes = os.path.join(common.SCENARIO_DIR, "vtypes.add.xml")
    with open(vtypes, "w", encoding="utf-8") as f:
        f.write("<additional>\n" + VTYPES + "</additional>\n")

    # エリア内のランダムな出発地→目的地（ルートは最短経路。車ごとに異なる）
    trips = os.path.join(common.SCENARIO_DIR, "trips.rou.xml")
    tools = os.path.join(common.sumo_home(), "tools")
    subprocess.run([sys.executable, os.path.join(tools, "randomTrips.py"),
                    "-n", common.NET_FILE, "-o", os.path.join(common.SCENARIO_DIR, "trips.trips.xml"),
                    "-r", trips, "-e", str(a.end), "-p", str(a.period), "--seed", str(a.seed),
                    "--fringe-factor", "3", "--min-distance", str(a.min_distance), "--validate",
                    "--edge-permission", "passenger",
                    "--trip-attributes", 'type="car" departLane="best" departSpeed="max"',
                    "--prefix", "v", "--additional-file", vtypes],
                   check=True, stdout=subprocess.DEVNULL)
    with open(trips, encoding="utf-8") as f:
        txt = f.read()
    txt = re.sub(r"<vType\b[^>]*?(/>|>.*?</vType>)\s*", "", txt, flags=re.S)   # vtypes.add.xml に一元化
    with open(trips, "w", encoding="utf-8") as f:
        f.write(txt)

    write_cfg(a)
    if a.trips_only:
        n = sum(1 for _ in ET.parse(trips).getroot().iter("vehicle"))
        print(f"車両 {n} 台（交通流のみ作成）")
        return

    # 急停止イベント: エッジサーバ交差点を通る車からランダムに選び，その交差点の手前で止める。
    # 車両IDと場所を固定するので，V2Vなし／ありで全く同じ急停止が起きる（対応のある比較）
    net = common.load_net()
    es = common.read_sim_edge_servers()               # {iid: junctionId}
    j2i = {j: i for i, j in es.items()}
    cands = []
    for veh in ET.parse(trips).getroot().iter("vehicle"):
        route = veh.find("route")
        if route is None or float(veh.get("depart")) < 60:
            continue
        for e in route.get("edges").split()[1:]:      # 出発直後の交差点は除く
            j = net.getEdge(e).getToNode().getID()
            if j in j2i:
                cands.append((veh.get("id"), j2i[j], j))
                break
    rng = random.Random(a.seed)
    picked = rng.sample(cands, min(a.hazards, len(cands)))
    hazards = [{"vehicle": v, "intersectionId": iid, "junction": j,
                "trigger_m": rng.choice([40, 60, 80, 100]), "stop_sec": 12, "decel": 8.0}
               for v, iid, j in sorted(picked, key=lambda t: int(t[0][1:]))]
    with open(os.path.join(common.SCENARIO_DIR, "hazards.json"), "w", encoding="utf-8") as f:
        json.dump(hazards, f, ensure_ascii=False, indent=1)
    # --hazard-rule follower 用: エッジサーバ交差点を通る車すべてを候補にし，実行時に後続車がいるときだけ急停止させる
    rng2 = random.Random(a.seed + 1000)
    cand = [{"vehicle": v, "intersectionId": iid, "junction": j,
             "trigger_m": rng2.choice([40, 60, 80, 100]), "stop_sec": 12, "decel": 8.0}
            for v, iid, j in sorted(cands, key=lambda t: int(t[0][1:]))]
    with open(os.path.join(common.SCENARIO_DIR, "hazard_candidates.json"), "w", encoding="utf-8") as f:
        json.dump(cand, f, ensure_ascii=False, indent=1)

    n = sum(1 for _ in ET.parse(trips).getroot().iter("vehicle"))
    print(f"車両 {n} 台（エリア内を自由に走行）, エッジサーバを通る車 {len(cands)} 台, 急停止イベント {len(hazards)} 件")


if __name__ == "__main__":
    main()
