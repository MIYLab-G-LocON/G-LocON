"""段階1-b: エリア内の交差点からランダムにエッジサーバを選ぶ.

    python select_edge_servers.py [--count 10] [--seed 1] [--min-spacing 150]

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
    ap.add_argument("--count", type=int, default=10, help="エッジサーバの数")
    ap.add_argument("--seed", type=int, default=1, help="選び方の乱数シード")
    ap.add_argument("--min-spacing", type=float, default=150.0,
                    help="エッジサーバどうしの最小距離 [m]（近すぎる交差点を同時に選ばない）")
    ap.add_argument("--min-degree", type=int, default=3, help="この本数以上の道がつながる交差点から選ぶ")
    a = ap.parse_args()

    with open(common.INTERSECTIONS_CSV, encoding="utf-8") as f:
        rows = [r for r in csv.DictReader(f) if int(r["degree"]) >= a.min_degree]
    rng = random.Random(a.seed)
    rng.shuffle(rows)

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

    with open(common.SIM_EDGE_SERVERS_CSV, "w", encoding="utf-8", newline="") as f:
        f.write(f"# ランダムに選んだエッジサーバ（{len(chosen)}か所, seed={a.seed}, 最小距離={a.min_spacing}m, "
                f"候補={len(rows)}交差点）\n")
        f.write("intersectionId,ip,port,junctionId\n")
        for k, r in enumerate(sorted(chosen, key=lambda r: r["intersectionId"])):
            f.write(f"{r['intersectionId']},{common.EDGE_SERVER_IP},{common.EDGE_SERVER_BASE_PORT + k},{r['junctionId']}\n")
    print(f"{len(chosen)} か所を選択 → {common.SIM_EDGE_SERVERS_CSV}")


if __name__ == "__main__":
    main()
