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
    final private static long HELLO_RETRY_INTERVAL = 2000; // [修正 2026/10] Hello 再送間隔(ms)

    STUNServerClientSender(DatagramSocket socket,ISTUNServerClientSender istunServerClientSender){
        this.socket = socket;
        this.istunServerClientSender = istunServerClientSender;
    }

    @Override
    protected void onPreExecute() {
    }

    @Override
    protected Integer doInBackground(String... ttt) {
        String sendMsg = "Hello";
        UtilCommon utilCommon = (UtilCommon)UtilCommon.getAppContext();
        String stunServerIP = utilCommon.getStunServerIP();
        int stunServerPort = utilCommon.getStunServerPort();
        boolean receiverStarted = false; // [修正 2026/10]
        while(true) {
            try {
                byte[] sendData = sendMsg.getBytes();
                DatagramPacket sendPacket;
                sendPacket = new DatagramPacket(sendData,
                        sendData.length, InetAddress.getByName(stunServerIP), stunServerPort);
                socket.send(sendPacket);
                if(sendMsg.equals("Hello")){
                    // [修正 2026/10] 応答（UDP）が失われると止まったままになるため、応答を受信するまで Hello を一定間隔で再送する
                    //   受信側（STUNServerClientReceiver）の起動は最初の 1 回だけ
                    if (!receiverStarted) {
                        receiverStarted = true;
                        istunServerClientSender.onSendFinishMsgToStun();
                    }
                    sleep(HELLO_RETRY_INTERVAL);
                    if (istunServerClientSender.isReceivedMsgFromStun()) {
                        sendMsg = "Ping";
                        sleep();
                    }
                    continue;
                }
                sleep();
            } catch (Exception e) {
                Log.d("loghogehoge", "" + e);
                sleep(HELLO_RETRY_INTERVAL); // [修正 2026/10] 送信失敗時に待たずにループし続けないようにする
            }
        }
    }

    @Override
    protected void onPostExecute(Integer result) {

    }


    private void sleep(){
        sleep(60000); // [修正 2026/10] 待ち時間指定版に委譲
    }

    // [修正 2026/10] 待ち時間指定版（Hello 再送用）
    private void sleep(long millis){
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
