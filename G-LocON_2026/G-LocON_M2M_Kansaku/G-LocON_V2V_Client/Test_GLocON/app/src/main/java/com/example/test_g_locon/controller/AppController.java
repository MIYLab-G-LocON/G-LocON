package com.example.test_g_locon.controller;

import android.content.Context;
import android.location.Location;

import com.example.test_g_locon.P2P.IP2P;
import com.example.test_g_locon.P2P.P2P;
import com.example.test_g_locon.STUNServerClient.ISTUNServerClient;
import com.example.test_g_locon.STUNServerClient.STUNServerClient;
import com.example.test_g_locon.intersection.EdgeServerClient;
import com.example.test_g_locon.location.LocationProvider;
import com.example.test_g_locon.main.HeadUp;
import com.example.test_g_locon.main.HubenyDistance;
import com.example.test_g_locon.main.UserInfo;
import com.example.test_g_locon.main.UtilCommon;
import com.example.test_g_locon.navigation.Intersection;
import com.example.test_g_locon.navigation.IntersectionManager;
import com.example.test_g_locon.navigation.MasterServerClient;
import com.example.test_g_locon.navigation.OsrmRouteClient;
import com.example.test_g_locon.sim.SimBridgeClient;

import android.location.LocationListener;
import android.os.Bundle;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * [新規] 通信処理・位置情報取得の統括コントローラ。
 *
 * 旧実装では以下の責務がすべて MainActivity に集中していた:
 *   - STUNサーバとの通信 (ISTUNServerClient 実装)
 *   - P2P通信コールバック処理 (IP2P 実装)
 *   - GPS位置情報の受け取り (LocationListener 実装)
 *   - 自端末 UserInfo の管理
 *   - P2P送信タイミングの制御
 *
 * このクラスがそれらを引き受け、UIに関わる通知のみ IAppController 経由で
 * MainActivity へ委譲する。MainActivity 側はUI操作に専念できる。
 */
public class AppController implements ISTUNServerClient, IP2P, LocationListener {

    // ---- 定数 ----
    /** P2Pが使用可能な状態を示すフラグ値 */
    private static final int NAT_TRAVEL_OK = 1;
    /** シグナリング更新を行うGPS更新回数の間隔 */
    private static final int USER_INFO_UPDATE_INTERVAL = 2;

    // ---- 依存オブジェクト ----
    private final Context context;
    private final UtilCommon utilCommon;
    private final DatagramSocket socket;
    /** UIへのコールバック先。MainActivity が実装する */
    private final IAppController callback;

    // ---- 状態 ----
    private P2P p2p;
    private LocationProvider locationProvider;
    private UserInfo myUserInfo;
    private Location currentLocation;

    /** NATトラバーサル完了フラグ */
    private int natTravel = 0;
    private int geoUpdateCount = 1;
    private int totalGeoUpdateCount = 0;
    /**
     * 周辺ユーザ検索半径 (メートル)
     *
     * [変更] 100 → 200 に変更。
     *
     * 旧値 100m は屋外の晴天下（GPS誤差 ±5m 程度）を想定した値だった。
     * 屋内テスト環境では GPS 誤差が ±50〜200m に達し、同室の2端末でも
     * お互いの GPS 読取値が 100m 以上乖離することがある。
     * この場合、サーバの SEARCH で「距離 ≤ searchRange」の条件を満たさず
     * 検索ヒットが 0 件になり、P2P通信が一切始まらない。
     *
     * 200m にすることで、同建物内であれば GPS 精度に依らず確実にヒットする。
     * テスト完了後、実運用に合わせて再調整すること。
     */
    private double searchRange = 200;
    /** 仮想位置を使うか否か (trueにすると initialLatitude/Longitude を使い続ける) */
    private boolean useVirtualPosition;

