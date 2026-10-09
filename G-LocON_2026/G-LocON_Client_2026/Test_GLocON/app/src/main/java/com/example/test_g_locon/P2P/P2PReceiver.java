package com.example.test_g_locon.P2P;

import android.location.Location;
import android.os.AsyncTask;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class P2PReceiver extends AsyncTask<String, String, Void> {
    private DatagramSocket socket;
    private IP2PReceiver iP2PReceiver;

    P2PReceiver(DatagramSocket socket,IP2PReceiver iP2PReceiver){
        this.socket = socket;
        this.iP2PReceiver = iP2PReceiver;
    }

    @Override
    protected Void doInBackground(String... text) {
        Log.d("Receive", "Recieveクラスまで来た");
        final String GET_PERIPHERAL_USER = "getPeripheralUserInfoList";
        final String RECEIVE_MSG_PEER = "peermsg";
        final String DO_UDP_HOLE_PUNCHING = "doUDPHolePunching";
        final String SEND_DATA = "SendLocation";
        final String ACK = "Ack";
        // [修正 2026/10] 1024 バイトでは周辺ユーザ数人分の userList で切り詰められ JSON 解析に失敗するため UDP 最大長に拡大
        final int RECEIVE_BUFFER_SIZE = 65507;
        DatagramPacket receivePacket = new DatagramPacket(new byte[RECEIVE_BUFFER_SIZE], RECEIVE_BUFFER_SIZE);
        do {


            Log.d("P2P", "P2Pレシーブ起動直前");
            try{
                receivePacket.setLength(RECEIVE_BUFFER_SIZE); // [修正 2026/10] 再利用するパケットの受信長を毎回戻す
                socket.receive(receivePacket);
            } catch (IOException e) {
                Log.d("P2P", "エラー:"+e);
                continue; // [修正 2026/10] 受信失敗時に前回のデータを再処理しない
            }
            String result = new String(receivePacket.getData(), 0, receivePacket.getLength());
            try{
                JSONObject jsonObject = new JSONObject(result);

                SignalingJSONObject signalingJSONObject = new SignalingJSONObject(jsonObject);
                String processType = signalingJSONObject.getProcessType();
                //Log.d("Receive","送信元IP:"+receivePacket.getAddress());


                if(processType.equals(GET_PERIPHERAL_USER)){
                    System.out.println("processType-----GET_PERIPHERAL_USER");
                    iP2PReceiver.onGetPeripheralUser(signalingJSONObject.getPerioheralUsers());
                }

                else if(processType.equals(DO_UDP_HOLE_PUNCHING)){
                    System.out.println("processType-----DO_UDP_HOLE_PUNCHING");
                    iP2PReceiver.onDoUDPHolePunching(signalingJSONObject.getSrcUser());
                }

                else if(processType.equals(SEND_DATA)) {
                    P2PJSONObject p2pJSONObject = new P2PJSONObject(jsonObject);
                    Log.d("Receive", "送信元:" + receivePacket.getAddress());
                    System.out.println("SEND_DATA");
                    Location location = p2pJSONObject.getPeripheralUserLocation();
                    iP2PReceiver.onGetPeripheralUserLocation(p2pJSONObject.getLocationCount(), receivePacket.getAddress().getHostAddress(), receivePacket.getPort(), location, p2pJSONObject.getPeerId(), p2pJSONObject.getSpeed());

                }
                /*
                else if(processType.equals(ACK)){
                    P2PJSONObject p2pJSONObject = new P2PJSONObject(jsonObject);
                        Log.d("Receive", "送信元:" + receivePacket.getAddress());
                        iP2PReceiver.onGetAck(p2pJSONObject.getLocationCount(), receivePacket.getAddress().getHostAddress(), receivePacket.getPort());
                }
                */

            } catch (JSONException e) {
                Log.d("P2P", "エラー:"+e);
            }

        }while (true);
    }

    /**
     * 完了処理
     */
    @Override
    protected void onPostExecute(Void a) {

    }
}
