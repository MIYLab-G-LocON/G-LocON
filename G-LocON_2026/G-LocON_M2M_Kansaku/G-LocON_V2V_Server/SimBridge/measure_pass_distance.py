"""交差点に最も近づいた距離の集計（通過判定の半径 PASS_RADIUS_M を決めるための測定）.

    python measure_pass_distance.py [シード]

SUMOで全車両の位置をアプリと同じ1秒ごとに記録し，各車両のルート上の交差点について
最も近づいた距離（直線距離）を集計する。2026/09/29 の結果（シード1）:
    エッジサーバ交差点 196回: 中央値 3.6m, 99% 10.5m, 最大 11.0m（15m以内 100%）
    全交差点 8,922回:        中央値 3.5m, 99% 10.4m, 最大 18.9m（20m以内 100%）
→ 実機のGPS誤差を見込み，アプリ・仮想クライアントとも 20m とした。
"""
import csv, math, sys
import common; common.sumo_home()
import traci
net = common.load_net()
ix = {}
with open(common.INTERSECTIONS_CSV, encoding="utf-8") as f:
    for r in csv.DictReader(f):
        ix[r["junctionId"]] = (float(r["lat"]), float(r["lon"]))
es = set(common.read_sim_edge_servers().values())
def d(a, b):
    return 6378137 * math.hypot(math.radians(b[0]-a[0]), math.radians(b[1]-a[1]) * math.cos(math.radians(a[0])))
seed = int(sys.argv[1]) if len(sys.argv) > 1 else 1
traci.start([common.sumo_bin("sumo"), "-c", "scenario/scenario.sumocfg", "--seed", str(seed), "--no-step-log", "true", "--no-warnings", "true"])
targets = {}   # vid -> {jid: min_dist}
res_all, res_es = [], []
def finish(v):
    for j, m in targets.pop(v).items():
        res_all.append(m)
        if j in es: res_es.append(m)
step = 0
while traci.simulation.getMinExpectedNumber() > 0:
    traci.simulationStep(); step += 1
    for v in traci.simulation.getDepartedIDList():
        js = {net.getEdge(e).getToNode().getID() for e in traci.vehicle.getRoute(v)[1:-1]}
        targets[v] = {j: 1e9 for j in js if j in ix}
    for v in traci.simulation.getArrivedIDList():
        if v in targets: finish(v)
    if step % 2: continue          # 1秒ごと（アプリと同じ更新間隔）
    for v in traci.vehicle.getIDList():
        x, y = traci.vehicle.getPosition(v); lon, lat = net.convertXY2LonLat(x, y)
        for j in targets.get(v, {}):
            targets[v][j] = min(targets[v][j], d((lat, lon), ix[j]))
traci.close()
def pct(a, p): a = sorted(a); return a[min(len(a)-1, int(len(a)*p))]
for name, a in (("全交差点", res_all), ("エッジサーバ交差点", res_es)):
    a = [x for x in a if x < 1e8]
    print(f"{name}: 通過 {len(a)} 回, 最接近距離 中央値 {pct(a,.5):.1f} m, 90% {pct(a,.9):.1f} m, 95% {pct(a,.95):.1f} m, 99% {pct(a,.99):.1f} m, 最大 {max(a):.1f} m")
    for r in (10, 15, 20, 25, 30):
        print(f"   {r} m 以内に入った割合 {100*sum(x<=r for x in a)/len(a):.1f}%")