    /**
     * [追加] 速度の移動平均バッファ。
     *
     * GPS は瞬間的に位置がブレることがあり、2点間距離から算出する瞬時速度も
     * 急スパイクが発生しやすい。1回のブレだけで赤ピン警告が出ないよう、
     * 直近 SPEED_SMOOTH_SAMPLES 回分の速度を保持して平均を使う。
     *
     * 効果:
     *   - GPS瞬間ブレ（1回スパイク）: 平均への影響は 1/N にとどまり閾値を超えない
     *   - 継続的な高速移動     : N回連続して高速 → 平均も高くなり警告が出る
     *   これにより「同じ方向に継続して高速移動している場合のみ警告」と等価になる。
     */
    private static final int SPEED_SMOOTH_SAMPLES = 5;
    /**
     * 進行方向を更新する条件。止まっているとGPSの位置が数mずつ揺れ，前回との差から求めた向きが
     * ばらばらになる（矢印が回る）。GPSが測った速度がこれ以上のとき（動いているとき）だけ向きを更新する
     */
    private static final float BEARING_MIN_SPEED_MPS = 1.5f;   // 約5 km/h
    /** GPSの速度が無い（ネットワーク測位など）ときは，この距離以上かつ位置の誤差以上動いたときだけ向きを更新する */
    private static final double BEARING_MIN_MOVE_M = 5.0;
    /** 最後に決まった進行方向（止まっている間はこれを使う） */
    private double lastBearing = 0;
    private final Deque<Double> speedHistory = new ArrayDeque<>();

    // [追加] GPS非依存の定期 signalingSearch 用スケジューラ
    // GPS更新がない屋内テスト環境でも周辺端末を発見できるよう、
    // REGISTER完了後から一定間隔で強制的に signalingSearch を呼び出す。
    private ScheduledExecutorService searchScheduler;
    /** REGISTER後に最初の SEARCH を行うまでの待機時間 (秒) */
    private static final long SEARCH_INITIAL_DELAY_SEC = 3;
    /** 定期 SEARCH の実行間隔 (秒) */
    private static final long SEARCH_INTERVAL_SEC = 5;
    /**
     * [V2V] SignalingServerへの距離ベース SEARCH を行うか。
     * V2Vでは周辺車両をEdgeServerの交差点グループで決めるため false。
     * true にすると SignalingServer の検索結果（距離ベース）も受信するが，
     * 交差点グループの一覧とは別扱い（上書きしない）になる。
     */
    private static final boolean USE_SIGNALING_SEARCH = false;

    // ---- V2V拡張フィールド ----
//    private static final String MASTER_SERVER_IP   = "172.20.10.4"; // テザリング
    private static final String MASTER_SERVER_IP   = "192.168.137.1"; // PCホットスポット（MainActivity.SERVER_IP と揃える）
    private static final int    MASTER_SERVER_PORT = 55556;

    private final IntersectionManager intersectionManager;
    private final OsrmRouteClient osrmRouteClient = new OsrmRouteClient();
    /** 今のルートの道の形 [緯度, 経度]（OSRM または SimBridge から。地図のルート線・SIMの走行経路に使う） */
    private volatile List<double[]> routeShape = null;
    private EdgeServerClient edgeServerClient;
    // ルート取得・MasterServer問い合わせ用の単一スレッド
    private final ExecutorService routeExecutor = Executors.newSingleThreadExecutor();

    // ---- 仮想走行フィールド ----
    /** 仮想走行スケジューラ */
    private ScheduledExecutorService simScheduler = null;
    /** 補間済み座標列（10mごと）上の現在インデックス */
    private int simIndex = 0;
    /** 補間済み座標列 [lat, lng] */
    private List<double[]> simPath = null;
    /** 仮想走行速度（m/s）: 約36km/h */
    private static final double SIM_SPEED_MPS = 10.0;
    /** 仮想走行の位置更新間隔（ミリ秒） */
    private static final long SIM_INTERVAL_MS = 1000;

    // ---- SUMOモード ----
    /** PC上の SimBridge（SimBridge/sim_bridge.py）の待ち受けポート */
    public static final int SIM_BRIDGE_PORT = 55700;
    private SimBridgeClient simBridge = null;

