package com.example.test_g_locon.controller;

import android.location.Location;

import com.example.test_g_locon.main.UserInfo;

import java.util.ArrayList;

/**
 * [新規] AppController → MainActivity へのコールバックインターフェース。
 *
 * 旧実装では MainActivity が ISTUNServerClient / IP2P / LocationListener を直接実装し、
 * 通信処理とUI処理が一つのクラスに混在していた。
 * このインターフェースを挟むことで MainActivity はUI更新のみに専念できる。
 */
public interface IAppController {

    /**
     * 自端末の位置情報が更新されたときに呼ばれる。
     * MainActivity はこの通知を受けて地図カメラを動かすだけでよい。
     *
     * @param location  新しい位置情報
     * @param bearing   進行方向（北を0度とする度数）
     * @param speed     速度 (km/h)
     */
    void onLocationUpdated(Location location, double bearing, double speed);

    /**
     * 特定の周辺ユーザの詳細位置情報（P2P直接通信）が届いたときに呼ばれる。
     *
     * @param userInfo        更新された周辺ユーザ情報
     * @param allPeripheralUsers 現在の周辺ユーザ全リスト
     */
    void onPeripheralUserDetailReceived(UserInfo userInfo, ArrayList<UserInfo> allPeripheralUsers);

    /**
     * シグナリングサーバから周辺ユーザ一覧が届いたときに呼ばれる。
     * マーカの削除判定に使用する。
     *
     * @param allPeripheralUsers 最新の周辺ユーザ全リスト
     */
    void onPeripheralUsersRefreshed(ArrayList<UserInfo> allPeripheralUsers);
}
