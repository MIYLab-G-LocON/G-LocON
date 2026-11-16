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

    // ===================== 交差点までの道のり（ETA 用。2026/11/16 追加） =====================
    //
    // ETA の距離を，直線距離ではなくルートの線に沿った道のりにするための計算。
    // SimBridge の仮想クライアント（sim_bridge.py）と評価のシミュレーション（compare_schemes.py --eta-by path）も
    // common.py の RouteTrack で同じ規則を使う:
    //   1. ルートの線を，先頭を原点とするメートル座標の折れ線にし，各点までの道のり cum を求める
    //   2. 交差点はルート順に，前の交差点の道のり - BACK_M 以降で線に最も近い所に当てはめる（交差点の道のり s_i）
    //   3. 自分の位置は，前回の道のり s から [s - BACK_M, s + AHEAD_M] の範囲で線に最も近い所に当てはめる。
    //      その範囲で線から OFF_ROUTE_M より離れていたら，s - BACK_M 以降の全体で探し直す（大きく進んだとき）。
    //      それでも OFF_ROUTE_M より離れていたらルート外とし，道のりは使わない（呼び出し側は直線距離に戻す）
    //   4. 交差点までの道のり = s_i - s（負なら 0）

    /** 後戻りを許す道のり [m]（測位誤差で少し戻る分） */
    public static final double BACK_M = 5.0;
    /** 毎回探す前方の範囲 [m]（1秒で進む距離より十分大きい） */
    public static final double AHEAD_M = 300.0;
    /** ルートの線からこれ以上離れていたらルート外 [m] */
    public static final double OFF_ROUTE_M = 50.0;

    /** ルートの線（メートル座標の折れ線と，各点までの道のり） */
    public static final class Track {
        final double lat0, lon0, kx, ky;
        final double[] xs, ys, cum;

        Track(List<double[]> shape) {
            lat0 = shape.get(0)[0];
            lon0 = shape.get(0)[1];
            kx = 111320.0 * Math.cos(Math.toRadians(lat0));
            ky = 110540.0;
            int n = shape.size();
            xs = new double[n];
            ys = new double[n];
            cum = new double[n];
            for (int i = 0; i < n; i++) {
                xs[i] = (shape.get(i)[1] - lon0) * kx;
                ys[i] = (shape.get(i)[0] - lat0) * ky;
                cum[i] = i == 0 ? 0 : cum[i - 1] + Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
            }
        }

        public double length() { return cum[cum.length - 1]; }

        /**
         * 点を線に当てはめる。道のりが [sMin, sMax] の範囲の当てはめ先のうち，線に最も近いものを返す
         * （同じ距離なら道のりの小さい方）。
         * @return {道のり s, 線からの距離 d}。範囲内に候補が無ければ null
         */
        double[] project(double lat, double lon, double sMin, double sMax) {
            double px = (lon - lon0) * kx, py = (lat - lat0) * ky;
            double bestS = 0, bestD = Double.MAX_VALUE;
            boolean found = false;
            for (int i = 0; i < xs.length - 1; i++) {
                if (cum[i + 1] < sMin) continue;
                if (cum[i] > sMax) break;
                double dx = xs[i + 1] - xs[i], dy = ys[i + 1] - ys[i], l2 = dx * dx + dy * dy;
                if (l2 <= 0) continue;
                double u = Math.max(0, Math.min(1, ((px - xs[i]) * dx + (py - ys[i]) * dy) / l2));
                double s = cum[i] + u * Math.sqrt(l2);
                if (s < sMin || s > sMax) continue;
                double d = Math.hypot(px - (xs[i] + u * dx), py - (ys[i] + u * dy));
                if (d < bestD || (d == bestD && s < bestS)) {
                    bestD = d;
                    bestS = s;
                    found = true;
                }
            }
            return found ? new double[]{bestS, bestD} : null;
        }

        /**
         * 自分の位置の道のり（上の規則 3）。
         * @return {道のり s, 線からの距離 d, ルート上なら 1・ルート外なら 0}
         */
        public double[] progress(double lat, double lon, double sLast) {
            double[] p = project(lat, lon, sLast - BACK_M, sLast + AHEAD_M);
            if (p == null || p[1] > OFF_ROUTE_M) {
                double[] q = project(lat, lon, sLast - BACK_M, Double.MAX_VALUE);
                if (q != null && (p == null || q[1] < p[1])) p = q;
            }
            if (p == null || p[1] > OFF_ROUTE_M) {
                return new double[]{sLast, p == null ? Double.MAX_VALUE : p[1], 0};
            }
            return new double[]{p[0], p[1], 1};
        }

        /** 交差点の道のり（上の規則 2）。sMin は前の交差点の道のり（先頭は 0） */
        public double locate(double lat, double lon, double sMin) {
            double[] p = project(lat, lon, sMin - BACK_M, Double.MAX_VALUE);
            return p == null ? Double.NaN : p[0];
        }
    }

    /** ルートの線から Track を作る（点が2つ未満なら null） */
    public static Track track(List<double[]> shape) {
        return (shape == null || shape.size() < 2) ? null : new Track(shape);
    }
}
