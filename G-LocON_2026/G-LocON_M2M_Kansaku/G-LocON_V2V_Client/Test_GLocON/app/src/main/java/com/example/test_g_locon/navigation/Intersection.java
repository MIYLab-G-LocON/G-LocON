package com.example.test_g_locon.navigation;

/**
 * ルート上の交差点1つを表すデータモデル。
 *
 * OsrmRouteClientがルート解析時に生成し，IntersectionManagerが管理する。
 * MasterServerから取得したエッジサーバAddr/Portも保持する。
 */
public class Intersection {

    private final String intersectionId; // "緯度_経度" 形式
    private final double lat;
    private final double lng;

    // MasterServerから取得（初期はnull）
    private String edgeServerIp;
    private int edgeServerPort;

    // 走行中に更新
    private double etaSec;      // 到達予測時間（秒）
    private double distanceM;   // 現在地からの距離（メートル）
    private double prevDistanceM = -1; // 前回の距離（LEAVE判定用）

    private boolean joined = false;          // 現在JOIN中か
    private boolean hasJoinedAndLeft = false; // 一度JOIN→LEAVEした交差点は再JOINしない
    private boolean passed = false;          // 交差点を通過した（PASS_RADIUS_M 以内に近づいた）

    public Intersection(String intersectionId, double lat, double lng) {
        this.intersectionId = intersectionId;
        this.lat = lat;
        this.lng = lng;
    }

    /**
     * LEAVE条件: 交差点を通過済みで，交差点から δ メートル以上離れており，かつ距離が増加している。
     *
     * [変更] 以前は「通過済み」の条件がなく，交差点に向かう途中でも道が曲がって直線距離が
     *        一時的に増えると LEAVE していた（SUMOでの試験で，エッジサーバ交差点を通過した車の約3割）。
     *        一度 LEAVE すると再JOINしないため，交差点をグループ外のまま通過していた。
     */
    public boolean shouldLeave(double deltaMeters) {
        if (prevDistanceM < 0 || !passed) return false;
        return distanceM >= deltaMeters && distanceM > prevDistanceM;
    }

    public String getIntersectionId() { return intersectionId; }
    public double getLat()            { return lat; }
    public double getLng()            { return lng; }

    public String getEdgeServerIp()   { return edgeServerIp; }
    public void setEdgeServerIp(String edgeServerIp) { this.edgeServerIp = edgeServerIp; }

    public int getEdgeServerPort()    { return edgeServerPort; }
    public void setEdgeServerPort(int edgeServerPort) { this.edgeServerPort = edgeServerPort; }

    public double getEtaSec()         { return etaSec; }
    public void setEtaSec(double etaSec) { this.etaSec = etaSec; }

    public double getDistanceM()      { return distanceM; }
    public void setDistanceM(double distanceM) {
        this.prevDistanceM = this.distanceM;
        this.distanceM = distanceM;
    }

    public boolean isJoined()              { return joined; }
    public void setJoined(boolean joined)  { this.joined = joined; }

    public boolean hasJoinedAndLeft()      { return hasJoinedAndLeft; }
    public boolean isPassed()              { return passed; }
    public void setPassed(boolean v)       { this.passed = v; }
    public void setHasJoinedAndLeft(boolean v) { this.hasJoinedAndLeft = v; }

    /** SIM再起動時に距離履歴もリセットする */
    public void resetDistanceHistory() {
        this.distanceM = 0.0;
        this.prevDistanceM = -1;
        this.passed = false;
    }

    public boolean hasEdgeServer()         { return edgeServerIp != null; }
}