    /**
     * @param context          Activity の Context
     * @param utilCommon       グローバル設定ストア (Application クラス)
     * @param socket           共有 DatagramSocket
     * @param callback         UIへの通知先 (MainActivity)
     * @param useVirtualPosition true なら GPS更新で位置を上書きしない
     * @param initialLatitude  仮想位置 or 起動時初期緯度
     * @param initialLongitude 仮想位置 or 起動時初期経度
     */
    public AppController(Context context, UtilCommon utilCommon, DatagramSocket socket,
                         IAppController callback, boolean useVirtualPosition,
                         double initialLatitude, double initialLongitude) {
        this.context = context;
        this.utilCommon = utilCommon;
        this.socket = socket;
        this.callback = callback;
        this.useVirtualPosition = useVirtualPosition;

        // 初期位置を設定（仮想位置モードまたはGPS取得前のデフォルト）
        this.currentLocation = new Location("");
        this.currentLocation.setLatitude(initialLatitude);
        this.currentLocation.setLongitude(initialLongitude);

        this.myUserInfo = new UserInfo();
        this.intersectionManager = new IntersectionManager(context);
    }

    /**
     * アプリ開始処理。STUNサーバへの登録を起点に通信シーケンスを開始する。
     * 旧実装では onClick(start) 内に直接書かれていた処理。
     */
    public void start() {
        STUNServerClient stunServerClient = new STUNServerClient(socket, this);
        stunServerClient.stunServerClientStart();
    }

    // =========================================================
    // ISTUNServerClient 実装
    // =========================================================

    /**
     * STUNサーバからグローバルIP・ポートを取得したときのコールバック。
     * P2P通信の初期化と位置情報取得の開始を行う。
     * 旧実装では MainActivity.onGetGlobalIP_Port() に書かれていた。
     */
    @Override
    public void onGetGlobalIP_Port(String IP, int port) {
        myUserInfo.setPublicIP(IP);
        myUserInfo.setPublicPort(port);
        myUserInfo.setPrivateIP(getPrivateIP());
        myUserInfo.setPrivatePort(socket.getLocalPort());
        myUserInfo.setPeerId(utilCommon.getPeerId());
        myUserInfo.setSpeed(0);
        myUserInfo.setLatitude(currentLocation.getLatitude());
        myUserInfo.setLongitude(currentLocation.getLongitude());

        // P2P通信を初期化してシグナリング登録
        p2p = new P2P(socket, myUserInfo, this);
        natTravel = NAT_TRAVEL_OK;
        p2p.p2pReceiverStart();
        p2p.signalingRegister();

        // V2V: EdgeServerClientを初期化（グローバルIP確定後に生成する）
        edgeServerClient = new EdgeServerClient(context, socket, myUserInfo);
        edgeServerClient.setCallback(new EdgeServerClient.IEdgeServerCallback() {
            @Override
            public void onJoinSent(Intersection intersection, long tJoinSentMs) {
                callback.onIntersectionJoined(intersection);
            }
            @Override
            public void onLeaveSent(Intersection intersection) {
                // 離脱した交差点グループのメンバーを位置情報の送信先から外す
                p2p.removeGroup(intersection.getIntersectionId());
                callback.onIntersectionLeft(intersection);
            }
        });

        // V2V: IntersectionManagerのJOIN/LEAVEコールバックを設定
        intersectionManager.setCallback(new IntersectionManager.IIntersectionCallback() {
            @Override
            public void onShouldJoin(Intersection intersection) {
                edgeServerClient.join(intersection);
            }
            @Override
            public void onShouldLeave(Intersection intersection) {
                edgeServerClient.leave(intersection);
            }
        });

        // [追加] GPS非依存の定期 signalingSearch スケジューラを開始。
        //
        // 問題: 旧実装では signalingSearch() が onLocationChanged() の中でのみ
        //       呼ばれていたため、屋内テスト等で GPS が取得できない場合に
        //       SEARCH が一度も実行されず、端末間でP2P通信が開始されなかった。
        //
        // 対策: REGISTER完了の SEARCH_INITIAL_DELAY_SEC 秒後から
        //       SEARCH_INTERVAL_SEC 秒ごとに signalingSearch() を強制実行する。
        //       GPS更新と二重に呼ばれても問題なし（サーバ側は冪等）。
        searchScheduler = Executors.newSingleThreadScheduledExecutor();
        searchScheduler.scheduleAtFixedRate(
                () -> {
                    if (USE_SIGNALING_SEARCH && natTravel == NAT_TRAVEL_OK && p2p != null) {
                        p2p.signalingSearch(searchRange);
                    }
                },
                SEARCH_INITIAL_DELAY_SEC,
                SEARCH_INTERVAL_SEC,
                TimeUnit.SECONDS
        );

        // [変更] MyLocation → LocationProvider (クラス名をより明確に変更)
        locationProvider = new LocationProvider(context, this, 1);
        locationProvider.startLocationUpdates();
    }

