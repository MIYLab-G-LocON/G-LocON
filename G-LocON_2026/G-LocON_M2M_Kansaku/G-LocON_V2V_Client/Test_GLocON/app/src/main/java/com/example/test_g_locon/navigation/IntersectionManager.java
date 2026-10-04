package com.example.test_g_locon.navigation;

import android.content.Context;

import com.example.test_g_locon.main.HubenyDistance;
import com.example.test_g_locon.main.OutputToCSV;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ルート上の交差点リストを管理し，ETA計算・JOIN/LEAVE判定を行うクラス。
 *
 * AppControllerのonLocationChanged()から呼ばれ，
 * JOIN/LEAVEが必要な交差点をコールバックで通知する。
 *
 * パラメータ:
 *   参加タイミング τ  = 15.0  : ETA がこの値を下回ったらJOIN
 *                                （評価では 15 / 30 / 45秒を比較する。SUMOモードでは SimBridge から値を受け取る）
 *   参加円の半径 ρ    = 100.0 : 交差点までの直線距離がこの値未満なら，ETA に関係なくJOIN（0 = 参加円なし）
 *                                （評価では 0 / 50 / 100 / 150m を比較する。SUMOモードでは SimBridge から値を受け取る）
 *   PASS_RADIUS_M      = 20.0  : 交差点にこの距離まで近づいたら「通過済み」とする
 *   離脱円の半径 δ    = 100.0 : 通過済みで，交差点から この距離以上かつ遠ざかっていたらLEAVE
 *                                （評価では 30 / 60 / 100 / 150m を比較する。SUMOモードでは SimBridge から値を受け取る）
 *
 * 通過済みの判定（PASS_RADIUS_M）は，SUMOで全車両の位置を1秒ごと（アプリと同じ間隔）に記録し，
 * 交差点に最も近づいた距離を集計して決めた（車の中心の位置で，エッジサーバ交差点312回の通過で 99% が 10.8m 以内，最大 11.4m，
 * 全交差点8,922回で最大 19.0m）。実機ではGPSの誤差が加わるため余裕を持たせて 20m とし，
 * それでも近づかずに通り過ぎた場合は，ルート上の後の交差点を通過した時点で前の交差点を LEAVE する。
 *
 * 通過はルートの順番に沿って判定する（まだ通過していない最初の交差点から PASS_LOOKAHEAD 個先まで）。
 * ルートが後で出発地点の近くに戻ってくる場合に，出発直後に遠い先の交差点を「通過済み」としないため。
 */
public class IntersectionManager {

    public interface IIntersectionCallback {
        void onShouldJoin(Intersection intersection);
        void onShouldLeave(Intersection intersection);
    }

    public  static final double PASS_RADIUS_M     = 20.0;
    private static final int    PASS_LOOKAHEAD    = 3;
    private static final double MIN_SPEED_MPS     = 1.0; // ETA計算の最低速度（停止中の除算エラー防止）

    /** 離脱円の半径 δ の既定値 [m] */
    public  static final double DEFAULT_LEAVE_THRESHOLD_M = 100.0;   // 2026/10/04: 60 → 100（実験記録 E9）
    /** 離脱円の半径 δ [m]。地図の離脱円（MapManager）にも使う。SUMOモードでは SimBridge の設定値に合わせる */
    private static volatile double leaveThresholdM = DEFAULT_LEAVE_THRESHOLD_M;

    public static double getLeaveThresholdM() { return leaveThresholdM; }
    public static void setLeaveThresholdM(double m) { if (m > 0) leaveThresholdM = m; }

    /** 参加タイミング τ の既定値 [秒]: 交差点までのETAがこれを下回ったらJOIN */
    public  static final double DEFAULT_JOIN_ETA_SEC = 15.0;
    /** 評価で比べる候補（状態カードをタップして選べる） */
    public  static final double[] JOIN_ETA_CANDIDATES_SEC = {15.0, 30.0, 45.0};
    public  static final double[] LEAVE_CANDIDATES_M      = {30.0, 60.0, 100.0, 150.0};
    private static volatile double joinEtaSec = DEFAULT_JOIN_ETA_SEC;

    public static double getJoinEtaSec() { return joinEtaSec; }
    public static void setJoinEtaSec(double s) { if (s > 0) joinEtaSec = s; }

    /**
     * 参加円の半径 ρ の既定値 [m]: 交差点までの直線距離がこれ未満なら，ETA に関係なく JOIN する（0 = 参加円なし）。
     * 渋滞でゆっくり進む車は ETA が大きくなり，交差点のすぐ手前にいても参加しないため（2026/10/04 追加）。
     */
    public  static final double DEFAULT_JOIN_RADIUS_M = 100.0;
    public  static final double[] JOIN_RADIUS_CANDIDATES_M = {0.0, 50.0, 100.0, 150.0};
    private static volatile double joinRadiusM = DEFAULT_JOIN_RADIUS_M;

