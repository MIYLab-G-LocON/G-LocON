"""SimBridge 共通設定・ユーティリティ."""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OSM_FILE = os.path.join(HERE, "osm", "area.osm")
SCENARIO_DIR = os.path.join(HERE, "scenario")
NET_FILE = os.path.join(SCENARIO_DIR, "area.net.xml")
INTERSECTIONS_CSV = os.path.join(SCENARIO_DIR, "area_intersections.csv")   # エリア内の全交差点
SIM_EDGE_SERVERS_CSV = os.path.join(SCENARIO_DIR, "edge_servers.csv")      # ランダムに選んだエッジサーバ
OUT_DIR = os.path.join(HERE, "out")

# 対象エリア（長方形）。中心と，東西・南北の長さで指定する。
# osm/area.osm の範囲（緯度35.9435〜35.9545，経度139.6380〜139.6590 ≒ 南北1.2km×東西1.9km）に収まること。
# 2026/10/04: 1km四方 → 東西1.8km×南北1.15km に広げた（1ルートが短く，エッジサーバ交差点を1回しか通らない車が多かったため）
AREA_CENTER = (35.9490, 139.6485)   # (lat, lon)
AREA_WIDTH_M = 1800.0               # 東西
AREA_HEIGHT_M = 1150.0              # 南北

# エッジサーバのポート（EdgeServer は 1交差点1ポート）
EDGE_SERVER_IP = "192.168.137.1"
EDGE_SERVER_BASE_PORT = 55600

# アプリ側の設定と揃える
JOIN_ETA_SEC = 15.0      # τ（参加タイミング）: ETAがこれを下回るとJOIN
                         # 評価では 15 / 30 / 45秒を比較する（sim_bridge.py・run_scenario.py の --join-eta）
LEAVE_DIST_M = 60.0      # δ（離脱円の半径）: 通過後，交差点からこれ以上離れ，かつ遠ざかっていればLEAVE
                         # 評価では 30 / 60 / 100m を比較する（sim_bridge.py・run_scenario.py の --leave-dist）


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


def area_bbox(center=None, width_m=None, height_m=None):
    """長方形エリアの (lon_min, lat_min, lon_max, lat_max)."""
    lat, lon = center or AREA_CENTER
    dlat = (height_m or AREA_HEIGHT_M) / 2.0 / 111_320.0
    dlon = (width_m or AREA_WIDTH_M) / 2.0 / (111_320.0 * math.cos(math.radians(lat)))
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


def vehicle_xy(traci, vid):
    """車の中心の座標（SUMO座標）.

    SUMO の getPosition は車の先端（前のバンパー）の位置で，sumo-gui では車体がそこから後ろへ描かれる。
    スマホは車内にあるので，アプリに渡す位置・仮想クライアントの判定には車の中心を使う
    （先端のままだと，LEAVE した時点で車体の後ろ半分がまだ離脱円の中に見える）。
    """
    x, y = traci.vehicle.getPosition(vid)
    a = math.radians(traci.vehicle.getAngle(vid))      # 北=0°，時計回り
    h = traci.vehicle.getLength(vid) / 2.0
    return x - h * math.sin(a), y - h * math.cos(a)


def gui_vehicle_exaggeration():
    """gui_settings.xml の車の拡大率（vehicle_exaggeration）. 見つからなければ 1."""
    import xml.etree.ElementTree as ET
    try:
        v = ET.parse(os.path.join(HERE, "gui_settings.xml")).getroot().find(".//vehicles")
        return float(v.get("vehicle_exaggeration", 1.0))
    except Exception:
        return 1.0


def load_net():
    sumo_home()
    import sumolib
    return sumolib.net.readNet(NET_FILE, withInternal=False)
