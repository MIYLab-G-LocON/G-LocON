package com.example.test_g_locon.navigation;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 危険情報（急停止など）。同じ交差点グループの車へ，位置情報（SendLocation）の "hazard" として P2P で送る。
 *
 *   {id, intersectionId, active, latitude, longitude, bearing}
 *   active=false は解消の通知。
 */
public class HazardInfo {
    public final String id;
    public final String intersectionId;
    public final boolean active;
    public final double lat;
    public final double lon;
    /** 危険車両の向き（北=0の時計回り） */
    public final double bearing;
    /** 送ってきた車（受信時のみ） */
    public final String peerId;

    public HazardInfo(String id, String intersectionId, boolean active,
                      double lat, double lon, double bearing, String peerId) {
        this.id = id;
        this.intersectionId = intersectionId;
        this.active = active;
        this.lat = lat;
        this.lon = lon;
        this.bearing = bearing;
        this.peerId = peerId;
    }

    /** 受信した SendLocation の "hazard" から作る */
    public static HazardInfo fromJson(JSONObject h, String peerId) throws JSONException {
        return new HazardInfo(h.optString("id", "?"), h.optString("intersectionId", ""),
                h.optBoolean("active", true),
                h.optDouble("latitude", 0), h.optDouble("longitude", 0), h.optDouble("bearing", 0), peerId);
    }

    /** 送信する SendLocation に付ける "hazard"（位置と向きは送信時の自車のもの） */
    public JSONObject toJson(double myLat, double myLon, double myBearing) throws JSONException {
        JSONObject h = new JSONObject();
        h.put("id", id);
        h.put("intersectionId", intersectionId);
        h.put("active", active);
        h.put("latitude", myLat);
        h.put("longitude", myLon);
        h.put("bearing", myBearing);
        h.put("sentWall", System.currentTimeMillis() / 1000.0);
        return h;
    }
}
