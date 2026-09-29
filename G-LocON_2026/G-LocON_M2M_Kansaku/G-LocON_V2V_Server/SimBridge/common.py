"""SimBridge 共通設定・ユーティリティ."""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OSM_FILE = os.path.join(HERE, "osm", "area.osm")
SCENARIO_DIR = os.path.join(HERE, "scenario")
NET_FILE = os.path.join(SCENARIO_DIR, "area.net.xml")
INTERSECTION_MAP = os.path.join(SCENARIO_DIR, "intersection_map.csv")
EDGE_SERVERS_CSV = os.path.normpath(os.path.join(HERE, "..", "MasterServer", "edge_servers.csv"))
OUT_DIR = os.path.join(HERE, "out")

# アプリ側（OsrmRouteClient）と同じ始点・終点。実験ルートはこの2点を結ぶ
ROUTE_START = (35.952087, 139.65523)   # (lat, lon)
ROUTE_END = (35.949065, 139.640613)

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


def load_net():
    sumo_home()
    import sumolib
    return sumolib.net.readNet(NET_FILE, withInternal=False)


def read_edge_servers(include_commented=True):
    """edge_servers.csv から交差点を読む.

    有効行（エッジサーバあり）に加え，コメントアウトされた OSRM 交差点も
    include_commented=True なら返す（全ルート交差点との対応表を作るため）。
    戻り値: [(intersectionId, lat, lon, port or None, active)]
    """
    rows = []
    with open(EDGE_SERVERS_CSV, encoding="utf-8") as f:
        for raw in f:
            line = raw.strip()
            if not line or line.startswith("intersectionId"):
                continue
            active = not line.startswith("#")
            body = line.lstrip("#").strip()
            parts = body.split(",")
            if len(parts) < 3 or "_" not in parts[0]:
                continue
            try:
                lat_s, lon_s = parts[0].split("_")
                lat, lon = float(lat_s), float(lon_s)
            except ValueError:
                continue
            if not active and not include_commented:
                continue
            port = parts[2].strip()
            rows.append((parts[0], lat, lon, port if port.isdigit() else None, active))
    # 同じIDの重複（_old 行など）は有効行を優先
    uniq = {}
    for r in rows:
        if r[0] not in uniq or r[4]:
            uniq[r[0]] = r
    return list(uniq.values())
