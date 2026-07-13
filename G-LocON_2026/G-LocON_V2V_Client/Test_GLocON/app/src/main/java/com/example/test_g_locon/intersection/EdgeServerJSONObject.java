package com.example.test_g_locon.intersection;

import com.example.test_g_locon.main.UserInfo;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * EdgeServerへ送信するJSONを生成するクラス。
 *
 * JOIN: 交差点グループへの参加要求
 * LEAVE: 交差点グループからの離脱通知
 */
public class EdgeServerJSONObject {

    /**
     * JOIN用JSONを生成する。
     *
     * @param userInfo      自車のユーザ情報
     * @param intersectionId 参加する交差点のID
     * @param etaSec        参加時のETA（秒）。ログ・表示用。
     */
    public JSONObject buildJoin(UserInfo userInfo, String intersectionId, double etaSec) {
        JSONObject json = new JSONObject();
        try {
            json.put("processType",    "JOIN");
            json.put("intersectionId", intersectionId);
            json.put("publicIP",       userInfo.getPublicIP());
            json.put("publicPort",     userInfo.getPublicPort());
            json.put("privateIP",      userInfo.getPrivateIP());
            json.put("privatePort",    userInfo.getPrivatePort());
            json.put("latitude",       userInfo.getLatitude());
            json.put("longitude",      userInfo.getLongitude());
            json.put("peerID",         userInfo.getPeerId());
            json.put("eta",            etaSec);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return json;
    }

    /**
     * LEAVE用JSONを生成する。
     *
     * @param userInfo      自車のユーザ情報
     * @param intersectionId 離脱する交差点のID
     */
    public JSONObject buildLeave(UserInfo userInfo, String intersectionId) {
        JSONObject json = new JSONObject();
        try {
            json.put("processType",    "LEAVE");
            json.put("intersectionId", intersectionId);
            json.put("publicIP",       userInfo.getPublicIP());
            json.put("publicPort",     userInfo.getPublicPort());
            json.put("privateIP",      userInfo.getPrivateIP());
            json.put("privatePort",    userInfo.getPrivatePort());
            json.put("latitude",       userInfo.getLatitude());
            json.put("longitude",      userInfo.getLongitude());
            json.put("peerID",         userInfo.getPeerId());
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return json;
    }
}