    // =========================================================
    // LocationListener 実装
    // =========================================================

    /**
     * GPS位置情報が更新されたときのコールバック。
     * 速度・角度計算、P2P送信タイミング制御を担う。
     * 旧実装では MainActivity.onLocationChanged() に書かれていた UI混在処理を分離した。
     */
    @Override
    public void onLocationChanged(Location geo) {
        // 仮想位置モード: GPS速度が virtual-to-GPS 距離から計算され異常大になるため
        // intersectionManager.update() と UI更新をすべてスキップする。
        // searchScheduler が5秒ごとに SEARCH を送り続けるため P2P 接続は維持される。
        if (useVirtualPosition) return;

        double moved = new HubenyDistance().calcDistance(
                currentLocation.getLatitude(), currentLocation.getLongitude(),
                geo.getLatitude(), geo.getLongitude()
        );

        // 進行方向（北を0度とする方位角）。止まっているときのGPSの揺れで向きが回らないよう，動いているときだけ更新する
        //   1. GPSが速度と向きを測っていれば，速度が BEARING_MIN_SPEED_MPS 以上のときだけその向きを使う
        //   2. 無ければ，BEARING_MIN_MOVE_M 以上かつ位置の誤差以上動いたときだけ前回からの向きを使う
        //   3. それ以外（止まっている・初回）は前回の向きのまま
        double bearing = lastBearing;
        if (totalGeoUpdateCount > 0) {
            if (geo.hasSpeed()) {
                if (geo.getSpeed() >= BEARING_MIN_SPEED_MPS) {
                    bearing = geo.hasBearing() ? geo.getBearing()
                            : new HeadUp(currentLocation.getLatitude(), currentLocation.getLongitude(),
                                         geo.getLatitude(), geo.getLongitude()).getNowAngle();
                }
            } else if (moved >= Math.max(BEARING_MIN_MOVE_M, geo.hasAccuracy() ? geo.getAccuracy() : 0)) {
                bearing = new HeadUp(currentLocation.getLatitude(), currentLocation.getLongitude(),
                                     geo.getLatitude(), geo.getLongitude()).getNowAngle();
            }
        }
        lastBearing = bearing;

        // 速度 (km/h)。GPSが測った速度（ドップラー。止まっていればほぼ0）があればそれを使う。
        // 無ければ2点間距離から求める（GPS更新間隔を1秒と仮定して m/s→km/h に換算）。
        // 2点間距離だけだと，止まっていてもGPSの揺れで 10〜30 km/h に見え，ETA（JOIN）が狂っていた
        double rawSpeed = geo.hasSpeed() ? geo.getSpeed() * 3.6 : moved * 3.6;

        // [追加] 移動平均で GPS ブレによる速度スパイクを除去する。
        // 直近 SPEED_SMOOTH_SAMPLES 回分の瞬時速度を保持し、その平均を使用する。
        // GPS が1回だけブレて瞬間移動のように見えても、平均への影響は 1/N にとどまるため
        // 閾値 (TOLERANCE_SPEED) を超えにくい。
        // 一方、実際に高速移動している場合は複数回連続して高い値が入り、平均も上がるため
        // 「同じ方向に継続して高速移動」しているときのみ赤ピン警告となる。
        speedHistory.addLast(rawSpeed);
        if (speedHistory.size() > SPEED_SMOOTH_SAMPLES) speedHistory.pollFirst();
        double speed = 0;
        for (double s : speedHistory) speed += s;
        speed /= speedHistory.size();

        // 仮想位置モードでなければ実際のGPS位置で更新
        if (!useVirtualPosition) {
            currentLocation = geo;
        }

        // myUserInfo を最新状態に同期
        myUserInfo.setLatitude(currentLocation.getLatitude());
        myUserInfo.setLongitude(currentLocation.getLongitude());
        myUserInfo.setSpeed(speed);

        // UIへ位置更新を通知（地図カメラ移動はMainActivityのMapManagerが担う）
        callback.onLocationUpdated(currentLocation, bearing, speed);

        // V2V: ETA計算・JOIN/LEAVE判定（速度はkm/h→m/sに変換して渡す）
        // 初回GPS更新は currentLocation が初期値(35.0,136.0)のため速度が異常大になる → スキップ
        if (totalGeoUpdateCount > 0) {
            intersectionManager.update(
                    currentLocation.getLatitude(),
                    currentLocation.getLongitude(),
                    speed / 3.6
            );
        }

        // NAT完了後のみP2P送信を行う
        if (natTravel == NAT_TRAVEL_OK) {
            p2p.setMyUserInfo(myUserInfo);
            totalGeoUpdateCount++;
            geoUpdateCount++;

            if (geoUpdateCount == USER_INFO_UPDATE_INTERVAL) {
                geoUpdateCount = 0;
                p2p.signalingUpdate();
                if (USE_SIGNALING_SEARCH) p2p.signalingSearch(searchRange);
                p2p.sendLocation(totalGeoUpdateCount);
            } else {
                p2p.sendLocation(totalGeoUpdateCount);
            }
        }
    }

