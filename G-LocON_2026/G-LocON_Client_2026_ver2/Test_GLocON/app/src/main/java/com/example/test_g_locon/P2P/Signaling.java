package com.example.test_g_locon.P2P;

// [変更] AsyncTask → Runnable に置き換え
// AsyncTask は API 30 で非推奨になったため、Executor + Runnable で代替する
import com.example.test_g_locon.main.UserInfo;
import com.example.test_g_locon.main.UtilCommon;

import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * シグナリングサーバへのUDP送信を行うクラス。
 *
 * [変更] extends AsyncTask → implements Runnable
 *   - doInBackground() の内容を run() に移動
 *   - onPreExecute() / onPostExecute() は不要なので削除
 *   - P2P クラスの ExecutorService から executor.execute(this) で呼び出す
 *
 * [変更] switch-case の各ブランチでほぼ同一のsend処理が繰り返されていた（DRY違反）
 *   → sendPacket() ヘルパーメソッドに共通化
 */
public class Signaling implements Runnable {

    private final DatagramSocket socket;
    private final UserInfo userInfo;
    private final ESignalingProcess eSignalingProcess;
    private final double searchDistance;

    Signaling(DatagramSocket socket, UserInfo userInfo, ESignalingProcess eSignalingProcess) {
        this.socket = socket;
        this.userInfo = userInfo;
        this.eSignalingProcess = eSignalingProcess;
        this.searchDistance = 0;
    }

    Signaling(DatagramSocket socket, UserInfo userInfo, double searchDistance, ESignalingProcess eSignalingProcess) {
        this.socket = socket;
        this.userInfo = userInfo;
        this.searchDistance = searchDistance;
        this.eSignalingProcess = eSignalingProcess;
    }

    /**
     * [変更] doInBackground() → run()
     * シグナリングサーバにJSONを送信する処理本体。
     */
    @Override
    public void run() {
        UtilCommon utilCommon = (UtilCommon) UtilCommon.getAppContext();
        SignalingJSONObject signalingJSONObject = new SignalingJSONObject();
        JSONObject jsonObject;

        // [変更] 各 case で重複していた送信処理を sendPacket() に共通化
        switch (eSignalingProcess) {
            case REGISTER:
                jsonObject = signalingJSONObject.covUserInfoForRegister(userInfo);
                sendPacket(jsonObject, utilCommon);
                System.out.println("REGISTER送信完了");
                break;

            case UPDATE:
                jsonObject = signalingJSONObject.covUserInfoForUpdate(userInfo);
                sendPacket(jsonObject, utilCommon);
                System.out.println("UPDATE送信完了");
                break;

            case SEARCH:
                jsonObject = signalingJSONObject.covUserInfoForSearch(userInfo, searchDistance);
                sendPacket(jsonObject, utilCommon);
                System.out.println("SEARCH送信完了");
                break;

            case DELETE:
                jsonObject = signalingJSONObject.covUserInfoForDelete(userInfo);
                sendPacket(jsonObject, utilCommon);
                System.out.println("DELETE送信完了");
                break;

            default:
                break;
        }
    }

    /**
     * [変更] 旧実装では各 case に同じ try-catch+send が重複して書かれていた。
     * このヘルパーメソッドに集約することでコードを簡潔にした。
     */
    private void sendPacket(JSONObject jsonObject, UtilCommon utilCommon) {
        try {
            byte[] sendData = jsonObject.toString().getBytes();
            DatagramPacket sendPacket = new DatagramPacket(
                    sendData, sendData.length,
                    InetAddress.getByName(utilCommon.getSignalingServerIP()),
                    utilCommon.getSignalingServerPort()
            );
            socket.send(sendPacket);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
