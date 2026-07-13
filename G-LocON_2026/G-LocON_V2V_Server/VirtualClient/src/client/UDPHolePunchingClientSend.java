package client;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;


/**
 * Created by MF17037 on 2017/12/04.
 */

public class UDPHolePunchingClientSend extends Thread{
    final static String STUN_SERVER_DOMAIN = "127.0.0.1";
    final static int STUN_SERVER_PORT = 55554;
    private DatagramSocket clientSocket;
    UDPHolePunchingClientSendFinishListener udpHolePunchingClientSendFinishListener;


    UDPHolePunchingClientSend(DatagramSocket clientSocket,UDPHolePunchingClientSendFinishListener udpHolePunchingClientSendFinishListener){
        this.udpHolePunchingClientSendFinishListener = udpHolePunchingClientSendFinishListener;
        this.clientSocket = clientSocket;
    }


    public void run() {
        String sendMsg = "Hello";
        while(true) {
            System.out.println("log:send 前");
            try {
                //clientSocket.connect(InetAddress.getByName(STUN_SERVER_DOMAIN), STUN_SERVER_PORT);//いらないけど追加してみた
                byte[] sendData = sendMsg.getBytes();
                DatagramPacket sendPacket;

                sendPacket = new DatagramPacket(sendData,
                        sendData.length, InetAddress.getByName(STUN_SERVER_DOMAIN), STUN_SERVER_PORT);
                clientSocket.send(sendPacket);
                if(sendMsg.equals("Hello")){
                    sendMsg = "Ping";
                    udpHolePunchingClientSendFinishListener.onSendFinishMsgToStun();
                }
                sleep();
            } catch (Exception e) {
            	 System.out.println("loghogehoge"+e);
            }
            System.out.println("log:send 後");
        }
    }


    private void sleep(){
        try {
        	 System.out.println("hogehoge:繰り返し中");
            Thread.sleep(60000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

}
