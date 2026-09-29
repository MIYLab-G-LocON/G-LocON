"""SimBridge 共通設定・ユーティリティ."""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OSM_FILE = os.path.join(HERE, "osm", "area.osm")
SCENARIO_DIR = os.path.join(HERE, "scenario")
NET_FILE = os.path.join(SCENARIO_DIR, "area.net.xml")
INTERSECTIONS_CSV = os.path.join(SCENARIO_DIR, "area_intersections.csv")   # エリア内の全交差点
SIM_EDGE_SERVERS_CSV = os.path.join(SCENARIO_DIR, "edge_servers.csv")      # ランダムに選んだエッジサーバ
OUT_DIR = os.path.join(HERE, "out")

# 対象エリア（正方形）。中心と一辺の長さで指定する。
# osm/area.osm の範囲（緯度35.9435〜35.9545，経度139.6380〜139.6590 ≒ 1.2km×1.9km）に収まること
AREA_CENTER = (35.9490, 139.6485)   # (lat, lon)
AREA_SIDE_M = 1000.0

# エッジサーバのポート（EdgeServer は 1交差点1ポート）
EDGE_SERVER_IP = "192.168.137.1"
EDGE_SERVER_BASE_PORT = 55600

# アプリ側の設定と揃える
JOIN_ETA_SEC = 30.0      # τ: ETAがこれを下回るとJOIN
LEAVE_DIST_M = 30.0      # δ: 交差点からこれ以上離れ，かつ遠ざかっていればLEAVE


def sumo_home():
    """SUMO_HOME を返す（pipの eclipse-sumo でも公式インストーラでも可）."""
    if os.environ.get("SUMO_HOME"):
        return os.environ["SUMO_HOME"]
    try:
        import sumo  # pip install eclipse-sumo
        os.environ["SUMO_HOME"] = sumo.SUMO_HOME
        return sumo.SUMO_HOME
    except ImportError:
        sys.exit("SUMOが見つかりません。 pip install eclipse-sumo traci sumolib を実行してください")


def sumo_bin(name):
    exe = name + (".exe" if os.name == "nt" else "")
    return os.path.join(sumo_home(), "bin", exe)


def area_bbox(center=None, side_m=None):
    """正方形エリアの (lon_min, lat_min, lon_max, lat_max)."""
    import math
    lat, lon = center or AREA_CENTER
    half = (side_m or AREA_SIDE_M) / 2.0
    dlat = half / 111_320.0
    dlon = half / (111_320.0 * math.cos(math.radians(lat)))
    return lon - dlon, lat - dlat, lon + dlon, lat + dlat


def intersection_id(lat, lon):
    """アプリ（OsrmRouteClient）と同じ "緯度5桁_経度5桁" 形式."""
    return f"{lat:.5f}_{lon:.5f}"


def read_sim_edge_servers():
    """scenario/edge_servers.csv → {intersectionId: junctionId}."""
    import csv
    res = {}
    with open(SIM_EDGE_SERVERS_CSV, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or line.startswith("intersectionId") or not line.strip():
                continue
            iid, _ip, _port, jid = line.strip().split(",")[:4]
            res[iid] = jid
    return res


def load_net():
    sumo_home()
    import sumolib
    return sumolib.net.readNet(NET_FILE, withInternal=False)
