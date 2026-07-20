package client;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

/**
 * Created by MF17037 on 2017/12/04.
 */

public class UDPHolePunchingClientReceive extends Thread{
    private UDPHolePunchingClientReceiveListener udpHolePunchingClientReceiveListener;
    private DatagramSocket clientSocket;

    UDPHolePunchingClientReceive(DatagramSocket clientSocket, UDPHolePunchingClientReceiveListener udpHolePunchingClientReceiveListener) {
        this.clientSocket = clientSocket;
        this.udpHolePunchingClientReceiveListener = udpHolePunchingClientReceiveListener;
    }


    public void run() {
        // receive Data
        DatagramPacket receivePacket = new DatagramPacket(new byte[128], 128);
        String addr;
        int port;
        //while (true) {
            try {
            	System.out.println("現在の接続先:" + clientSocket.getInetAddress());
            	System.out.println("現在のローカルポート:" + clientSocket.getLocalPort());
                clientSocket.receive(receivePacket);
                String allData = new String(receivePacket.getData(), 0, receivePacket.getLength());
                System.out.println("UDP_HOLE_PUNCHING:"+allData);
                String result[] = allData.split("-", 0);
                addr = result[0];
                port = Integer.parseInt(result[1]);
                udpHolePunchingClientReceiveListener.onReceiveMsgFromStun(addr, port);
            } catch (Exception e) {
            	System.out.println("hogehoge:変換で失敗" + e);
            }
    }

}
