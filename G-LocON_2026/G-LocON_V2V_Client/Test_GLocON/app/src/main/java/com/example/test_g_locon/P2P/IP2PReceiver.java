package com.example.test_g_locon.P2P;

import android.location.Location;

import com.example.test_g_locon.main.UserInfo;

import java.util.ArrayList;

public interface IP2PReceiver {
    void onGetPeripheralUser(ArrayList<UserInfo> peripheralUsers); //シグナリングサーバから周辺ユーザ情報を取得時
    void onDoUDPHolePunching(UserInfo srcUser);//自身を検索したユーザ情報をNATに登録時
    void onGetPeripheralUserLocation(int locationUpdateCount, String srcIP, int srcPort, Location location, String peerId, double speed);//ピアからデータを取得時
    void onGetAck(int locationCount, String endPointIP, int endPointPort);//ACKの送信時
}
