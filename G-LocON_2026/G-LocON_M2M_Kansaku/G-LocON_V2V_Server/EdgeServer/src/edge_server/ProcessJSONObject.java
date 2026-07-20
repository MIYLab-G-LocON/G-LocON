package edge_server;

import java.util.ArrayList;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * EdgeServerで扱うJSONの解析・生成を担当するクラス。
 *
 * 受信: JOIN / LEAVE / SEARCH
 * 送信: getPeripheralUserInfoList（メンバー一覧）/ doUDPHolePunching（NATホールパンチング）
 */
public class ProcessJSONObject {

    private JSONObject jsonObject;

    public ProcessJSONObject() {}

    public ProcessJSONObject(JSONObject jsonObject) {
        this.jsonObject = jsonObject;
    }

    public String getProcessType() {
        try {
            return jsonObject.getString("processType");
        } catch (JSONException e) {
            e.printStackTrace();
            return "";
        }
    }

    public String getIntersectionId() {
        try {
            return jsonObject.getString("intersectionId");
        } catch (JSONException e) {
            e.printStackTrace();
            return "";
        }
    }

    /** JOIN/LEAVE/SEARCHに含まれるユーザ情報（eta含む）を取得する */
    public UserInfo getUserInfo() {
        UserInfo userInfo = new UserInfo();
        try {
            userInfo.setPublicIP(jsonObject.getString("publicIP"));
            userInfo.setPublicPort(jsonObject.getInt("publicPort"));
            userInfo.setPrivateIP(jsonObject.getString("privateIP"));
            userInfo.setPrivatePort(jsonObject.getInt("privatePort"));
            userInfo.setLatitude(jsonObject.getDouble("latitude"));
            userInfo.setLongitude(jsonObject.getDouble("longitude"));
            userInfo.setPeerId(jsonObject.getString("peerID"));
            userInfo.setEta(jsonObject.optDouble("eta", 0.0));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return userInfo;
    }

    /** メンバー一覧をJSONに変換して返す（REPLY_RESULT用） */
    public JSONObject getUserInfoList(ArrayList<UserInfo> members) {
        JSONObject json = new JSONObject();
        JSONArray userList = new JSONArray();
        for (UserInfo m : members) {
            JSONObject user = new JSONObject();
            try {
                user.put("publicIP",    m.getPublicIP());
                user.put("publicPort",  m.getPublicPort());
                user.put("privateIP",   m.getPrivateIP());
                user.put("privatePort", m.getPrivatePort());
                user.put("latitude",    m.getLatitude());
                user.put("longitude",   m.getLongitude());
                user.put("peerID",      m.getPeerId());
                user.put("eta",         m.getEta());
                userList.put(user);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        try {
            json.put("processType", "getPeripheralUserInfoList");
            json.put("userList", userList);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return json;
    }

    /** NATホールパンチング通知用JSON（NAT_REGISTER用） */
    public JSONObject getSrcUserInfo(UserInfo userInfo) {
        JSONObject json = new JSONObject();
        try {
            json.put("processType", "doUDPHolePunching");
            json.put("publicIP",    userInfo.getPublicIP());
            json.put("publicPort",  userInfo.getPublicPort());
            json.put("privateIP",   userInfo.getPrivateIP());
            json.put("privatePort", userInfo.getPrivatePort());
            json.put("latitude",    userInfo.getLatitude());
            json.put("longitude",   userInfo.getLongitude());
            json.put("peerID",      userInfo.getPeerId());
            json.put("eta",         userInfo.getEta());
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return json;
    }
}
