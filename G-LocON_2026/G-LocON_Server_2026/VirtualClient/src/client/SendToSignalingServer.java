package client;

//11/15日の成功時の変更履歴18:07らへん
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import org.json.JSONObject;

/**
 * Created by MF17037 on 2017/12/15.
 */

public class SendToSignalingServer extends Thread{
    private final static String SIGNALING_SERVER_IP = "127.0.0.1";
    final static int SIGNALING_SERVER_PORT = 55555;
    private String processType;
    private DatagramSocket socket;
    private UserInfo userInfo;
    private double searchDistance;

    SendToSignalingServer(DatagramSocket socket, String processType, UserInfo userInfo) {
        this.socket = socket;
        this.processType = processType;
        this.userInfo = userInfo;
    }

    SendToSignalingServer(DatagramSocket socket, String processType, UserInfo userInfo, double searchDistance) {
        this.socket = socket;
        this.processType = processType;
        this.userInfo = userInfo;
        this.searchDistance = searchDistance;
    }



    /**
     * シグナリングサーバにデータを送信する
     *
     * @param data 今回は使わない
     * @return
     */
    public void run(){
        final String REGISTER = "REGISTER";
        final String UPDATE = "UPDATE";
        final String SEARCH = "SEARCH";
        final String DELETE = "DELETE";

        if (processType.equals(REGISTER)) {
            ProcessJSONObject processJSONObject = new ProcessJSONObject();
            JSONObject jsonObject;
            jsonObject = processJSONObject.getUserInfoToRegister(userInfo);

            try {
                byte[] sendData = jsonObject.toString().getBytes();
                DatagramPacket sendPacket;
                sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName(SIGNALING_SERVER_IP), SIGNALING_SERVER_PORT);
                socket.send(sendPacket);
                System.out.println("REGISTER送信完了");
            } catch (IOException e) {
                e.printStackTrace();
            }


        } else if (processType.equals(UPDATE)) {
            ProcessJSONObject processJSONObject = new ProcessJSONObject();
            JSONObject jsonObject;
            jsonObject = processJSONObject.getUserInfoToUpdate(userInfo);

            try {
                byte[] sendData = jsonObject.toString().getBytes();
                DatagramPacket sendPacket;
                sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName(SIGNALING_SERVER_IP), SIGNALING_SERVER_PORT);
                socket.send(sendPacket);
                System.out.println("UPDATE送信完了");
            } catch (IOException e) {
                e.printStackTrace();
            }


        } else if (processType.equals(SEARCH)) {
            ProcessJSONObject processJSONObject = new ProcessJSONObject();
            JSONObject jsonObject;
            jsonObject = processJSONObject.getUserInfoToSearch(userInfo, searchDistance);

            try {
                byte[] sendData = jsonObject.toString().getBytes();
                DatagramPacket sendPacket;
                sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName(SIGNALING_SERVER_IP), SIGNALING_SERVER_PORT);
                socket.send(sendPacket);
                System.out.println("SEARCH送信完了");
            } catch (IOException e) {
                e.printStackTrace();
            }


        } else if (processType.equals(DELETE)) {
            ProcessJSONObject processJSONObject = new ProcessJSONObject();
            JSONObject jsonObject;
            jsonObject = processJSONObject.getUserInfoToDelete(userInfo);

            try {
                byte[] sendData = jsonObject.toString().getBytes();
                DatagramPacket sendPacket;
                sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName(SIGNALING_SERVER_IP), SIGNALING_SERVER_PORT);
                socket.send(sendPacket);
                System.out.println("DELETE送信完了");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


}
