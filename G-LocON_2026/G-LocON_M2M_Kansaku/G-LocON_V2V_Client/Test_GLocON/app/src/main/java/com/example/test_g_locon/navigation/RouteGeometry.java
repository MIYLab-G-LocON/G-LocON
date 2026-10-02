package com.example.test_g_locon.navigation;

import java.util.List;

/**
 * ルートの線（道の形）を使った幾何の計算。
 *
 * 危険情報を受け取ったとき，その地点が「自分のルートの前方」にあるか（＝接近中か）を判定する。
 * SimBridge の仮想クライアント（sim_bridge.py の hazard_ahead）と同じ規則:
 *   - 危険地点がルートの線から SIDE_M 以内
 *   - その地点でのルートの向きと，危険車両の向きの差が ANGLE_DEG 以内（対向車線・交差する道を除く）
 *   - 自分より先で，MAX_M 以内
 */
public final class RouteGeometry {

    public static final double SIDE_M = 12.0;
    public static final double ANGLE_DEG = 60.0;
    public static final double MAX_M = 400.0;

    private RouteGeometry() {}

    /** 判定結果: gap = 危険地点までの道のり [m]（前方に無ければ負），sMe = ルート上の自分の道のり */
    public static final class Result {
        public final double gap;
        public final double sMe;
        Result(double gap, double sMe) { this.gap = gap; this.sMe = sMe; }
        public boolean isAhead() { return gap > 0; }
    }

    /**
     * @param shape  ルートの線 [緯度, 経度] の列
     * @param sLast  前回の自分の道のり（ルートが同じ道を2回通るときに後戻りしないため。初回は 0）
     */
    public static Result hazardAhead(List<double[]> shape, double myLat, double myLon, double sLast,
                                     double hLat, double hLon, double hBearing) {
        if (shape == null || shape.size() < 2) return new Result(-1, sLast);
        // 緯度経度を，ルートの先頭を原点とするメートル座標に直す（短距離なので平面近似で十分）
        final double lat0 = shape.get(0)[0], lon0 = shape.get(0)[1];
        final double kx = 111320.0 * Math.cos(Math.toRadians(lat0)), ky = 110540.0;
        final double mx = (myLon - lon0) * kx, my = (myLat - lat0) * ky;
        final double hx = (hLon - lon0) * kx, hy = (hLat - lat0) * ky;

        double sMe = sLast, bestD = Double.MAX_VALUE;
        double gap = -1;
        boolean found = false;
        // 1回目: 自分の位置（sLast-5 以降で線に最も近い所）
        double cum = 0;
        double x1 = 0, y1 = 0;
        for (int i = 0; i < shape.size() - 1; i++) {
            double x2 = (shape.get(i + 1)[1] - lon0) * kx, y2 = (shape.get(i + 1)[0] - lat0) * ky;
            double dx = x2 - x1, dy = y2 - y1, l2 = dx * dx + dy * dy;
            if (l2 > 0) {
                double len = Math.sqrt(l2);
                double u = Math.max(0, Math.min(1, ((mx - x1) * dx + (my - y1) * dy) / l2));
                double s = cum + u * len;
                if (s >= sLast - 5.0) {
                    double d = Math.hypot(mx - (x1 + u * dx), my - (y1 + u * dy));
                    if (d < bestD) { bestD = d; sMe = s; }
                }
                cum += len;
            }
            x1 = x2; y1 = y2;
        }
        // 2回目: 危険地点（自分より先で，線に近く，向きが同じ最初の所）
        cum = 0; x1 = 0; y1 = 0;
        for (int i = 0; i < shape.size() - 1 && !found; i++) {
            double x2 = (shape.get(i + 1)[1] - lon0) * kx, y2 = (shape.get(i + 1)[0] - lat0) * ky;
            double dx = x2 - x1, dy = y2 - y1, l2 = dx * dx + dy * dy;
            if (l2 > 0) {
                double len = Math.sqrt(l2);
                double u = Math.max(0, Math.min(1, ((hx - x1) * dx + (hy - y1) * dy) / l2));
                double s = cum + u * len;
                if (s >= sMe) {
                    double d = Math.hypot(hx - (x1 + u * dx), hy - (y1 + u * dy));
                    double segBearing = (Math.toDegrees(Math.atan2(dx, dy)) + 360.0) % 360.0;
                    if (d <= SIDE_M && angleDiff(segBearing, hBearing) <= ANGLE_DEG) {
                        found = true;
                        double g = s - sMe;
                        gap = (g > 0 && g <= MAX_M) ? g : -1;
                    }
                }
                cum += len;
            }
            x1 = x2; y1 = y2;
        }
        return new Result(gap, sMe);
    }

    static double angleDiff(double a, double b) {
        return Math.abs(((a - b + 180.0) % 360.0 + 360.0) % 360.0 - 180.0);
    }
}
