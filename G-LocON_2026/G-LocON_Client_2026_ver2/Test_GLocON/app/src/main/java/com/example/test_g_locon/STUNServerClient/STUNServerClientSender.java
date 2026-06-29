package com.example.test_g_locon.STUNServerClient;

// [変更] AsyncTask → Runnable に置き換え
import android.util.Log;

import com.example.test_g_locon.main.UtilCommon;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * STUNサーバへ最初に "Hello" を送り、その後60秒ごとに "Ping" を送り続けるクラス。
 *
 * [変更] extends AsyncTask → implements Runnable
 *   - doInBackground() の内容を run() に移動
 *   - STUNServerClient の ExecutorService から executor.execute(this) で呼び出す
 */
public class STUNServerClientSender implements Runnable {

    private static final String TAG = "STUNSender";
    private static final long PING_INTERVAL_MS = 60_000L; // 60秒ごとにPing

    private final DatagramSocket socket;
    private final ISTUNServerClientSender callback;

    STUNServerClientSender(DatagramSocket socket, ISTUNServerClientSender callback) {
        this.socket = socket;
        this.callback = callback;
    }

    /**
     * [変更] doInBackground() → run()
     * "Hello" 送信後に Receiver を起動し、以降は "Ping" を60秒ごとに送り続ける。
     */
    @Override
    public void run() {
        UtilCommon utilCommon = (UtilCommon) UtilCommon.getAppContext();
        String stunServerIP = utilCommon.getStunServerIP();
        int stunServerPort = utilCommon.getStunServerPort();

        boolean helloSent = false;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String sendMsg = helloSent ? "Ping" : "Hello";
                byte[] sendData = sendMsg.getBytes();
                DatagramPacket sendPacket = new DatagramPacket(
                        sendData, sendData.length,
                        InetAddress.getByName(stunServerIP), stunServerPort
                );
                socket.send(sendPacket);

                if (!helloSent) {
                    helloSent = true;
                    // Hello 送信直後に Receiver を起動してグローバルIP・Portを取得する
                    callback.onSendFinishMsgToStun();
                }

                Thread.sleep(PING_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.d(TAG, "送信エラー: " + e);
            }
        }
    }
}
