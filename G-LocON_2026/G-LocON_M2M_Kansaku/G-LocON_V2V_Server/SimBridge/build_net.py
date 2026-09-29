"""段階1-a: OpenStreetMap → SUMO道路網の変換と，交差点IDの対応付け.

使い方:
    python build_net.py            # osm/area.osm → scenario/area.net.xml, intersection_map.csv

出力する intersection_map.csv:
    intersectionId, lat, lon, active, port, junctionId, distance_m
    active=1 はエッジサーバが設置された交差点（edge_servers.csv の有効行）
"""
import csv
import os
import subprocess
import sys

import common


def convert():
    if not os.path.exists(common.OSM_FILE):
        sys.exit(f"{common.OSM_FILE} がありません（OpenStreetMapのデータを置いてください）")
    os.makedirs(common.SCENARIO_DIR, exist_ok=True)
    typemap = os.path.join(common.sumo_home(), "data", "typemap", "osmNetconvert.typ.xml")
    cmd = [
        common.sumo_bin("netconvert"),
        "--osm-files", common.OSM_FILE,
        "--type-files", typemap + "," + os.path.join(common.HERE, "osm", "service_passenger.typ.xml"),
        "-o", common.NET_FILE,
        # 車が走る道路だけを残す
        "--keep-edges.by-vclass", "passenger",
        "--remove-edges.isolated",
        # 交差点・信号・ランプの整形
        "--geometry.remove", "--roundabouts.guess", "--ramps.guess",
        "--junctions.join", "--tls.guess-signals", "--tls.discard-simple", "--tls.join",
        "--osm.turn-lanes",
        # 日本は左側通行
        "--lefthand",
        "--output.street-names", "--output.original-names",
        "--proj.utm",
        "--no-warnings",
    ]
    print(" ".join(cmd))
    subprocess.run(cmd, check=True)


def map_intersections():
    net = common.load_net()
    rows = []
    for iid, lat, lon, port, active in common.read_edge_servers(include_commented=True):
        x, y = net.convertLonLat2XY(lon, lat)
        best, best_d = None, 1e9
        for node in net.getNodes():
            if node.getType() in ("dead_end",):
                continue
            nx, ny = node.getCoord()
            d = ((nx - x) ** 2 + (ny - y) ** 2) ** 0.5
            if d < best_d:
                best, best_d = node, d
        rows.append([iid, lat, lon, int(active), port or "", best.getID() if best else "", round(best_d, 1)])
    rows.sort(key=lambda r: (-r[3], r[0]))
    with open(common.INTERSECTION_MAP, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["intersectionId", "lat", "lon", "active", "port", "junctionId", "distance_m"])
        w.writerows(rows)
    print(f"{common.INTERSECTION_MAP}: {len(rows)} 交差点")
    for r in rows:
        if r[3]:
            print(f"  [ES] {r[0]} → junction {r[5]}  ({r[6]} m)")
    far = [r for r in rows if r[6] > 20]
    if far:
        print(f"  注意: SUMOの交差点と20m以上離れているもの {len(far)} 件（地図の範囲外や道路の簡略化による）")


if __name__ == "__main__":
    convert()
    map_intersections()
