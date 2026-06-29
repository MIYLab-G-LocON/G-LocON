package com.example.test_g_locon.P2P;

// [変更] AsyncTask → Runnable に置き換え
import android.location.Location;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

/**
 * UDPソケットでパケットを無限受信し、processTypeに応じてコールバックを呼ぶクラス。
 *
 * [変更] extends AsyncTask → implements Runnable
 *   - doInBackground() の内容を run() に移動
 *   - P2P クラスの ExecutorService から executor.execute(this) で呼び出す
 */
public class P2PReceiver implements Runnable {

    private static final String TAG = "P2PReceiver";

    // processType 文字列定数（旧実装ではローカル変数だったが、可読性のためフィールド定数に移動）
    private static final String GET_PERIPHERAL_USER  = "getPeripheralUserInfoList";
    private static final String DO_UDP_HOLE_PUNCHING = "doUDPHolePunching";
    private static final String SEND_DATA            = "SendLocation";

    private final DatagramSocket socket;
    private final IP2PReceiver iP2PReceiver;

    P2PReceiver(DatagramSocket socket, IP2PReceiver iP2PReceiver) {
        this.socket = socket;
        this.iP2PReceiver = iP2PReceiver;
    }

    /**
     * [変更] doInBackground() → run()
     * UDP受信ループの処理本体。スレッドが中断されるまで受信し続ける。
     */
    @Override
    public void run() {
        Log.d(TAG, "P2PReceiver 起動");
        DatagramPacket receivePacket = new DatagramPacket(new byte[1024], 1024);

        while (!Thread.currentThread().isInterrupted()) {
            try {
                socket.receive(receivePacket);
            } catch (IOException e) {
                Log.d(TAG, "受信エラー: " + e);
                continue;
            }

            String result = new String(receivePacket.getData(), 0, receivePacket.getLength());
            try {
                JSONObject jsonObject = new JSONObject(result);
                SignalingJSONObject signalingJSONObject = new SignalingJSONObject(jsonObject);
                String processType = signalingJSONObject.getProcessType();

                if (processType.equals(GET_PERIPHERAL_USER)) {
                    Log.d(TAG, "processType: GET_PERIPHERAL_USER");
                    iP2PReceiver.onGetPeripheralUser(signalingJSONObject.getPerioheralUsers());

                } else if (processType.equals(DO_UDP_HOLE_PUNCHING)) {
                    Log.d(TAG, "processType: DO_UDP_HOLE_PUNCHING");
                    iP2PReceiver.onDoUDPHolePunching(signalingJSONObject.getSrcUser());

                } else if (processType.equals(SEND_DATA)) {
                    P2PJSONObject p2pJSONObject = new P2PJSONObject(jsonObject);
                    Log.d(TAG, "processType: SEND_DATA, 送信元: " + receivePacket.getAddress());
                    Location location = p2pJSONObject.getPeripheralUserLocation();
                    iP2PReceiver.onGetPeripheralUserLocation(
                            p2pJSONObject.getLocationCount(),
                            receivePacket.getAddress().getHostAddress(),
                            receivePacket.getPort(),
                            location,
                            p2pJSONObject.getPeerId(),
                            p2pJSONObject.getSpeed()
                    );
                }

            } catch (JSONException e) {
                Log.d(TAG, "JSON解析エラー: " + e);
            }
        }
    }
}
