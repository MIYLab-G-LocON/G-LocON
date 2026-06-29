package com.example.test_g_locon.main;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
// [変更] com.google.android.gms.location.LocationListener → android.location.LocationListener
import android.location.LocationListener;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.example.test_g_locon.P2P.IP2P;
import com.example.test_g_locon.P2P.P2P;
import com.example.test_g_locon.R;
import com.example.test_g_locon.STUNServerClient.ISTUNServerClient;
import com.example.test_g_locon.STUNServerClient.STUNServerClient;

// [変更] Google Maps SDK のインポートを全て削除し、osmdroid のインポートに置き換え
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.io.File;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// [変更] クラス宣言から OnMapReadyCallback と GoogleMap.OnMarkerClickListener を削除
//   OnMapReadyCallback  → osmdroid は非同期コールバック不要。onCreate で直接 MapView を初期化する
//   GoogleMap.OnMarkerClickListener → osmdroid は各マーカーに個別リスナーを設定する方式
// [変更] LocationListener は android.location.LocationListener に変更
public class MainActivity extends AppCompatActivity
        implements View.OnClickListener, LocationListener, ISTUNServerClient, IP2P {

    private EditText peerId;
    private Button start;
    private Button end;
    private Button plus;
    private Button minus;
    private Button angle;
    // [変更] GoogleMap → MapView（osmdroid）
    private MapView mMap;
    private Location mLocation;
    private float nowCameraAngle = 0;
    private double nowSpeed = 0;
    private List<MarkerInfo> markerList;
    // [変更] Circle（Google Maps）→ Polygon（osmdroid）。osmdroidに Circle クラスはなく Polygon で円を近似する
    private Polygon circle = null;
    final static private double TOLERANCE_SPEED = 7; //速度差
    private float cameraLevel = 4.0f;
    final static private String HEAD_UP = "HEAD_UP";
    final static private String NORTH_UP = "NORTH_UP";
    private String cameraAngle = HEAD_UP;

    UtilCommon utilCommon;
    UserInfo myUserInfo;
    private DatagramSocket socket;
    final static int NAT_TRAVEL_OK = 1;
    private int natTravel = 0;
    final private static int USER_INFO_UPDATE_INTERVAL = 2;
    private int geoUpdateCount = 1;
    private int totalGeoUpdateCount = 0;
    private double searchRange = 100;
    private int addMarker = 0;
    final private int ADD_MARKER_PROGRESS = 1;
    final private int NOT_ADD_MARKER_PROGRESS = 0;
    private P2P p2p;
    final private String serverIP = "172.31.104.25"; // TODO: サーバのIPを設定。
    final private boolean USE_VIRTUAL_POSITION = false; // TODO: 仮想位置を使用する場合はtrue。
    final private double initialLatitude = 0.0; // TODO: 仮想位置の緯度を設定。
    final private double initialLongitude = 0.0; // TODO: 仮想位置の経度を設定。


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // [変更] osmdroid の初期設定（setContentView より前に必ず呼ぶこと）
        // ユーザーエージェント設定（osmdroid のタイル取得ポリシー上必須）
        Configuration.getInstance().setUserAgentValue(getPackageName());
        // タイルキャッシュをアプリ固有ディレクトリに保存（外部ストレージ不要）
        Configuration.getInstance().setOsmdroidBasePath(getCacheDir());
        Configuration.getInstance().setOsmdroidTileCache(new File(getCacheDir(), "osmdroid"));

        setContentView(R.layout.activity_main);

        start = findViewById(R.id.start);
        end = findViewById(R.id.end);
        plus = findViewById(R.id.plus);
        minus = findViewById(R.id.minus);
        angle = findViewById(R.id.angle);
        peerId = findViewById(R.id.peerId);
        start.setOnClickListener(this);
        end.setOnClickListener(this);
        end.setVisibility(View.INVISIBLE);

        createDatagramSocket();
        utilCommon = (UtilCommon) getApplication();
        myUserInfo = new UserInfo();
        markerList = new ArrayList<>();

        // [変更] SupportMapFragment + getMapAsync → MapView を直接 findViewById で取得
        // osmdroid の MapView はコールバック不要で即座に使用可能
        mMap = findViewById(R.id.mapFragment);
        mMap.setTileSource(TileSourceFactory.MAPNIK); // OpenStreetMap の標準タイルを使用
        mMap.setMultiTouchControls(true);              // ピンチイン・ピンチアウトによるズームを有効化

        // osmdroid のデフォルトズームは 1（全世界表示）のため、起動時に明示的に設定する
        // 設定しないと cameraPosition() が呼ばれる（位置情報更新）まで縮小表示のままになる
        mMap.getController().setZoom((double) cameraLevel); // cameraLevel = 18.0f（初期値）
        mMap.getController().setCenter(new GeoPoint(initialLatitude, initialLongitude));

        // [変更] GoogleMap.setMyLocationEnabled → MyLocationNewOverlay で自端末位置を表示
        // 元実装の onMapReady 内のパーミッションチェックをここに移動
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            MyLocationNewOverlay myLocationOverlay = new MyLocationNewOverlay(mMap);
            myLocationOverlay.enableMyLocation();
            mMap.getOverlays().add(myLocationOverlay);
        }
    }

    // [変更] osmdroid の MapView はライフサイクルに合わせて onResume/onPause を呼ぶ必要がある
    // これを怠るとタイル取得スレッドが停止しない
    @Override
    protected void onResume() {
        super.onResume();
        mMap.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mMap.onPause();
    }

    private void createDatagramSocket() {
        try {
            socket = new DatagramSocket();
            socket.setReuseAddress(true);
        } catch (SocketException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.start) {
            utilCommon.setSignalingServerIP(serverIP);
            utilCommon.setSignalingServerPort(55555);
            utilCommon.setStunServerIP(serverIP);
            utilCommon.setStunServerPort(55554);
            utilCommon.setPeerId(peerId.getText().toString());

            peerId.setVisibility(View.INVISIBLE);
            start.setVisibility(View.VISIBLE);
            end.setVisibility(View.VISIBLE);
/**/
            plus.setOnClickListener(this);
            minus.setOnClickListener(this);
            angle.setOnClickListener(this);
/**/
            mLocation = new Location("");
            mLocation.setLatitude(initialLatitude);
            mLocation.setLongitude(initialLongitude);
/**/
            // TODO: STUNServerClient起動
            STUNServerClient stunServerClient = new STUNServerClient(socket, this);
            stunServerClient.stunServerClientStart();

        } else if (v.getId() == R.id.end) {
            System.exit(1);

        } else if (v.getId() == R.id.plus) {
            cameraLevel += 1f;

        } else if (v.getId() == R.id.minus) {
            cameraLevel -= 1f;

        } else if (v.getId() == R.id.angle) {
            if (cameraAngle.equals(HEAD_UP)) {
                cameraAngle = NORTH_UP;
                angle.setText("NORTHUP");

            } else if (cameraAngle.equals(NORTH_UP)) {
                cameraAngle = HEAD_UP;
                angle.setText("HEADUP");
            }
        }
    }

    // [変更] onMapReady(GoogleMap) メソッドを削除
    // osmdroid は非同期コールバック不要のため、onCreate で MapView の初期化を完結させた

    // [変更] GoogleMap.OnMarkerClickListener#onMarkerClick を削除
    // osmdroid では各マーカー生成時に Marker.setOnMarkerClickListener で個別にリスナーを設定する

    @Override
    public void onLocationChanged(Location geo) {
        double nowAngle = new HeadUp(mLocation.getLatitude(), mLocation.getLongitude(), geo.getLatitude(), geo.getLongitude()).getNowAngle();
        //m/sをkm/hに変換
        nowSpeed = (new HubenyDistance().calcDistance(mLocation.getLatitude(), mLocation.getLongitude(), geo.getLatitude(), geo.getLongitude())) * 3.6;
        Log.d("log", "angle:" + nowAngle);
        Log.d("log", "speed:" + nowSpeed);

        if (!USE_VIRTUAL_POSITION) mLocation = geo;

        cameraPosition(nowAngle);

        myUserInfo.setLatitude(mLocation.getLatitude());
        myUserInfo.setLongitude(mLocation.getLongitude());
        myUserInfo.setSpeed(nowSpeed);
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

    // [変更] android.location.LocationListener が要求する追加メソッド（API 29未満でも必要）
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {}

    /**
     * map上のカメラの位置，及び，アングルの操作
     *
     * @param angle 現在の端末の方角（北を0度とする）
     */
    public void cameraPosition(double angle) {
        if (cameraAngle.equals(HEAD_UP)) {
            if (Math.abs(nowCameraAngle - (float) angle) > 5)
                nowCameraAngle = (float) angle;
        } else if (cameraAngle.equals(NORTH_UP)) {
            nowCameraAngle = 0;
        }

        // [変更] LatLng(Google Maps) → GeoPoint(osmdroid)
        GeoPoint location = new GeoPoint(mLocation.getLatitude(), mLocation.getLongitude());

        // [変更] CameraPosition.Builder / CameraUpdateFactory / mMap.moveCamera
        //   → osmdroid の MapController で中心座標・ズームレベルを設定
        mMap.getController().setCenter(location);
        mMap.getController().setZoom((double) cameraLevel);

        // [変更] Google Maps bearing → osmdroid setMapOrientation（方向が逆なので符号を反転）
        mMap.setMapOrientation(-nowCameraAngle);

        // [変更] mMap.addCircle(CircleOptions) → Polygon を overlay に追加
        //   円の中心更新は Polygon のポイントリストを再生成することで対応
        if (circle == null) {
            circle = new CreateCircle().createCirclePolygon(location, searchRange);
            mMap.getOverlays().add(circle);
        } else {
            // [変更] circle.setCenter(location) → Polygon の点列を更新
            List<GeoPoint> circlePoints = Polygon.pointsAsCircle(location, searchRange);
            circle.setPoints(circlePoints);
        }

        // [変更] osmdroid では地図の再描画を明示的に呼ぶ必要がある
        mMap.invalidate();
    }

    /**
     * STUNサーバからグローバルIPとポート番号を取得した際に呼ばれるイベントリスナー
     * 位置情報取得クラスの実行のトリガーとする
     *
     * @param IP   NAT変換されたグローバルIP
     * @param port NAT変換されたグローバルPORT
     */
    @Override
    public void onGetGlobalIP_Port(String IP, int port) {
        myUserInfo.setPublicIP(IP);
        myUserInfo.setPublicPort(port);
        myUserInfo.setPrivateIP(GetPrivateIP());
        myUserInfo.setPrivatePort(socket.getLocalPort());
        myUserInfo.setPeerId(utilCommon.getPeerId());
        myUserInfo.setSpeed(nowSpeed);
        myUserInfo.setPeerId(utilCommon.getPeerId());
        myUserInfo.setLatitude(mLocation.getLatitude());
        myUserInfo.setLongitude(mLocation.getLongitude());

        p2p = new P2P(socket, myUserInfo, this);
        natTravel = NAT_TRAVEL_OK;
        p2p.p2pReceiverStart();
        p2p.signalingRegister();
        // [変更] MyLocation の内部実装が GoogleApiClient → LocationManager になっているが
        //   呼び出し側 (MainActivity) のコードは変更不要（メソッド名を維持しているため）
        MyLocation myLocation = new MyLocation(this, this, 1);
        myLocation.createGoogleApiClient();
    }

    /**
     * 端末のプライベートIPを取得
     *
     * @return privateIP address
     */
    public String GetPrivateIP() {
        String privateIP = null;
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress addr : Collections.list(n.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        privateIP = addr.getHostAddress();
                        return privateIP;
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return privateIP;
    }

    /**
     * 通信相手から詳細な位置，速度情報の取得
     */
    @Override
    public void onGetDetailUserInfo(final UserInfo userInfo, ArrayList<UserInfo> peripheralUserInfos) {
        arrangeMarker(userInfo, peripheralUserInfos);
    }

    /**
     * シグナリングサーバから周辺ユーザ情報を取得
     */
    @Override
    public void onGetPeripheralUsersInfo(ArrayList<UserInfo> peripheralUserInfos) {
        arrangeMarker(null, peripheralUserInfos);
    }

    /**
     * マップ上のマーカの制御を行う（マーカの追加，更新，削除）
     *
     * @param userInfo            マーカ位置の更新を行う通信相手情報
     * @param peripheralUserInfos 現在の周辺ユーザ数
     */
    synchronized public void arrangeMarker(final UserInfo userInfo, final ArrayList<UserInfo> peripheralUserInfos) {
        /************************************************マーカの削除************************************************/
        if (userInfo == null) {
            Log.d("Main_arrangeMarker", "マーカの削除を行う関数が呼ばれたときのマーカ数：" + markerList.size());
            Log.d("Main_arrangeMarker", "現在の周辺ユーザ数:" + peripheralUserInfos.size());
            final ArrayList<MarkerInfo> removeMarker = new ArrayList<>();
            final ArrayList<MarkerInfo> continueMarker = new ArrayList<>();

            /////////////////////////////////////////////削除すべきマーカの探索/////////////////////////////////////////////
            for (int i = 0; i < markerList.size(); i++) {
                int j = 0;
                for (; j < peripheralUserInfos.size(); j++) {
                    if (markerList.get(i).getPeerId().equals(peripheralUserInfos.get(j).getPeerId())) {
                        continueMarker.add(markerList.get(i));
                        break;
                    }
                }
                if (j == peripheralUserInfos.size()) {
                    removeMarker.add(markerList.get(i));
                }
            }
            /////////////////////////////////////////////削除すべきマーカの探索/////////////////////////////////////////////

            /////////////////////////////////////////////マーカの削除の実行/////////////////////////////////////////////
            runOnUiThread(new Runnable() {
                public void run() {
                    Log.d("Main_arrangeMarker", "マーカの削除を行う直前のマーカ数：" + markerList.size());
                    Log.d("Main_arrangeMarker", "削除するマーカ数：" + removeMarker.size());
                    if (peripheralUserInfos.size() == 0) {
                        for (int i = 0; i < markerList.size(); i++) {
                            // [変更] marker.remove() → mMap.getOverlays().remove(marker) に変更
                            // osmdroid の Marker は overlay として管理されるため、overlays から削除する
                            mMap.getOverlays().remove(markerList.get(i).getMarker());
                        }
                        markerList.clear();
                    } else {
                        for (int i = 0; i < removeMarker.size(); i++) {
                            // [変更] 同上
                            mMap.getOverlays().remove(removeMarker.get(i).getMarker());
                        }
                        markerList = continueMarker;
                    }
                    mMap.invalidate(); // [変更] 削除後に再描画
                }
            });
            return;
            /////////////////////////////////////////////マーカの削除の実行/////////////////////////////////////////////
        }
        /************************************************マーカの削除************************************************/


        /************************************************マーカの作成・更新************************************************/
        Log.d("Main_arrangeMarker", "マーカ移動を行う時のマーカ数" + markerList.size());
        waitUntilFinishAddMarker();
        /////////////////////////////////////////////マーカの更新の実行/////////////////////////////////////////////
        for (int i = 0; i < markerList.size(); i++) {
            final int tmp = i;
            if (markerList.get(i).getPeerId().equals(userInfo.getPeerId())) {
                if (userInfo.getSpeed() - myUserInfo.getSpeed() > TOLERANCE_SPEED) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            // [変更] setPosition(LatLng) → setPosition(GeoPoint)
                            markerList.get(tmp).getMarker().setPosition(
                                    new GeoPoint(userInfo.getLatitude(), userInfo.getLongitude()));
                            // [変更] BitmapDescriptorFactory.defaultMarker(HUE_RED) →
                            //   createColoredMarkerIcon(RED) で色付き丸アイコンを生成
                            markerList.get(tmp).getMarker().setIcon(
                                    createColoredMarkerIcon(android.graphics.Color.RED));
                            mMap.invalidate();
                        }
                    });
                } else {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            // [変更] setPosition(LatLng) → setPosition(GeoPoint)
                            markerList.get(tmp).getMarker().setPosition(
                                    new GeoPoint(userInfo.getLatitude(), userInfo.getLongitude()));
                            System.out.println("緯度" + userInfo.getLatitude() + "経度" + userInfo.getLongitude());
                            // [変更] BitmapDescriptorFactory.defaultMarker(HUE_GREEN) →
                            //   createColoredMarkerIcon(GREEN) で色付き丸アイコンを生成
                            markerList.get(tmp).getMarker().setIcon(
                                    createColoredMarkerIcon(android.graphics.Color.GREEN));
                            System.out.println("マーカーのセット完了");
                            mMap.invalidate();
                        }
                    });
                }
                return;
            }
        }
        /////////////////////////////////////////////マーカの更新の実行/////////////////////////////////////////////


        /////////////////////////////////////////////マーカの作成の実行/////////////////////////////////////////////
        addMarker = ADD_MARKER_PROGRESS;
        final String newPeerId = userInfo.getPeerId();
        final double newLat = userInfo.getLatitude();
        final double newLng = userInfo.getLongitude();
        runOnUiThread(new Runnable() {
            public void run() {
                // [変更] mMap.addMarker(MarkerOptions) → osmdroid の Marker を生成して overlays に追加
                Marker setMarker = new Marker(mMap);
                setMarker.setPosition(new GeoPoint(newLat, newLng));
                setMarker.setIcon(createColoredMarkerIcon(android.graphics.Color.GREEN));
                // [変更] アンカーを中央下に設定（Google Maps のデフォルト挙動と同じ）
                setMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);

                // [変更] GoogleMap.OnMarkerClickListener の代替
                //   osmdroid では各マーカーに個別リスナーを設定する
                setMarker.setOnMarkerClickListener(new Marker.OnMarkerClickListener() {
                    @Override
                    public boolean onMarkerClick(Marker marker, MapView mapView) {
                        UserInfo tapPeer = null;
                        for (int i = 0; i < p2p.getPeripheralUsers().size(); i++) {
                            if (newPeerId.equals(p2p.getPeripheralUsers().get(i).getPeerId())) {
                                tapPeer = p2p.getPeripheralUsers().get(i);
                                break;
                            }
                        }
                        if (tapPeer != null) {
                            String msg = "name:" + tapPeer.getPeerId()
                                    + "\nlatitude:" + tapPeer.getLatitude()
                                    + "\nlongitude:" + tapPeer.getLongitude()
                                    + "\nspeed:" + tapPeer.getSpeed();
                            displayToast(msg);
                        }
                        return true;
                    }
                });

                mMap.getOverlays().add(setMarker);
                markerList.add(new MarkerInfo(setMarker, newPeerId));
                addMarker = NOT_ADD_MARKER_PROGRESS;
                mMap.invalidate();
            }
        });
        /////////////////////////////////////////////マーカの作成の実行/////////////////////////////////////////////
    }
    /************************************************マーカの作成・更新************************************************/

    /**
     * [変更] BitmapDescriptorFactory（Google Maps）の代替メソッド
     * osmdroid の Marker.setIcon() に渡す Drawable を生成する
     * シンプルな塗りつぶし円をアイコンとして使用（外部リソース不要）
     *
     * @param color アイコンの色（android.graphics.Color の定数）
     */
    private Drawable createColoredMarkerIcon(int color) {
        int size = 40; // アイコンサイズ（px）
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        return new BitmapDrawable(getResources(), bmp);
    }

    private void waitUntilFinishAddMarker() {
        do {
            if (addMarker == NOT_ADD_MARKER_PROGRESS) {
                return;
            }
            try {
                Thread.sleep(100); //100ミリ秒Sleepする
            } catch (InterruptedException e) {
            }
        } while (true);
    }

    private void displayToast(final String msg) {
        Handler h = new Handler(getApplication().getMainLooper());
        h.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
