package com.example.test_g_locon.P2P;

import android.location.Location;

import com.example.test_g_locon.main.UserInfo;

import java.util.ArrayList;

public interface IP2PReceiver {
    void onGetPeripheralUser(ArrayList<UserInfo> peripheralUsers); //シグナリングサーバから周辺ユーザ情報を取得時
    void onDoUDPHolePunching(UserInfo srcUser);//自身を検索したユーザ情報をNATに登録時
    void onGetGroupMembers(String intersectionId, ArrayList<UserInfo> members); //EdgeServerから交差点グループのメンバー一覧を取得時
    void onDoUDPHolePunchingInGroup(String intersectionId, UserInfo srcUser); //交差点グループに新規車両が参加した時
    void onPeerLeftGroup(String intersectionId, UserInfo leftUser); //交差点グループから他の車両が離脱した時
    void onGetPeripheralUserLocation(int locationUpdateCount, String srcIP, int srcPort, Location location, String peerId, double speed);//ピアからデータを取得時
    void onGetHazard(com.example.test_g_locon.navigation.HazardInfo hazard); //ピアから危険情報（急停止など）を取得時
    void onGetAck(int locationCount, String endPointIP, int endPointPort);//ACKの送信時
}
