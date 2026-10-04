"""段階1-a: 対象エリア（長方形）の道路網を作り，エリア内の全交差点を洗い出す.

    python build_net.py [--width 1800] [--height 1150] [--center 35.9490,139.6485]

出力:
    scenario/area.net.xml          エリアで切り出したSUMO道路網（左側通行）
    scenario/area_intersections.csv  エリア内の全交差点
        intersectionId（アプリと同じ "緯度5桁_経度5桁"）, lat, lon, junctionId, degree
"""
import argparse
import csv
import os
import subprocess
import sys

import common


def convert(bbox):
    if not os.path.exists(common.OSM_FILE):
        sys.exit(f"{common.OSM_FILE} がありません（OpenStreetMapのデータを置いてください）")
    os.makedirs(common.SCENARIO_DIR, exist_ok=True)
    typemap = os.path.join(common.sumo_home(), "data", "typemap", "osmNetconvert.typ.xml")
    cmd = [
        common.sumo_bin("netconvert"),
        "--osm-files", common.OSM_FILE,
        "--type-files", typemap + "," + os.path.join(common.HERE, "osm", "service_passenger.typ.xml"),
        "-o", common.NET_FILE,
        # エリアで切り出す
        "--keep-edges.in-geo-boundary", ",".join(f"{v:.6f}" for v in bbox),
        # 車が走る道路だけを残し，つながっていない断片は捨てる
        "--keep-edges.by-vclass", "passenger",
        "--remove-edges.isolated", "--keep-edges.components", "1",
        # 交差点・信号の整形
        "--geometry.remove", "--roundabouts.guess", "--ramps.guess",
        "--junctions.join", "--tls.guess-signals", "--tls.discard-simple", "--tls.join",
        "--osm.turn-lanes",
        "--lefthand",                      # 日本は左側通行
        "--output.street-names", "--output.original-names",
        "--proj.utm",
        "--no-warnings",
    ]
    subprocess.run(cmd, check=True)


def extract_intersections():
    """3方向以上に道がつながる地点を交差点とみなす."""
    net = common.load_net()
    rows = []
    for node in net.getNodes():
        if node.getType() in ("dead_end", "internal"):
            continue
        nbrs = {e.getFromNode().getID() for e in node.getIncoming()} | \
               {e.getToNode().getID() for e in node.getOutgoing()}
        nbrs.discard(node.getID())
        if len(nbrs) < 3:
            continue
        x, y = node.getCoord()
        lon, lat = net.convertXY2LonLat(x, y)
        rows.append([common.intersection_id(lat, lon), round(lat, 6), round(lon, 6), node.getID(), len(nbrs)])
    rows.sort()
    with open(common.INTERSECTIONS_CSV, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["intersectionId", "lat", "lon", "junctionId", "degree"])
        w.writerows(rows)
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--width", type=float, default=common.AREA_WIDTH_M, help="東西の長さ [m]")
    ap.add_argument("--height", type=float, default=common.AREA_HEIGHT_M, help="南北の長さ [m]")
    ap.add_argument("--center", default=None, help="中心の 緯度,経度（省略時は common.AREA_CENTER）")
    a = ap.parse_args()
    center = tuple(float(v) for v in a.center.split(",")) if a.center else None
    bbox = common.area_bbox(center, a.width, a.height)
    print(f"対象エリア: 経度 {bbox[0]:.5f}〜{bbox[2]:.5f}, 緯度 {bbox[1]:.5f}〜{bbox[3]:.5f}"
          f"（東西 {a.width:.0f} m × 南北 {a.height:.0f} m）")
    convert(bbox)
    rows = extract_intersections()
    print(f"エリア内の交差点: {len(rows)} か所 → {common.INTERSECTIONS_CSV}")


if __name__ == "__main__":
    main()
