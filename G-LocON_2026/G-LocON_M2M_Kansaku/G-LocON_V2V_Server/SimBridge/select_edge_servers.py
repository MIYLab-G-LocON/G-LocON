"""段階1-b: エリア内の交差点からエッジサーバを置く交差点を選ぶ.

    python select_edge_servers.py [--count 20] [--by traffic] [--min-spacing 100]
    python select_edge_servers.py --by random --seed 1      # ランダムに選ぶ

--by traffic（既定）: 交通量の多い交差点から順に選ぶ（scenario/trips.rou.xml で，その交差点を通る車の数を数える。
                      先に make_scenario.py --trips-only を実行しておく）。
                      ランダムに選ぶと車の通らない交差点にも置かれ，1ルートで1回もグループに入らない車が多くなるため。
--by random         : ランダムに選ぶ

2026/10/04: 最小距離の既定を 200m → 100m にした。交通量の多い交差点は幹線道路沿いに固まっており，
200m 離す条件だとその多くを飛ばしてしまう（実験記録 E6）。どこに置くのが良いかは別の研究課題とし，
現段階では方式の挙動を確かめやすい配置（交通量の多い交差点に詰めて置く）を使う。

--move 元=先 : ランダムに選んだ交差点を別の交差点に置き換える（数・ポート・色はそのまま）

出力 scenario/edge_servers.csv（MasterServer の edge_servers.csv と同じ形式＋SUMOの交差点ID）:
    intersectionId,ip,port,junctionId
MasterServer に読ませる場合は，4列目は無視される（先頭3列のみ使用）。
"""
import argparse
import csv
import math
import random

import common


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--count", type=int, default=20, help="エッジサーバの数")
    ap.add_argument("--by", choices=["traffic", "random"], default="traffic", help="選び方")
    ap.add_argument("--seed", type=int, default=1, help="選び方の乱数シード")
    ap.add_argument("--min-spacing", type=float, default=100.0,
                    help="エッジサーバどうしの最小距離 [m]（近すぎる交差点を同時に選ばない）")
    ap.add_argument("--min-degree", type=int, default=3, help="この本数以上の道がつながる交差点から選ぶ")
    ap.add_argument("--move", action="append", default=[], metavar="元ID=先ID",
                    help="選んだ交差点を別の交差点に置き換える（ポート・色はそのまま。複数指定可）")
    a = ap.parse_args()

    with open(common.INTERSECTIONS_CSV, encoding="utf-8") as f:
        rows = [r for r in csv.DictReader(f) if int(r["degree"]) >= a.min_degree]
    rng = random.Random(a.seed)
    rng.shuffle(rows)
    if a.by == "traffic":
        import os
        import xml.etree.ElementTree as ET
        trips = os.path.join(common.SCENARIO_DIR, "trips.rou.xml")
        if not os.path.exists(trips):
            raise SystemExit("trips.rou.xml がありません。先に python make_scenario.py --trips-only を実行してください")
        net = common.load_net()
        count = {}
        for veh in ET.parse(trips).getroot().iter("vehicle"):
            for e in veh.find("route").get("edges").split()[:-1]:      # 通り抜ける交差点（目的地の端は除く）
                j = net.getEdge(e).getToNode().getID()
                count[j] = count.get(j, 0) + 1
        rows.sort(key=lambda r: -count.get(r["junctionId"], 0))        # 同数はシャッフルした順
        for r in rows:
            r["traffic"] = count.get(r["junctionId"], 0)

    def dist(p, q):
        dy = (float(p["lat"]) - float(q["lat"])) * 111_320
        dx = (float(p["lon"]) - float(q["lon"])) * 111_320 * math.cos(math.radians(float(p["lat"])))
        return math.hypot(dx, dy)

    chosen = []
    for r in rows:
        if all(dist(r, c) >= a.min_spacing for c in chosen):
            chosen.append(r)
        if len(chosen) == a.count:
            break
    if len(chosen) < a.count:
        print(f"注意: 最小距離 {a.min_spacing} m の条件では {len(chosen)} か所しか選べませんでした")

    # ポート（と sumo-gui の色）はランダムに選んだ交差点のID順に割り当て，--move ではその枠の交差点だけを入れ替える
    chosen = sorted(chosen, key=lambda r: r["intersectionId"])
    by_id = {r["intersectionId"]: r for r in rows}
    moved = []
    for m in a.move:
        old, new = m.split("=")
        k = next((k for k, r in enumerate(chosen) if r["intersectionId"] == old), None)
        if k is None or new not in by_id:
            raise SystemExit(f"--move {m}: 元の交差点が選ばれていないか，先の交差点がありません")
        chosen[k] = by_id[new]
        near = min((dist(by_id[new], c) for j, c in enumerate(chosen) if j != k), default=0)
        moved.append(f"{old}→{new}")
        print(f"{old} → {new}（他のエッジサーバまで最短 {near:.0f} m）")

    with open(common.SIM_EDGE_SERVERS_CSV, "w", encoding="utf-8", newline="") as f:
        f.write(f"# エッジサーバ（{len(chosen)}か所, 選び方={a.by}, seed={a.seed}, 最小距離={a.min_spacing}m, "
                f"候補={len(rows)}交差点）" + (f" 移動: {', '.join(moved)}" if moved else "") + "\n")
        f.write("intersectionId,ip,port,junctionId\n")
        for k, r in enumerate(chosen):
            f.write(f"{r['intersectionId']},{common.EDGE_SERVER_IP},{common.EDGE_SERVER_BASE_PORT + k},{r['junctionId']}\n")
    if a.by == "traffic":
        print("通る車の数: " + ", ".join(str(r["traffic"]) for r in chosen))
    print(f"{len(chosen)} か所を選択 → {common.SIM_EDGE_SERVERS_CSV}")


if __name__ == "__main__":
    main()
