package com.example.test_g_locon.STUNServerClient;

import android.os.AsyncTask;
import android.util.Log;

import com.example.test_g_locon.main.UtilCommon;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class STUNServerClientSender extends AsyncTask<String, String, Integer> {
    private DatagramSocket socket;
    ISTUNServerClientSender istunServerClientSender;
    // [修正 2026/10] STUNサーバから返信を受け取ったかどうか（受信スレッドから書き込まれる）
    private volatile boolean receivedReply = false;
    private boolean receiverStarted = false;

    STUNServerClientSender(DatagramSocket socket,ISTUNServerClientSender istunServerClientSender){
        this.socket = socket;
        this.istunServerClientSender = istunServerClientSender;
    }

    @Override
    protected void onPreExecute() {
    }

    // [修正 2026/10] STUNサーバから返信を受け取ったら呼ぶ（Helloの再送を止める）
    void onReceivedReply() {
        receivedReply = true;
    }

    @Override
    protected Integer doInBackground(String... ttt) {
        String sendMsg = "Hello";
        UtilCommon utilCommon = (UtilCommon)UtilCommon.getAppContext();
        String stunServerIP = utilCommon.getStunServerIP();
        int stunServerPort = utilCommon.getStunServerPort();
        Log.d("STUN_DEBUG", "STUNServerClientSender開始: " + stunServerIP + ":" + stunServerPort);
        while(true) {
            try {
                byte[] sendData = sendMsg.getBytes();
                DatagramPacket sendPacket;
                sendPacket = new DatagramPacket(sendData,
                        sendData.length, InetAddress.getByName(stunServerIP), stunServerPort);
                Log.d("STUN_DEBUG", "送信前: " + sendMsg + " → " + stunServerIP + ":" + stunServerPort);
                socket.send(sendPacket);
                Log.d("STUN_DEBUG", "送信完了: " + sendMsg);
                // [修正 2026/10] Helloの返信が失われると先に進めないため，返信が来るまで2秒ごとにHelloを再送する
                if(sendMsg.equals("Hello")){
                    if (!receiverStarted) {
                        receiverStarted = true;
                        istunServerClientSender.onSendFinishMsgToStun();
                    }
                    if (waitReply(2000)) {
                        sendMsg = "Ping";
                    } else {
                        continue;
                    }
                }
                sleep();
            } catch (Exception e) {
                Log.d("STUN_DEBUG", "エラー: " + e);
                // [修正 2026/10] 送信失敗時に間隔を空けずに再試行し続けるのを防ぐ
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    ie.printStackTrace();
                }
            }
        }
    }

    @Override
    protected void onPostExecute(Integer result) {

    }


    // [修正 2026/10] 最大timeoutミリ秒，返信を待つ。返信があればtrue
    private boolean waitReply(long timeout) {
        long end = System.currentTimeMillis() + timeout;
        while (!receivedReply && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return receivedReply;
    }

    private void sleep(){
        try {
            Thread.sleep(60000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