    /** API 29未満でも必要な LocationListener メソッド（処理なし） */
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {}

    // =========================================================
    // IP2P 実装
    // =========================================================

    /**
     * P2P直接通信で周辺ユーザの詳細位置を受信したときのコールバック。
     * 旧実装では MainActivity.onGetDetailUserInfo() に書かれていた。
     */
    @Override
    public void onGetDetailUserInfo(UserInfo userInfo, ArrayList<UserInfo> peripheralUserInfos) {
        callback.onPeripheralUserDetailReceived(userInfo, peripheralUserInfos);
    }

    /**
     * シグナリングサーバから周辺ユーザ一覧を受信したときのコールバック。
     * 旧実装では MainActivity.onGetPeripheralUsersInfo() に書かれていた。
     */
    @Override
    public void onGetPeripheralUsersInfo(ArrayList<UserInfo> peripheralUserInfos) {
        callback.onPeripheralUsersRefreshed(peripheralUserInfos);
    }

    // =========================================================
    // ユーティリティ
    // =========================================================

    /**
     * 端末のプライベートIPv4アドレスを取得する。
     * 旧実装では MainActivity.GetPrivateIP() として存在していた。
     */
    private String getPrivateIP() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress addr : Collections.list(n.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return null;
    }

    /** 現在保持している自端末の位置情報を返す */
    public Location getCurrentLocation() {
        return currentLocation;
    }

    /** 自端末の UserInfo を返す（主にマーカ速度比較に使用） */
    public UserInfo getMyUserInfo() {
        return myUserInfo;
    }

    /** P2P インスタンスを返す（マーカタップ時のユーザ情報参照に使用） */
    public P2P getP2p() {
        return p2p;
    }

