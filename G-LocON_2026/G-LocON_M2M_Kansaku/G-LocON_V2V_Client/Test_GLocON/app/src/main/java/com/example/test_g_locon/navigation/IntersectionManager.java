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
 *   ETA_THRESHOLD_SEC  = 30.0  : ETA がこの値以下になったらJOIN
 *   LEAVE_THRESHOLD_M  = 30.0  : 交差点から この距離以上かつ遠ざかっていたらLEAVE
 */
public class IntersectionManager {

    public interface IIntersectionCallback {
        void onShouldJoin(Intersection intersection);
        void onShouldLeave(Intersection intersection);
    }

    private static final double ETA_THRESHOLD_SEC = 30.0;
    private static final double LEAVE_THRESHOLD_M = 30.0;
    private static final double MIN_SPEED_MPS     = 1.0; // ETA計算の最低速度（停止中の除算エラー防止）

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

        for (Intersection intersection : intersections) {
            double dist = hubeny.calcDistance(myLat, myLng,
                    intersection.getLat(), intersection.getLng());
            intersection.setDistanceM(dist);

            double eta = dist / speed;
            intersection.setEtaSec(eta);

            if (!intersection.isJoined() && !intersection.hasJoinedAndLeft()
                    && intersection.hasEdgeServer()) {
                // JOIN判定: ETA < τ
                if (eta < ETA_THRESHOLD_SEC) {
                    intersection.setJoined(true);
                    logJoin(intersection, now, "JOIN");
                    if (callback != null) callback.onShouldJoin(intersection);
                }
            } else if (intersection.isJoined()) {
                // LEAVE判定: d ≥ δ かつ 距離が増加
                if (intersection.shouldLeave(LEAVE_THRESHOLD_M)) {
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