    public static double getJoinRadiusM() { return joinRadiusM; }
    public static void setJoinRadiusM(double m) { if (m >= 0) joinRadiusM = m; }

    private final List<Intersection> intersections = new CopyOnWriteArrayList<>();
    private final HubenyDistance hubeny = new HubenyDistance();
    private IIntersectionCallback callback;

    // join_log.csv
    private OutputToCSV joinLog;

    public IntersectionManager(Context context) {
        joinLog = new OutputToCSV(context, "join_log.csv");
        joinLog.OutputFieledName("intersectionId", "t_update_ms", "eta_sec", "distance_m", "event");
    }

    public void setCallback(IIntersectionCallback callback) {
        this.callback = callback;
    }

    /** OsrmRouteClientで取得した交差点リストをセットする */
    public void setIntersections(List<Intersection> list) {
        intersections.clear();
        intersections.addAll(list);
    }

    public List<Intersection> getIntersections() {
        return new ArrayList<>(intersections);
    }

    /**
     * GPS更新のたびに呼ばれる。各交差点のETA・距離を更新し，JOIN/LEAVEを判定する。
     *
     * @param myLat   自車の緯度
     * @param myLng   自車の経度
     * @param speedMs 自車の速度（m/s）
     */
    public void update(double myLat, double myLng, double speedMs) {
        double speed = Math.max(speedMs, MIN_SPEED_MPS);
        long now = System.currentTimeMillis();

        // 通過判定（リストはルート順）: まだ通過していない最初の交差点から PASS_LOOKAHEAD 個先までを見て，
        // PASS_RADIUS_M 以内に入った最も先の交差点までを通過済みとする
        int n = intersections.size();
        int next = 0;
        while (next < n && intersections.get(next).isPassed()) next++;
        int lastPassed = next - 1;
        for (int k = 0; k < n; k++) {
            Intersection intersection = intersections.get(k);
            double dist = hubeny.calcDistance(myLat, myLng,
                    intersection.getLat(), intersection.getLng());
            intersection.setDistanceM(dist);
            if (k >= next && k <= next + PASS_LOOKAHEAD && dist <= PASS_RADIUS_M) {
                intersection.setPassed(true);
                lastPassed = Math.max(lastPassed, k);
            }
        }

        for (int k = 0; k < intersections.size(); k++) {
            Intersection intersection = intersections.get(k);
            double dist = intersection.getDistanceM();

            double eta = dist / speed;
            intersection.setEtaSec(eta);

            // 保険: ルート上の後の交差点を通過したのに，この交差点は近づかないまま（GPSのずれなど）
            //       → 通過したものとみなし，JOIN中なら LEAVE，未JOINならこの先JOINしない
            if (k < lastPassed && !intersection.isPassed()) {
                intersection.setPassed(true);
                if (intersection.isJoined()) {
                    intersection.setJoined(false);
                    intersection.setHasJoinedAndLeft(true);
                    logJoin(intersection, now, "LEAVE_PASSED_NEXT");
                    if (callback != null) callback.onShouldLeave(intersection);
                } else {
                    intersection.setHasJoinedAndLeft(true);
                }
                continue;
            }

            if (!intersection.isJoined() && !intersection.hasJoinedAndLeft()
                    && intersection.hasEdgeServer()) {
                // JOIN判定: ETA < τ，または参加円（半径 ρ）の中
                if (eta < joinEtaSec || dist < joinRadiusM) {
                    intersection.setJoined(true);
                    logJoin(intersection, now, "JOIN");
                    if (callback != null) callback.onShouldJoin(intersection);
                }
            } else if (intersection.isJoined()) {
                // LEAVE判定: d ≥ δ かつ 距離が増加
                if (intersection.shouldLeave(leaveThresholdM)) {
                    intersection.setJoined(false);
                    intersection.setHasJoinedAndLeft(true); // 同一交差点への再JOINを防ぐ
                    logJoin(intersection, now, "LEAVE");
                    if (callback != null) callback.onShouldLeave(intersection);
                }
            }
        }
    }

    private void logJoin(Intersection i, long now, String event) {
        joinLog.OutputData(
                i.getIntersectionId(),
                String.valueOf(now),
                String.format("%.1f", i.getEtaSec()),
                String.format("%.1f", i.getDistanceM()),
                event
        );
    }

    /** SIM開始前に呼び出し，全交差点のJOIN/LEAVE状態と距離履歴を初期化する */
    public void resetAllJoinState() {
        for (Intersection i : intersections) {
            i.setJoined(false);
            i.setHasJoinedAndLeft(false);
            i.resetDistanceHistory();
        }
        android.util.Log.i("IntersectionManager", "全交差点のJOIN状態をリセット: " + intersections.size() + "件");
    }

    public void close() {
        joinLog.fileClose();
    }
}
