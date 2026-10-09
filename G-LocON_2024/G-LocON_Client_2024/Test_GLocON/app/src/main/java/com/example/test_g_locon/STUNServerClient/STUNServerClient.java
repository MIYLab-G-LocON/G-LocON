package com.example.test_g_locon.STUNServerClient;

import android.os.AsyncTask;

import java.net.DatagramSocket;
import java.util.concurrent.Executors;

public class STUNServerClient implements ISTUNServerClientSender, ISTUNServerClientReceiver{
    private ISTUNServerClient istunServerClient;
    private DatagramSocket socket;
    private STUNServerClientSender stunServerClientSender; // [修正 2026/10] 返信受信を通知するため保持

    public STUNServerClient(DatagramSocket socket,ISTUNServerClient istunServerClient){
        this.socket = socket;
        this.istunServerClient = istunServerClient;
    }


    public void stunServerClientStart(){
        stunServerClientSender = new STUNServerClientSender(socket,this);
        // [修正 2026/10] 無限ループの送信処理は専用スレッドで実行する（共有スレッドプールを占有してREGISTER等が動かなくなるのを防ぐ）
        stunServerClientSender.executeOnExecutor(Executors.newSingleThreadExecutor());
    }

    @Override
    public void onSendFinishMsgToStun(){
        STUNServerClientReceiver stunServerClientReceiver = new STUNServerClientReceiver(socket,this);
        // [修正 2026/10] 返信待ちでブロックするため専用スレッドで実行する
        stunServerClientReceiver.executeOnExecutor(Executors.newSingleThreadExecutor());

    }

    @Override
    public void onReceiveMsgFromStun(String addr, int port){
        stunServerClientSender.onReceivedReply(); // [修正 2026/10] Helloの再送を止める
        istunServerClient.onGetGlobalIP_Port(addr,port);
    }
}
