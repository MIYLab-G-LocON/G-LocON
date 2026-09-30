package com.example.test_g_locon.sim;

/**
 * [SUMOモード] SimBridge から届く周りの車1台分（SIM_VEHICLES）。
 * 「表示:全車両」で，P2Pでつながっていない車も含めて地図に出すために使う。
 */
public class SimVehicle {
    /** 実機が乗っている車ならその端末の peerID，それ以外は "sim-<車両ID>"（仮想クライアントと同じ） */
    public final String peerId;
    public final double lat;
    public final double lon;
    /** 進行方向（北=0の時計回り） */
    public final float bearing;
    /** 実機が乗っている車か */
    public final boolean isPhone;

    public SimVehicle(String peerId, double lat, double lon, float bearing, boolean isPhone) {
        this.peerId = peerId;
        this.lat = lat;
        this.lon = lon;
        this.bearing = bearing;
        this.isPhone = isPhone;
    }
}
