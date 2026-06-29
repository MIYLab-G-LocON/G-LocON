package client;

import java.net.DatagramSocket;

/**
 * Created by MF17037 on 2017/12/05.
 */

public class UDPHolePunchingClient implements UDPHolePunchingClientSendFinishListener,UDPHolePunchingClientReceiveListener{
    private UDPHolePunchingFinish udpHolePunchingFinish;
    private DatagramSocket clientSocket;


    UDPHolePunchingClient(DatagramSocket clientSocket,UDPHolePunchingFinish udpHolePunchingFinish){
        this.udpHolePunchingFinish = udpHolePunchingFinish;
        this.clientSocket = clientSocket;
    }


    public void udpHolePunchingStart(){
        UDPHolePunchingClientSend udpHolePunchingClientSend = new UDPHolePunchingClientSend(clientSocket,this);
        udpHolePunchingClientSend.start();
    }


    @Override
    public void onSendFinishMsgToStun(){
        UDPHolePunchingClientReceive udpHolePunchingClientReceive = new UDPHolePunchingClientReceive(clientSocket,this);
        udpHolePunchingClientReceive.start();
    }

    @Override
    public void onReceiveMsgFromStun(String addr, int port){
        udpHolePunchingFinish.onUDPHolePunchingFinish(addr,port);
    }

}