package com.example.test_g_locon.STUNServerClient;

import android.os.AsyncTask;

import java.net.DatagramSocket;

public class STUNServerClient implements ISTUNServerClientSender, ISTUNServerClientReceiver{
    private ISTUNServerClient istunServerClient;
    private DatagramSocket socket;
    private volatile boolean receivedMsgFromStun = false; // [修正 2026/10] STUN 応答受信済みフラグ（送信スレッドから参照）

    public STUNServerClient(DatagramSocket socket,ISTUNServerClient istunServerClient){
        this.socket = socket;
        this.istunServerClient = istunServerClient;
    }


    public void stunServerClientStart(){
        STUNServerClientSender stunServerClientSender = new STUNServerClientSender(socket,this);
        stunServerClientSender.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    public void onSendFinishMsgToStun(){
        STUNServerClientReceiver stunServerClientReceiver = new STUNServerClientReceiver(socket,this);
        stunServerClientReceiver.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

    }

    @Override
    public void onReceiveMsgFromStun(String addr, int port){
        receivedMsgFromStun = true; // [修正 2026/10]
        istunServerClient.onGetGlobalIP_Port(addr,port);
    }

    // [修正 2026/10] Hello の再送を止めるために送信側から参照する
    @Override
    public boolean isReceivedMsgFromStun(){
        return receivedMsgFromStun;
    }
}
