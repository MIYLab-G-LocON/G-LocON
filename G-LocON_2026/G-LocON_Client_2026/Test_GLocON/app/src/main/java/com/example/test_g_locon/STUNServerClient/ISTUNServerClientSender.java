package com.example.test_g_locon.STUNServerClient;

public interface ISTUNServerClientSender {
    void onSendFinishMsgToStun();
    boolean isReceivedMsgFromStun(); // [修正 2026/10] STUN サーバから応答を受信済みか（Hello 再送の判定用）
}
