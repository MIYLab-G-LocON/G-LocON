package client;

//11/15日の成功時の変更履歴18:07らへん
import java.util.ArrayList;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Created by MF17037 on 2017/12/15.
 */

public class ProcessJSONObject {
    private JSONObject jsonObject;

    /**
     * コンストラクタ
     */
    ProcessJSONObject(){

    }


    /**
     * コンストラクタ
     * @param jsonObject//受信データ
     */
    ProcessJSONObject(JSONObject jsonObject){
        this.jsonObject = jsonObject;
    }


    /**
     * 処理タイプを受信データから取得する
     * @return//処理タイプ
     */
    public String getProcessType(){
        String processType = "";
        try {
            processType = jsonObject.getString("processType");
            System.out.println("Log.d:ProcessJSONObject:called getProcessType:取得したprocessTypeは"+processType);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return processType;
    }


    public ArrayList<UserInfo> getPerioheralUserInfos(){
        ArrayList<UserInfo> peripheralUserInfos = new ArrayList<>();
        JSONArray getArray = null;
        try {
            System.out.println("Log.d:ProcessJSONObject:called getPerioheralUserInfos\n"+jsonObject.toString(4));
            getArray = jsonObject.getJSONArray("userList");
            for(int i = 0; i < getArray.length(); i++) {
                UserInfo peripheralUser = new UserInfo();
                JSONObject obj = getArray.getJSONObject(i);
                peripheralUser.setPublicIP(obj.getString("publicIP"));
                peripheralUser.setPublicPort(obj.getInt("publicPort"));
                peripheralUser.setPrivateIP(obj.getString("privateIP"));
                peripheralUser.setPrivatePort(obj.getInt("privatePort"));
                peripheralUser.setLatitude(obj.getDouble("latitude"));
                peripheralUser.setLongitude(obj.getDouble("longitude"));
                peripheralUser.setPeerID(obj.getString("peerID"));
                //peripheralUser.setSpeed(obj.getDouble("speed"));
                peripheralUserInfos.add(peripheralUser);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return peripheralUserInfos;
    }


    public UserInfo getSrcUserInfo(){
        UserInfo srcUserInfo = new UserInfo();
        try {
            srcUserInfo.setPublicIP(jsonObject.getString("publicIP"));
            srcUserInfo.setPublicPort(jsonObject.getInt("publicPort"));
            srcUserInfo.setPrivateIP(jsonObject.getString("privateIP"));
            srcUserInfo.setPrivatePort(jsonObject.getInt("privatePort"));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return  srcUserInfo;
    }


    public String getP2PMsg(){
        try {
            System.out.println("Log.d:ProcessJSONObject:called getP2PMsg\n"+jsonObject.toString(4));
            return jsonObject.getString("msg");//hellopacket送信時は、msgを設定していないためexceptionが働く
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return "miss";
    }


    public int getDelayExperimentLocationCount(){
        try {
            return jsonObject.getInt("locationUpdateCount");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return 0;
    }


    /**
     * シグナリングサーバにUserInfoを登録する
     * @param userInfo ユーザ情報
     * @return userInfoをJSONにしたもの
     */
    public JSONObject getUserInfoToRegister(UserInfo userInfo){
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("processType","REGISTER");
            jsonObject.put("publicIP",userInfo.getPublicIP());
            jsonObject.put("publicPort",userInfo.getPublicPort());
            jsonObject.put("privateIP",userInfo.getPrivateIP());
            jsonObject.put("privatePort",userInfo.getPrivatePort());
            jsonObject.put("latitude",userInfo.getLatitude());
            jsonObject.put("longitude",userInfo.getLongitude());
            jsonObject.put("peerID",userInfo.getPeerID());
            //jsonObject.put("speed",userInfo.getSpeed());
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return jsonObject;
    }


    /**
     * シグナリングサーバにUserInfoを更新する
     * @param userInfo ユーザ情報
     * @return userInfoをJSONにしたもの
     */
    public JSONObject getUserInfoToUpdate(UserInfo userInfo){
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("processType","UPDATE");
            jsonObject.put("publicIP",userInfo.getPublicIP());
            jsonObject.put("publicPort",userInfo.getPublicPort());
            jsonObject.put("privateIP",userInfo.getPrivateIP());
            jsonObject.put("privatePort",userInfo.getPrivatePort());
            jsonObject.put("latitude",userInfo.getLatitude());
            jsonObject.put("longitude",userInfo.getLongitude());
            jsonObject.put("peerID",userInfo.getPeerID());
            //jsonObject.put("speed",userInfo.getSpeed());
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return jsonObject;
    }


    /**
     * シグナリングサーバに検索を問い合わせる
     * @param userInfo ユーザ情報
     * @return userInfoとdistanceをJSONにしたもの
     */
    public JSONObject getUserInfoToSearch(UserInfo userInfo, double distance){
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("processType","SEARCH");
            jsonObject.put("publicIP",userInfo.getPublicIP());
            jsonObject.put("publicPort",userInfo.getPublicPort());
            jsonObject.put("privateIP",userInfo.getPrivateIP());
            jsonObject.put("privatePort",userInfo.getPrivatePort());
            jsonObject.put("latitude",userInfo.getLatitude());
            jsonObject.put("longitude",userInfo.getLongitude());
            jsonObject.put("peerID",userInfo.getPeerID());
            //jsonObject.put("speed",userInfo.getSpeed());
            jsonObject.put("searchDistance",distance);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return jsonObject;
    }


    /**
     * シグナリングサーバにUserInfoを破棄させる
     * @param userInfo ユーザ情報
     * @return userInfoをJSONにしたもの
     */
    public JSONObject getUserInfoToDelete(UserInfo userInfo){
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("processType","DELETE");
            jsonObject.put("publicIP",userInfo.getPublicIP());
            jsonObject.put("publicPort",userInfo.getPublicPort());
            jsonObject.put("privateIP",userInfo.getPrivateIP());
            jsonObject.put("privatePort",userInfo.getPrivatePort());
            jsonObject.put("latitude",userInfo.getLatitude());
            jsonObject.put("longitude",userInfo.getLongitude());
            jsonObject.put("peerID",userInfo.getPeerID());
            //jsonObject.put("speed",userInfo.getSpeed());
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return jsonObject;
    }

}
