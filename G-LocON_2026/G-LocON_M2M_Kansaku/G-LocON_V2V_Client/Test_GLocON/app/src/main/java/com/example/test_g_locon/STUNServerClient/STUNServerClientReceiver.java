package com.example.test_g_locon.STUNServerClient;

// [変更] AsyncTask → Runnable に置き換え
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

/**
 * STUNサーバから "IP-Port" 形式の応答を1回受信し、コールバックを呼ぶクラス。
 *
 * [変更] extends AsyncTask → implements Runnable
 *   - doInBackground() の内容を run() に移動
 *   - STUNServerClient の ExecutorService から executor.execute(this) で呼び出す
 */
public class STUNServerClientReceiver implements Runnable {

    private static final String TAG = "STUNReceiver";

    private final DatagramSocket socket;
    private final ISTUNServerClientReceiver callback;

    STUNServerClientReceiver(DatagramSocket socket, ISTUNServerClientReceiver callback) {
        this.socket = socket;
        this.callback = callback;
    }

    /**
     * [変更] doInBackground() → run()
     * STUNサーバからの応答を受信してグローバルIP・Portを取り出す。
     */
    @Override
    public void run() {
        DatagramPacket receivePacket = new DatagramPacket(new byte[128], 128);
        try {
            socket.receive(receivePacket);
            String allData = new String(receivePacket.getData(), 0, receivePacket.getLength());
            Log.d(TAG, "受信データ: " + allData);
            String[] parts = allData.split("-", 2);
            String addr = parts[0];
            int port = Integer.parseInt(parts[1]);
            callback.onReceiveMsgFromStun(addr, port);
        } catch (Exception e) {
            Log.d(TAG, "受信エラー: " + e);
        }
    }
}
