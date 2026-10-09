package com.example.test_g_locon.P2P;

import com.example.test_g_locon.main.UserInfo;

import java.util.List;

public interface IP2P {
    // [修正 2026/10] ArrayList → List（P2P 側でスレッドセーフな CopyOnWriteArrayList を渡すため）
    void onGetDetailUserInfo(UserInfo receiveUserInfo, List<UserInfo> userInfos); //周辺ユーザからデータが送られた場合
    void onGetPeripheralUsersInfo(List<UserInfo> userInfos);//シグナリングサーバから周辺ユーザ情報を取得した場合
}
