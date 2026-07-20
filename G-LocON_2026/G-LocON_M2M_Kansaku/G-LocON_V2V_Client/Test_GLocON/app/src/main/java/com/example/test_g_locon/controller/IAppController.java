package com.example.test_g_locon.controller;

import android.location.Location;

import com.example.test_g_locon.main.UserInfo;
import com.example.test_g_locon.navigation.Intersection;

import java.util.ArrayList;
import java.util.List;

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

    // ---- V2V拡張コールバック ----

    /**
     * OSRMルート取得完了後，交差点リストが確定したときに呼ばれる。
     * MapManagerでルートラインと交差点マーカーを描画するために使用する。
     *
     * @param intersections ルート上の交差点リスト（ルート順）
     */
    void onRouteLoaded(List<Intersection> intersections);

    /**
     * 交差点V2VグループへのJOIN完了（EdgeServerへのJOIN送信完了）時に呼ばれる。
     * 交差点マーカーをアクティブ表示に切り替えるために使用する。
     *
     * @param intersection JOIN した交差点
     */
    void onIntersectionJoined(Intersection intersection);

    /**
     * 交差点V2VグループからのLEAVE完了時に呼ばれる。
     * 交差点マーカーを非アクティブ表示に戻すために使用する。
     *
     * @param intersection LEAVE した交差点
     */
    void onIntersectionLeft(Intersection intersection);

    /**
     * 仮想走行中に位置が更新されたときに呼ばれる。
     * 地図カメラと自位置マーカーを仮想座標に移動する。
     *
     * @param lat 仮想緯度
     * @param lng 仮想経度
     */
    void onSimulationLocationUpdated(double lat, double lng);
}
