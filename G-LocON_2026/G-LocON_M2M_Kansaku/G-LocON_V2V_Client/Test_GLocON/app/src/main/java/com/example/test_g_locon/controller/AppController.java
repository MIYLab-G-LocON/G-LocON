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
    private final Deque<Double> speedHistory = new ArrayDeque<>();

    // [追加] GPS非依存の定期 signalingSearch 用スケジューラ
    // GPS更新がない屋内テスト環境でも周辺端末を発見できるよう、
    // REGISTER完了後から一定間隔で強制的に signalingSearch を呼び出す。
    private ScheduledExecutorService searchScheduler;
    /** REGISTER後に最初の SEARCH を行うまでの待機時間 (秒) */
    private static final long SEARCH_INITIAL_DELAY_SEC = 3;
    /** 定期 SEARCH の実行間隔 (秒) */
    private static final long SEARCH_INTERVAL_SEC = 5;

    // ---- V2V拡張フィールド ----
    private static final String MASTER_SERVER_IP   = "172.20.10.4"; // テザリング
    private static final int    MASTER_SERVER_PORT = 55556;

    private final IntersectionManager intersectionManager;
    private final OsrmRouteClient osrmRouteClient = new OsrmRouteClient();
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
        edgeServerClient = new EdgeServerClient(context, myUserInfo);
        edgeServerClient.setCallback(new EdgeServerClient.IEdgeServerCallback() {
            @Override
            public void onJoinSent(Intersection intersection, long tJoinSentMs) {
                callback.onIntersectionJoined(intersection);
            }
            @Override
            public void onLeaveSent(Intersection intersection) {
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
                    if (natTravel == NAT_TRAVEL_OK && p2p != null) {
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

        // 進行方向の計算（北を0度とする方位角）
        double bearing = new HeadUp(
                currentLocation.getLatitude(), currentLocation.getLongitude(),
                geo.getLatitude(), geo.getLongitude()
        ).getNowAngle();

        // 瞬時速度 (km/h) を2点間距離から算出
        // HubenyDistance は距離(m)を返す。GPS更新間隔を1秒と仮定して m/s→km/h に換算
        double rawSpeed = new HubenyDistance().calcDistance(
                currentLocation.getLatitude(), currentLocation.getLongitude(),
                geo.getLatitude(), geo.getLongitude()
        ) * 3.6;

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
                p2p.signalingSearch(searchRange);
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

            // ② IntersectionManagerに交差点リストをセット（ETA/LEAVE判定開始）
            intersectionManager.setIntersections(intersections);

            // ③ UIへルート表示を通知
            callback.onRouteLoaded(intersections);

            // ④ MasterServerへ交差点IDリストを問い合わせ，エッジサーバAddrを取得
            MasterServerClient masterClient = new MasterServerClient(
                    MASTER_SERVER_IP, MASTER_SERVER_PORT,
                    myUserInfo.getPublicIP(), myUserInfo.getPublicPort(),
                    intersections,
                    updatedIntersections -> {
                        // エッジサーバAddr確定後，IntersectionManagerを更新
                        intersectionManager.setIntersections(updatedIntersections);
                        System.out.println("setDestination: エッジサーバAddr確定 "
                                + updatedIntersections.size() + "件");
                        // 交差点マーカーを再描画（EdgeServer有無が確定した後）
                        callback.onRouteLoaded(updatedIntersections);
                    }
            );
            masterClient.run(); // routeExecutorのスレッド内で同期実行
        });
    }

    // =========================================================
    // 仮想走行シミュレーション
    // =========================================================

    /**
     * 交差点リストを10mごとに線形補間した座標列を生成する。
     * 1秒ごとに10m進む（= SIM_SPEED_MPS）ため，ETAが自然に変化しJOIN閾値を正しく通過する。
     */
    private List<double[]> buildSimPath(List<Intersection> intersections) {
        HubenyDistance hubeny = new HubenyDistance();
        List<double[]> path = new ArrayList<>();
        for (int i = 0; i < intersections.size() - 1; i++) {
            Intersection from = intersections.get(i);
            Intersection to   = intersections.get(i + 1);
            double dist  = hubeny.calcDistance(from.getLat(), from.getLng(),
                                               to.getLat(), to.getLng());
            int steps = Math.max(1, (int) Math.ceil(dist / SIM_SPEED_MPS));
            for (int s = 0; s < steps; s++) {
                double t = (double) s / steps;
                path.add(new double[]{
                    from.getLat() + t * (to.getLat() - from.getLat()),
                    from.getLng() + t * (to.getLng() - from.getLng())
                });
            }
        }
        // 終点を追加
        Intersection last = intersections.get(intersections.size() - 1);
        path.add(new double[]{ last.getLat(), last.getLng() });
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
        simPath  = buildSimPath(list);
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

            callback.onSimulationLocationUpdated(lat, lng);
            intersectionManager.update(lat, lng, SIM_SPEED_MPS);
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