    /**
     * [追加] リソースを解放する。
     * アプリ終了時（MainActivity.onDestroy 等）に呼び出すことで
     * searchScheduler のスレッドが残存しないようにする。
     *
     * [追加] signalingDelete() を呼び出してサーバ側のユーザリストから自端末を削除する。
     *
     * 問題: アプリを再インストール・再起動するたびに REGISTER が行われるが、
     *       旧実装では DELETE が送られず、前回の登録情報がサーバに残り続けた。
     *       これにより SEARCH 結果に消えた端末が混入し、P2P接続が正常に行えなかった。
     *
     * 対策: stop() 内で signalingDelete() を呼び出す。
     *       P2P の executor (CachedThreadPool) はここではシャットダウンしないため、
     *       DELETE の UDP パケット送信スレッドはプロセスが終了するまでの間に完了できる。
     */
    public void stop() {
        // [追加] 定期 SEARCH スケジューラを停止（これ以上 SEARCH が送られないようにする）
        if (searchScheduler != null && !searchScheduler.isShutdown()) {
            searchScheduler.shutdownNow();
            searchScheduler = null; // 二重呼び出し防止
        }
        // [追加] サーバに DELETE を送信して自端末をユーザリストから除外する。
        // signalingDelete() は P2P の CachedThreadPool に非同期投入されるため
        // メインスレッドをブロックしない。プロセス終了前に UDP 送信が完了する。
        //
        // [追加] p2p = null にすることで、終了ボタン → onDestroy() の順で
        // stop() が二重呼び出しされても DELETE を重複送信しない（冪等性の確保）。
        if (p2p != null) {
            p2p.signalingDelete();
            p2p = null; // 二重呼び出し防止
        }
        if (locationProvider != null) {
            locationProvider.stopLocationUpdates();
            locationProvider = null; // 二重呼び出し防止
        }
        // V2V: リソース解放
        if (edgeServerClient != null) {
            edgeServerClient.shutdown();
            edgeServerClient = null;
        }
        if (simBridge != null) {
            simBridge.stop();
            simBridge = null;
        }
        intersectionManager.close();
        routeExecutor.shutdown();
        stopSimulation();
    }

    // =========================================================
    // V2V拡張: 目的地設定・ルート取得
    // =========================================================

    /**
     * 目的地を設定し，OSRMでルートを取得してIntersectionManagerに渡す。
     * MasterServerへも交差点IDリストを問い合わせ，エッジサーバAddr/Portを確定する。
     * ネットワーク通信を伴うためrouteExecutor（バックグラウンド）で実行する。
     *
     * @param destLat 目的地の緯度
     * @param destLng 目的地の経度
     */
    public void setDestination(double destLat, double destLng) {
        routeExecutor.submit(() -> {
            // ① OSRM公開APIでルート上の交差点リストを取得
            List<Intersection> intersections = osrmRouteClient.fetchIntersections(
                    currentLocation.getLatitude(), currentLocation.getLongitude(),
                    destLat, destLng
            );
            if (intersections.isEmpty()) {
                System.err.println("setDestination: ルート取得失敗");
                return;
            }
            routeShape = osrmRouteClient.getLastShape();

            loadRoute(intersections);
        });
    }

    /**
     * ルート上の交差点リストをセットし，MasterServerからエッジサーバAddrを取得する。
     * 実機モード（OSRM）とSUMOモード（SimBridge）で共通の処理。routeExecutor 上で呼ぶこと。
     */
    private void loadRoute(List<Intersection> intersections) {
        // ② IntersectionManagerに交差点リストをセット（ETA/LEAVE判定開始）
        intersectionManager.setIntersections(intersections);

        // ③ UIへルート表示を通知
        callback.onRouteLoaded(intersections, routeShape);

        // ④ MasterServerへ交差点IDリストを問い合わせ，エッジサーバAddrを取得
        MasterServerClient masterClient = new MasterServerClient(
                MASTER_SERVER_IP, MASTER_SERVER_PORT,
                myUserInfo.getPublicIP(), myUserInfo.getPublicPort(),
                intersections,
                updatedIntersections -> {
                    // エッジサーバAddr確定後，IntersectionManagerを更新
                    intersectionManager.setIntersections(updatedIntersections);
                    System.out.println("loadRoute: エッジサーバAddr確定 "
                            + updatedIntersections.size() + "件");
                    // 交差点マーカーを再描画（EdgeServer有無が確定した後）
                    callback.onRouteLoaded(updatedIntersections, routeShape);
                }
        );
        masterClient.run(); // routeExecutorのスレッド内で同期実行
    }

