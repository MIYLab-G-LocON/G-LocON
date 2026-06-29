package com.example.test_g_locon.P2P;

import com.example.test_g_locon.main.UserInfo;

import java.util.ArrayList;

public interface IP2P {
    void onGetDetailUserInfo(UserInfo receiveUserInfo, ArrayList<UserInfo> userInfos); //周辺ユーザからデータが送られた場合
    void onGetPeripheralUsersInfo(ArrayList<UserInfo> userInfos);//シグナリングサーバから周辺ユーザ情報を取得した場合
}
