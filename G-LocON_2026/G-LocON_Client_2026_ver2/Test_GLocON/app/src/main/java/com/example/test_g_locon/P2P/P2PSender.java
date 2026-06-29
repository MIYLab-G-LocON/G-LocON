package com.example.test_g_locon.P2P;

// [変更] AsyncTask → Runnable に置き換え
import android.util.Log;

import com.example.test_g_locon.main.UserInfo;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;

/**
 * 周辺ユーザへ自端末の位置情報をUDP送信するクラス。
 *
 * [変更] extends AsyncTask → implements Runnable
 *   - doInBackground() の内容を run() に移動
 *   - P2P クラスの ExecutorService から executor.execute(this) で呼び出す
 */
public class P2PSender implements Runnable {

    private static final String TAG = "P2PSender";

    private final DatagramSocket socket;
    private final int locationUpdateCount;
    private final UserInfo myUserInfo;
    private final ArrayList<UserInfo> peripheralUsers;
    private final EP2PProcess eP2PProcess;

    P2PSender(DatagramSocket socket, int locationUpdateCount, UserInfo myUserInfo,
              ArrayList<UserInfo> peripheralUsers, EP2PProcess eP2PProcess) {
        this.socket = socket;
        this.locationUpdateCount = locationUpdateCount;
        this.myUserInfo = myUserInfo;
        this.peripheralUsers = peripheralUsers;
        this.eP2PProcess = eP2PProcess;
    }

    /**
     * [変更] doInBackground() → run()
     * 周辺ユーザ全員に SendLocation パケットを送信する処理本体。
     */
    @Override
    public void run() {
        if (!EP2PProcess.SendLocation.equals(eP2PProcess)) return;

        Log.d(TAG, "現在の周辺ピア数: " + peripheralUsers.size());
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("processType", "SendLocation");
            jsonObject.put("locationUpdateCount", locationUpdateCount);
            jsonObject.put("latitude", myUserInfo.getLatitude());
            jsonObject.put("longitude", myUserInfo.getLongitude());
            jsonObject.put("peerID", myUserInfo.getPeerId());
            jsonObject.put("speed", myUserInfo.getSpeed());
            byte[] sendData = jsonObject.toString().getBytes();

            for (UserInfo peer : peripheralUsers) {
                DatagramPacket sendPacket;
                // 同NAT内の端末にはプライベートIP・Portを使用
                if (myUserInfo.getPublicIP().equals(peer.getPublicIP())) {
                    sendPacket = new DatagramPacket(sendData, sendData.length,
                            InetAddress.getByName(peer.getPrivateIP()), peer.getPrivatePort());
                } else {
                    // 異なるNATの端末にはパブリックIP・Portを使用（NATホールパンチング）
                    Log.d(TAG, "宛先IP: " + peer.getPublicIP() + " Port: " + peer.getPublicPort());
                    sendPacket = new DatagramPacket(sendData, sendData.length,
                            InetAddress.getByName(peer.getPublicIP()), peer.getPublicPort());
                }
                socket.send(sendPacket);
            }
            Log.d(TAG, "SendLocation 送信完了");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