    /**
     * GPS以外から与えられた位置（SIM走行・SUMO）で自車の状態を更新する。
     * JOIN/LEAVE判定に加え，P2Pで周囲の車へ位置を送る（GPS更新時と同じ扱い）。
     *
     * @param speedMps 速度 [m/s]
     * @param bearing  進行方向（北=0の時計回り）
     */
    private void applyExternalLocation(double lat, double lng, double speedMps, double bearing) {
        currentLocation.setLatitude(lat);
        currentLocation.setLongitude(lng);
        myUserInfo.setLatitude(lat);
        myUserInfo.setLongitude(lng);
        myUserInfo.setSpeed(speedMps * 3.6);   // UserInfo の速度は km/h

        callback.onSimulationLocationUpdated(lat, lng, bearing);
        intersectionManager.update(lat, lng, speedMps);

        if (natTravel == NAT_TRAVEL_OK && p2p != null) {
            p2p.setMyUserInfo(myUserInfo);
            totalGeoUpdateCount++;
            p2p.sendLocation(totalGeoUpdateCount);
        }
    }

    // =========================================================
    // SUMOモード
    // =========================================================

    /**
     * SUMOモードを開始する。PC上の SimBridge に接続し，割り当てられたSUMO車両の
     * ルートと位置で走行する（GPS・OSRMは使わない）。開始ボタンで通信を始めた後に呼ぶこと。
     *
     * @param bridgeIp SimBridge を動かしているPCのIP（PCホットスポットなら 192.168.137.1）
     */
    public boolean startSumoMode(String bridgeIp) {
        if (p2p == null || edgeServerClient == null) return false;
        if (simBridge != null) return true;
        stopSimulation();
        useVirtualPosition = true;              // GPS更新で位置を上書きしない
        simBridge = new SimBridgeClient(bridgeIp, SIM_BRIDGE_PORT, myUserInfo.getPeerId(),
                new SimBridgeClient.Listener() {
                    @Override
                    public void onSimRoute(String vehicleId, List<Intersection> intersections) {
                        final List<double[]> shape = simBridge.getLastRouteShape();
                        routeExecutor.submit(() -> {
                            leaveAllIntersections();
                            routeShape = shape;
                            loadRoute(intersections);
                        });
                        callback.onSumoStatus("SUMO車両 " + vehicleId + " に乗車（交差点 " + intersections.size() + "）");
                    }

                    @Override
                    public void onSimLocation(double lat, double lng, double speedMps, double bearing) {
                        applyExternalLocation(lat, lng, speedMps, bearing);
                    }

                    @Override
                    public void onSimVehicles(List<com.example.test_g_locon.sim.SimVehicle> vehicles) {
                        callback.onSimVehicles(vehicles);
                    }

                    @Override
                    public void onSimEnd(String vehicleId) {
                        routeExecutor.submit(() -> leaveAllIntersections());
                        callback.onSumoStatus("SUMO車両 " + vehicleId + " が到着。次の車を待っています");
                    }
                });
        new Thread(simBridge, "SimBridgeClient").start();
        return true;
    }

    public boolean isSumoMode() {
        return simBridge != null;
    }

    /** JOIN中の交差点からすべて離脱する（SUMO車両の乗り換え・到着時） */
    private void leaveAllIntersections() {
        for (Intersection i : intersectionManager.getIntersections()) {
            if (i.isJoined()) {
                i.setJoined(false);
                i.setHasJoinedAndLeft(true);
                if (edgeServerClient != null) edgeServerClient.leave(i);
            }
        }
    }

    // =========================================================
    // 仮想走行シミュレーション
    // =========================================================

