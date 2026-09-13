package com.example.test_g_locon.main;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.content.pm.PackageManager;
import android.location.Location;
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
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity implements View.OnClickListener, OnMapReadyCallback, GoogleMap.OnMarkerClickListener, LocationListener, ISTUNServerClient, IP2P {

    private EditText peerId;
    private Button start;
    private Button end;
    private Button plus;
    private Button minus;
    private Button angle;
    private GoogleMap mMap;
    private Location mLocation;
    private float nowCameraAngle = 0;
    private double nowSpeed = 0;
    private List<MarkerInfo> markerList;
    private Circle circle = null;
    final static private double TOLERANCE_SPEED = 7; //速度差
    private float cameraLevel = 18.0f;
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
    final private String serverIP = "192.168.137.1"; // PCホットスポット
//    final private String serverIP = "172.20.10.4"; // iPhoneテザリング（クライアントアイソレーションあり）
//    final private String serverIP = "192.168.0.15"; // 自宅
    final private boolean USE_VIRTUAL_POSITION = false; // TODO: 仮想位置を使用する場合はtrue。
    final private double initialLatitude = 0.0; // TODO: 仮想位置の緯度を設定。
    final private double initialLongitude = 0.0; // TODO: 仮想位置の経度を設定。


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

        SupportMapFragment mapFragment
                = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.mapFragment);
        mapFragment.getMapAsync(this);
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
            //p2p.fileInputMemorySendData();
            //p2p.fileInputMemoryReceiveData();
            //p2p.signalingDelete();
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

    @Override
    public void onMapReady(GoogleMap googleMap) {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        mMap = googleMap;
        mMap.setMyLocationEnabled(true);
        mMap.setOnMarkerClickListener(this);
    }

    @Override
    public boolean onMarkerClick(@NonNull Marker marker) {
        String tapPeerID = null;
        UserInfo tapPeer = null;
        for (MarkerInfo info : markerList) {
            if (marker.equals(info.getMarker())) {
                tapPeerID = info.getPeerId();
                break;
            }
        }

        for(int i = 0; i < p2p.getPeripheralUsers().size(); i++){
            if(tapPeerID.equals(p2p.getPeripheralUsers().get(i).getPeerId())){
                tapPeer = p2p.getPeripheralUsers().get(i);
                break;
            }
        }

        String msg = "name:" +tapPeer.getPeerId()
                +"\nlatitude:"+tapPeer.getLatitude()
                +"\nlongitude:"+tapPeer.getLongitude()
                +"\nspeed:"+tapPeer.getSpeed();
        displayToast(msg);

        return false;
    }


    @Override
    public void onLocationChanged(Location geo) {
        double nowAngle = new HeadUp(mLocation.getLatitude(), mLocation.getLongitude(), geo.getLatitude(), geo.getLongitude()).getNowAngle();
        //m/sをkm/hに変換
        nowSpeed = (new HubenyDistance().calcDistance(mLocation.getLatitude(), mLocation.getLongitude(), geo.getLatitude(), geo.getLongitude())) * 3.6;
        Log.d("log", "angle:" + nowAngle);
        Log.d("log", "speed:" + nowSpeed);

        if (!USE_VIRTUAL_POSITION) mLocation = geo; // 変更

        cameraPosition(nowAngle);


        //myUserInfo.setLatitude(geo.getLatitude());
        //myUserInfo.setLongitude(geo.getLongitude());
        myUserInfo.setLatitude(mLocation.getLatitude()); // 追加してください。
        myUserInfo.setLongitude(mLocation.getLongitude()); // 追加してください。
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

    /**
     * map上のカメラの位置，及び，アングルの操作
     *
     * @param angle //現在の端末の方角（北を0度とする）
     */
    public void cameraPosition(double angle) {
        if(cameraAngle.equals(HEAD_UP)) {
            if (Math.abs(nowCameraAngle - (float) angle) > 5)
                nowCameraAngle = (float) angle;
        }
        else if(cameraAngle.equals(NORTH_UP)){
            nowCameraAngle = 0;
        }
        LatLng location = new LatLng(mLocation.getLatitude(), mLocation.getLongitude());
        CameraPosition cameraPos = new CameraPosition.Builder().target(location).zoom(cameraLevel).bearing(nowCameraAngle).tilt(60).build();
        mMap.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPos));
        if (circle == null) {
            circle = mMap.addCircle(new CreateCircle().createCircleOptions(location, searchRange)); //検索半径の円を表示
        } else {
            circle.setCenter(location);
        }

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
        //myUserInfo.setLatitude(35.409716);
        //myUserInfo.setLongitude(139.588568);
        myUserInfo.setLatitude(mLocation.getLatitude());
        myUserInfo.setLongitude(mLocation.getLongitude());

        p2p = new P2P(socket, myUserInfo, this);
        natTravel = NAT_TRAVEL_OK;
        p2p.p2pReceiverStart();
        p2p.signalingRegister();
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
     * マーカの操作を呼び出す
     *
     * @param userInfo            通信相手の情報
     * @param peripheralUserInfos 現在の近接ユーザ数
     */
    @Override
    public void onGetDetailUserInfo(final UserInfo userInfo, ArrayList<UserInfo> peripheralUserInfos) {
        arrangeMarker(userInfo, peripheralUserInfos);
    }


    /**
     * シグナリングサーバから周辺ユーザ情報を取得
     * マーカの操作を呼び出す
     *
     * @param peripheralUserInfos 周辺ユーザ情報
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
            final ArrayList<MarkerInfo> removeMarker = new ArrayList<>(); //削除するマーカを格納
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
                            markerList.get(i).getMarker().remove();
                        }
                        markerList.clear();
                    } else {
                        for (int i = 0; i < removeMarker.size(); i++) {
                            removeMarker.get(i).getMarker().remove();
                        }
                        markerList = continueMarker;
                    }
                }
            });
            return;
            /////////////////////////////////////////////マーカの削除の実行/////////////////////////////////////////////
        }
        /************************************************マーカの削除************************************************/


        /************************************************マーカの作成・更新************************************************/
        Log.d("Main_arrangeMarker", "マーカ移動を行う時のマーカ数" + markerList.size());
        waitUntilFinishAddMarker(); //markerが他スレッドで作成中の場合は終了を待つ
        /////////////////////////////////////////////マーカの更新の実行/////////////////////////////////////////////
        for (int i = 0; i < markerList.size(); i++) {
            final int tmp = i;
            if (markerList.get(i).getPeerId().equals(userInfo.getPeerId())) { //ぬるぽでる
                if (userInfo.getSpeed() - myUserInfo.getSpeed() > TOLERANCE_SPEED) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            markerList.get(tmp).getMarker().setPosition(new LatLng(userInfo.getLatitude(), userInfo.getLongitude()));
                            markerList.get(tmp).getMarker().setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED));
                        }
                    });
                } else {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            markerList.get(tmp).getMarker().setPosition(new LatLng(userInfo.getLatitude(), userInfo.getLongitude()));
                            System.out.println("緯度"+userInfo.getLatitude()+"経度"+userInfo.getLongitude());
                            markerList.get(tmp).getMarker().setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN));
                            System.out.println("マーカーのセット完了");
                        }
                    });
                }
                return;
            }
        }
        /////////////////////////////////////////////マーカの更新の実行/////////////////////////////////////////////


        /////////////////////////////////////////////マーカの作成の実行/////////////////////////////////////////////
        addMarker = ADD_MARKER_PROGRESS;
        runOnUiThread(new Runnable() {
            public void run() {
                Marker setMarker = mMap.addMarker(new MarkerOptions().position(new LatLng(userInfo.getLatitude(), userInfo.getLongitude())).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));
                String setPeerId = userInfo.getPeerId();
                markerList.add(new MarkerInfo(setMarker, setPeerId));
                addMarker = NOT_ADD_MARKER_PROGRESS;
            }
        });
        /////////////////////////////////////////////マーカの作成の実行/////////////////////////////////////////////
    }

    /************************************************マーカの作成・更新************************************************/


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
