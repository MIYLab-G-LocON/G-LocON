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
                if(sendMsg.equals("Hello")){
                    sendMsg = "Ping";
                    istunServerClientSender.onSendFinishMsgToStun();
                }
                sleep();
            } catch (Exception e) {
                Log.d("STUN_DEBUG", "エラー: " + e);
            }
        }
    }

    @Override
    protected void onPostExecute(Integer result) {

    }


    private void sleep(){
        try {
            Thread.sleep(60000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