    /**
     * ルートの点の列（道の形，無ければ交差点）を10mごとに補間した座標列を生成する。
     * 1秒ごとに10m進む（= SIM_SPEED_MPS）ため，ETAが自然に変化しJOIN閾値を正しく通過する。
     */
    private List<double[]> buildSimPath(List<double[]> points) {
        // 点の列 [緯度, 経度] を，1秒に SIM_SPEED_MPS 進むよう等間隔に並べ直す（曲がり角の点も通る）
        HubenyDistance hubeny = new HubenyDistance();
        List<double[]> path = new ArrayList<>();
        double carry = 0;   // 前の区間で余った距離
        for (int i = 0; i < points.size() - 1; i++) {
            double[] from = points.get(i), to = points.get(i + 1);
            double dist = hubeny.calcDistance(from[0], from[1], to[0], to[1]);
            if (dist <= 0) continue;
            double d = carry;
            while (d < dist) {
                double t = d / dist;
                path.add(new double[]{from[0] + t * (to[0] - from[0]), from[1] + t * (to[1] - from[1])});
                d += SIM_SPEED_MPS;
            }
            carry = d - dist;
        }
        // 終点を追加
        path.add(points.get(points.size() - 1).clone());
        return path;
    }

    /**
     * ルート上を10m/s（約36km/h）で連続移動する仮想走行を開始する。
     * 交差点間を補間した座標列を1秒ごとに進み，ETAが正しく減少してJOIN/LEAVEが発火する。
     * 終端に達したら自動停止する。
     */
    public void startSimulation() {
        List<Intersection> list = intersectionManager.getIntersections();
        if (list.isEmpty()) {
            System.err.println("startSimulation: 交差点リストが空です。先にルートを設定してください。");
            return;
        }
        stopSimulation();
        // 仮想位置モード中に GPS が引き起こした誤JOIN/LEAVEをリセットし
        // SIM開始から正しい JOIN/LEAVE シーケンスを開始できるようにする。
        intersectionManager.resetAllJoinState();
        // 道の形があればそれに沿って走る（無ければ交差点どうしを直線で結ぶ）
        List<double[]> pts = new ArrayList<>();
        if (routeShape != null && routeShape.size() >= 2) {
            pts.addAll(routeShape);
        } else {
            for (Intersection i : list) pts.add(new double[]{i.getLat(), i.getLng()});
        }
        simPath  = buildSimPath(pts);
        simIndex = 0;
        simScheduler = Executors.newSingleThreadScheduledExecutor();
        simScheduler.scheduleAtFixedRate(() -> {
            if (simIndex >= simPath.size()) {
                stopSimulation();
                System.out.println("仮想走行: ルート終端に達しました");
                return;
            }
            double lat = simPath.get(simIndex)[0];
            double lng = simPath.get(simIndex)[1];
            System.out.println("仮想走行[" + simIndex + "/" + simPath.size()
                    + "]: lat=" + lat + " lng=" + lng);

            double bearing = 0;
            if (simIndex > 0) {
                bearing = new HeadUp(simPath.get(simIndex - 1)[0], simPath.get(simIndex - 1)[1], lat, lng)
                        .getNowAngle();
            }
            // [変更] 以前は地図とJOIN判定だけを更新し，P2PではGPSの位置を送っていた。
            //        SIM の位置を自車の位置として扱い，P2Pでも送るようにした
            applyExternalLocation(lat, lng, SIM_SPEED_MPS, bearing);
            simIndex++;
        }, 0, SIM_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);

        System.out.println("仮想走行開始: 補間後" + simPath.size() + "ステップ speed=" + SIM_SPEED_MPS + "m/s");
    }

    /** 仮想走行を停止する */
    public void stopSimulation() {
        if (simScheduler != null && !simScheduler.isShutdown()) {
            simScheduler.shutdownNow();
            simScheduler = null;
        }
    }

    public boolean isSimulating() {
        return simScheduler != null && !simScheduler.isShutdown();
    }

    /**
     * 仮想位置を設定する。
     * 呼び出し後は GPS 更新で位置が上書きされなくなる。
     * SIM走行の起点にもなる。
     */
    public void setVirtualPosition(double lat, double lng) {
        useVirtualPosition = true;
        currentLocation.setLatitude(lat);
        currentLocation.setLongitude(lng);
        myUserInfo.setLatitude(lat);
        myUserInfo.setLongitude(lng);
        // SIM実行中はSIMが位置を制御するためカメラ移動しない
        if (!isSimulating()) {
            callback.onLocationUpdated(currentLocation, 0, 0);
        }
    }
}
