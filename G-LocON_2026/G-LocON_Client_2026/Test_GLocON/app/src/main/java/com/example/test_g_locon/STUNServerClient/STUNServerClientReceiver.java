package com.example.test_g_locon.STUNServerClient;

import android.os.AsyncTask;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class STUNServerClientReceiver extends AsyncTask<String, String, Integer> {
    private ISTUNServerClientReceiver istunServerClientReceiver;
    private DatagramSocket socket;

    STUNServerClientReceiver(DatagramSocket socket, ISTUNServerClientReceiver istunServerClientReceiver) {
        this.socket = socket;
        this.istunServerClientReceiver = istunServerClientReceiver;
    }

    @Override
    protected void onPreExecute() {
    }

    @Override
    protected Integer doInBackground(String... text) {
        // receive Data
        DatagramPacket receivePacket = new DatagramPacket(new byte[128], 128);
        String addr;
        int port;
        // [修正 2026/10] 想定外のデータを受信した場合も終了せず、正しい応答（"IP-PORT"）を受信するまで待ち続ける
        while (true) {
            try {
                receivePacket.setLength(128);
                socket.receive(receivePacket);
                String allData = new String(receivePacket.getData(), 0, receivePacket.getLength());
                Log.d("UDP_HOLE_PUNCHING", allData);
                String result[] = allData.split("-", 0);
                addr = result[0];
                port = Integer.parseInt(result[1]);

            } catch (Exception e) {
                Log.d("hogehoge", "変換で失敗" + e);
                continue;
            }
            istunServerClientReceiver.onReceiveMsgFromStun(addr, port);
            break;
        }
        return 0;
    }

    @Override
    protected void onPostExecute(Integer result) {
    }
}
