"""SimBridge 共通設定・ユーティリティ."""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# シナリオ（地図）の切り替え: 環境変数 GLOCON_SCENARIO で選ぶ（全スクリプト共通）。
#   （指定なし） 大学周辺の生活道路中心の地図（scenario/）。実機実験にもこれを使う
#   arterial     幹線道路（国道16号 東大宮バイパス・県道5号 第二産業道路）を含む広い地図（scenario_arterial/）。
#                交通量・速度の高い道路での比較用（2026/10/04 追加）
#   例（PowerShell）: $env:GLOCON_SCENARIO="arterial"; python compare_schemes.py
SCENARIO = os.environ.get("GLOCON_SCENARIO", "").strip()
_AREAS = {
    # 名前: (地図データ, 中心(緯度, 経度), 東西[m], 南北[m], 追加の道路の型定義（osm/ 内）)
    # 2026/10/04: 大学周辺は 1km四方 → 東西1.8km×南北1.15km に広げた
    "": ("area.osm", (35.9490, 139.6485), 1800.0, 1150.0, []),
    "arterial": ("arterial.osm", (35.9555, 139.6450), 3000.0, 2700.0, ["japan_speeds.typ.xml"]),
}
if SCENARIO not in _AREAS:
    sys.exit(f"GLOCON_SCENARIO={SCENARIO!r} は未定義です（使える値: {[k for k in _AREAS if k]}，または指定なし）")
_osm, AREA_CENTER, AREA_WIDTH_M, AREA_HEIGHT_M, EXTRA_TYPE_FILES = _AREAS[SCENARIO]
OSM_FILE = os.path.join(HERE, "osm", _osm)
SCENARIO_DIR = os.path.join(HERE, "scenario" + ("_" + SCENARIO if SCENARIO else ""))
NET_FILE = os.path.join(SCENARIO_DIR, "area.net.xml")
INTERSECTIONS_CSV = os.path.join(SCENARIO_DIR, "area_intersections.csv")   # エリア内の全交差点
SIM_EDGE_SERVERS_CSV = os.path.join(SCENARIO_DIR, "edge_servers.csv")      # エッジサーバを置く交差点
OUT_DIR = os.path.join(HERE, "out")

# エッジサーバのポート（EdgeServer は 1交差点1ポート）
EDGE_SERVER_IP = "192.168.137.1"
EDGE_SERVER_BASE_PORT = 55600

# アプリ側の設定と揃える
JOIN_ETA_SEC = 15.0      # τ（参加タイミング）: ETAがこれを下回るとJOIN
                         # 評価では 15 / 30 / 45秒を比較する（sim_bridge.py・run_scenario.py の --join-eta）
JOIN_DIST_M = 100.0      # ρ（参加円の半径）: 交差点までの直線距離がこれ未満なら，ETA に関係なくJOIN（0 = 参加円なし）
                         # 渋滞でゆっくり進む車は ETA が大きく，交差点のすぐ手前でも参加しないため（2026/10/04 追加）
                         # 評価では 0 / 50 / 100 / 150m を比較する（--join-dist）
LEAVE_DIST_M = 100.0     # δ（離脱円の半径）: 通過後，交差点からこれ以上離れ，かつ遠ざかっていればLEAVE（2026/10/04: 60 → 100）
                         # 評価では 30 / 60 / 100 / 150m を比較する（sim_bridge.py・run_scenario.py の --leave-dist）


class RouteTrack:
    """ルートの線（折れ線）に沿った道のりの計算。ETA の距離に使う（2026/11/16 に直線距離から変更）.

    アプリの RouteGeometry.Track と同じ規則（座標はメートル。SUMO座標をそのまま渡す）:
      1. 各点までの道のり cum を求める
      2. 交差点はルート順に，前の交差点の道のり - BACK_M 以降で線に最も近い所に当てはめる（locate）
      3. 自分の位置は，前回の道のり s から [s - BACK_M, s + AHEAD_M] の範囲で線に最も近い所に当てはめる。
         線から OFF_ROUTE_M より離れていたら s - BACK_M 以降の全体で探し直し，それでも離れていればルート外（progress）
      4. 交差点までの道のり = s_i - s（負なら 0）
    """
    BACK_M = 5.0
    AHEAD_M = 300.0
    OFF_ROUTE_M = 50.0

    def __init__(self, pts):
        self.xs = [p[0] for p in pts]
        self.ys = [p[1] for p in pts]
        self.cum = [0.0]
        for i in range(1, len(pts)):
            self.cum.append(self.cum[-1] + math.hypot(self.xs[i] - self.xs[i - 1], self.ys[i] - self.ys[i - 1]))

    def project(self, px, py, s_min, s_max):
        """道のりが [s_min, s_max] の当てはめ先のうち線に最も近いもの (s, d)。無ければ None（同じ距離なら s の小さい方）."""
        import bisect
        xs, ys, cum = self.xs, self.ys, self.cum
        i = max(bisect.bisect_left(cum, s_min) - 1, 0)
        best = None
        while i < len(xs) - 1:
            if cum[i] > s_max:
                break
            if cum[i + 1] >= s_min:
                dx, dy = xs[i + 1] - xs[i], ys[i + 1] - ys[i]
                l2 = dx * dx + dy * dy
                if l2 > 0:
                    u = max(0.0, min(1.0, ((px - xs[i]) * dx + (py - ys[i]) * dy) / l2))
                    s = cum[i] + u * math.sqrt(l2)
                    if s_min <= s <= s_max:
                        d = math.hypot(px - (xs[i] + u * dx), py - (ys[i] + u * dy))
                        if best is None or d < best[1] or (d == best[1] and s < best[0]):
                            best = (s, d)
            i += 1
        return best

    def progress(self, px, py, s_last):
        """自分の道のり (s, ルート上か)。ルート外なら (s_last, False)."""
        p = self.project(px, py, s_last - self.BACK_M, s_last + self.AHEAD_M)
        if p is None or p[1] > self.OFF_ROUTE_M:
            q = self.project(px, py, s_last - self.BACK_M, float("inf"))
            if q is not None and (p is None or q[1] < p[1]):
                p = q
        if p is None or p[1] > self.OFF_ROUTE_M:
            return s_last, False
        return p[0], True

    def locate(self, px, py, s_min):
        """交差点の道のり（前の交差点の道のり s_min 以降）。当てはめ先が無ければ None."""
        p = self.project(px, py, s_min - self.BACK_M, float("inf"))
        return None if p is None else p[0]

    def locate_all(self, pts):
        """ルート順の点（交差点）の道のりのリスト（当てはめられなければ None）."""
        out, prev = [], 0.0
        for x, y in pts:
            s = self.locate(x, y, prev)
            out.append(s)
            if s is not None:
                prev = s
        return out


def lonlat_track(shape):
    """[[緯度, 経度], ...] を，アプリと同じ近似（先頭を原点とする正距円筒）でメートル座標の RouteTrack にする.

    戻り値: (RouteTrack, 緯度経度 -> (x, y) の関数)。点が2つ未満なら (None, None)
    """
    if not shape or len(shape) < 2:
        return None, None
    lat0, lon0 = shape[0][0], shape[0][1]
    kx, ky = 111320.0 * math.cos(math.radians(lat0)), 110540.0

    def to_xy(lat, lon):
        return (lon - lon0) * kx, (lat - lat0) * ky

    return RouteTrack([to_xy(la, lo) for la, lo in shape]), to_xy


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
