package com.example.test_g_locon.STUNServerClient;

public interface ISTUNServerClientReceiver {
    void onReceiveMsgFromStun(String addr,int port);
}
