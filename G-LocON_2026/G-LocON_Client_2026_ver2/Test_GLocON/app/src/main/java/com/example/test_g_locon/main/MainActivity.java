package com.example.test_g_locon.main;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;

import com.example.test_g_locon.R;
import com.example.test_g_locon.controller.AppController;
import com.example.test_g_locon.controller.IAppController;
import com.example.test_g_locon.map.MapManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.io.File;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.ArrayList;

/**
 * アプリのメイン画面を担う Activity クラス。
 *
 * [変更] レイアウトを全面マップ表示にリデザイン
 *   - 旧: 固定サイズの MapView + 画面下部にボタン群
 *   - 新: MapView 全画面 + フローティング UI（現代の地図アプリ準拠）
 *
 * ボタン配置の設計方針（人間工学）:
 *   - 入力カード:  画面上部中央（起動直後に視認しやすい位置）
 *   - ズーム +/-:  右下（利き手の親指で届く位置、OSMand/Google Maps と同じ）
 *   - コンパス:    右上（誤操作しにくい位置）
 *   - 終了ボタン:  左下（ズーム操作と干渉しない場所、かつ誤タップを防ぐ）
 *
 * 初期ズームを 4（大陸スケール）に変更。
 */
public class MainActivity extends AppCompatActivity implements View.OnClickListener, IAppController {

    // ---- UI部品 ----
    private EditText peerId;
    private MaterialButton start;
    private MaterialButton end;
    private MaterialButton plus;
    private MaterialButton minus;
    private MaterialButton angle;
    private MaterialCardView inputCard;   // peerId入力カード（開始後に非表示）
    private MapView mapView;

    // ---- 委譲先 ----
    private MapManager mapManager;
    private AppController appController;

    // ---- カメラ状態 ----
    // [変更] 初期ズームレベルを 18 → 4 に変更（大陸が見える縮尺）
    private float cameraLevel = 4.0f;
    private float nowCameraAngle = 0;
    private static final String HEAD_UP  = "HEAD_UP";
    private static final String NORTH_UP = "NORTH_UP";
    // [変更] デフォルトを HEAD_UP → NORTH_UP に変更。
    // 起動直後は北固定の方が地図の向きが安定して見やすい。
    // ボタンを押すことで HEAD_UP（進行方向向き）に切り替えられる。
    private String cameraMode = NORTH_UP;
    /**
     * 検索円の表示半径 (メートル)。
     * AppController.searchRange と一致させること（サーバ検索範囲 = 地図表示範囲）。
     * [変更] 100 → 200 : AppController 側に合わせ屋内GPS誤差に対応。
     */
    private final double searchRange = 200;

    // TODO: サーバのIPアドレスを設定してください
    private static final String SERVER_IP = "172.31.115.240"; //研究室
//    private static final String SERVER_IP = "192.168.0.207"; // 自宅
    // TODO: 仮想位置を使用する場合は true に変更してください
    private static final boolean USE_VIRTUAL_POSITION = false;
    // TODO: 仮想位置の緯度・経度を設定してください（デフォルト: 日本付近）
    private static final double INITIAL_LATITUDE  = 35.0;
    private static final double INITIAL_LONGITUDE = 136.0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // [変更] 画面を常時点灯・フルスクリーン化（地図アプリとして運用中に画面が消えないよう）
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // osmdroid 設定（setContentView より前に必須）
        Configuration.getInstance().setUserAgentValue(getPackageName());
        Configuration.getInstance().setOsmdroidBasePath(getCacheDir());
        Configuration.getInstance().setOsmdroidTileCache(new File(getCacheDir(), "osmdroid"));

        setContentView(R.layout.activity_main);

        initViews();
        initMap();

        DatagramSocket socket = createDatagramSocket();
        UtilCommon utilCommon = (UtilCommon) getApplication();
        appController = new AppController(
                this, utilCommon, socket, this,
                USE_VIRTUAL_POSITION, INITIAL_LATITUDE, INITIAL_LONGITUDE
        );
    }

    /** UI部品の取得とリスナー設定 */
    private void initViews() {
        inputCard = findViewById(R.id.inputCard);
        peerId    = findViewById(R.id.peerId);
        start     = findViewById(R.id.start);
        end       = findViewById(R.id.end);
        plus      = findViewById(R.id.plus);
        minus     = findViewById(R.id.minus);
        angle     = findViewById(R.id.angle);

        start.setOnClickListener(this);
        end.setOnClickListener(this);

        // [変更] ズーム・コンパスボタンは起動直後から常時操作可能
        // 旧実装では START ボタン後にリスナーをセットしていた
        plus.setOnClickListener(this);
        minus.setOnClickListener(this);
        angle.setOnClickListener(this);

        // [変更] デフォルトが NORTH_UP なので初期ボタンテキストを "N↑" に設定
        // （ボタンテキストは「現在のモード」を示す。押すと HEAD_UP に切り替わり "H↑" になる）
        angle.setText("N↑");
    }

    /** MapView と MapManager の初期化 */
    private void initMap() {
        mapView = findViewById(R.id.mapFragment);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);

        // [変更] 初期ズーム 4（大陸スケール）+ 日本付近を初期中心に
        mapView.getController().setZoom((double) cameraLevel);
        mapView.getController().setCenter(new GeoPoint(INITIAL_LATITUDE, INITIAL_LONGITUDE));

        // 自端末位置の青点オーバーレイ
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            MyLocationNewOverlay myLocationOverlay = new MyLocationNewOverlay(mapView);
            myLocationOverlay.enableMyLocation();
            mapView.getOverlays().add(myLocationOverlay);
        }

        // MapManager 初期化（地図操作を全委譲）
        mapManager = new MapManager(this, mapView);
        mapManager.setOnMarkerTapListener(tappedPeerId -> {
            if (appController.getP2p() != null) {
                for (UserInfo user : appController.getP2p().getPeripheralUsers()) {
                    if (tappedPeerId.equals(user.getPeerId())) {
                        showToast("📍 " + user.getPeerId()
                                + "\n緯度: " + String.format("%.5f", user.getLatitude())
                                + "\n経度: " + String.format("%.5f", user.getLongitude())
                                + "\n速度: " + String.format("%.1f", user.getSpeed()) + " km/h");
                        break;
                    }
                }
            }
        });
    }

    private DatagramSocket createDatagramSocket() {
        try {
            DatagramSocket socket = new DatagramSocket();
            socket.setReuseAddress(true);
            return socket;
        } catch (SocketException e) {
            throw new RuntimeException("DatagramSocket の生成に失敗", e);
        }
    }

    // =========================================================
    // ライフサイクル
    // =========================================================

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
    }

    // [追加] アプリ終了時に AppController のリソース（定期検索スケジューラ・GPS）を解放する
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (appController != null) {
            appController.stop();
        }
    }

    // =========================================================
    // View.OnClickListener
    // =========================================================

    @Override
    public void onClick(View v) {
        int id = v.getId();

        if (id == R.id.start) {
            // サーバ設定をグローバルストアに保存
            UtilCommon utilCommon = (UtilCommon) getApplication();
            utilCommon.setSignalingServerIP(SERVER_IP);
            utilCommon.setSignalingServerPort(55555);
            utilCommon.setStunServerIP(SERVER_IP);
            utilCommon.setStunServerPort(55554);
            utilCommon.setPeerId(peerId.getText().toString());

            // [変更] 入力カード全体を非表示（旧: peerId/startだけ INVISIBLE）
            // GONE にすることでレイアウトスペースも解放し地図が広く見える
            inputCard.setVisibility(View.GONE);
            end.setVisibility(View.VISIBLE);

            // [変更] START後に即座にナビズーム (15) へ切り替え
            cameraLevel = 18.0f;
            mapView.getController().setZoom((double) cameraLevel);

            // [追加] START直後にカメラを自位置へフォーカスし検索円を描画する。
            // GPS発火前なので座標は初期値 (INITIAL_LATITUDE, INITIAL_LONGITUDE) になるが、
            // 検索円・ズームを即時反映し、GPS発火後に onLocationUpdated() で実座標へ更新される。
            mapManager.updateCamera(
                    INITIAL_LATITUDE, INITIAL_LONGITUDE,
                    cameraLevel, nowCameraAngle, searchRange
            );

            appController.start();

        } else if (id == R.id.end) {
            // [バグ修正] System.exit(1) はプロセスを即時強制終了するため onDestroy() が呼ばれず、
            // signalingDelete() がサーバに届かなかった。その結果：
            //   - サーバのユーザリストに終了した端末が残り続ける
            //   - 相手端末の SEARCH 結果にも含まれ続けるため、ピンが消えない
            //
            // 修正: stop() で DELETE を送信してから 600ms 後に finishAffinity() で終了する。
            // 600ms は UDP 送信スレッドが完了するまでの待機時間。
            // stop() は AppController 内部で p2p = null にするため onDestroy() からの
            // 二重呼び出しは安全に無視される。
            end.setEnabled(false); // 二重タップ防止
            appController.stop();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                finishAffinity(); // onDestroy() を経由して正常終了
            }, 600);

        } else if (id == R.id.plus) {
            cameraLevel = Math.min(cameraLevel + 1f, 21f);
            // [バグ修正] updateCamera は uiHandler.post() でキューに積まれるため、
            // ボタン押下後に発火するGPS更新の updateCamera が古い zoomLevel で
            // 上書きしてしまいズームが戻る問題があった。
            // ズームは常にここで直接 setZoom() し、updateCamera 側では setZoom しない設計にした。
            mapView.getController().setZoom((double) cameraLevel);

        } else if (id == R.id.minus) {
            cameraLevel = Math.max(cameraLevel - 1f, 1f);
            mapView.getController().setZoom((double) cameraLevel);

        } else if (id == R.id.angle) {
            // [変更] ボタンテキストを短く「H↑」「N↑」に変更（旧: "HEADUP" / "NORTHUP"）
            if (cameraMode.equals(HEAD_UP)) {
                cameraMode = NORTH_UP;
                angle.setText("N↑");
            } else {
                cameraMode = HEAD_UP;
                angle.setText("H↑");
            }
        }
    }

    // =========================================================
    // IAppController 実装
    // =========================================================

    @Override
    public void onLocationUpdated(Location location, double bearing, double speed) {
        if (cameraMode.equals(HEAD_UP)) {
            if (Math.abs(nowCameraAngle - (float) bearing) > 5) {
                nowCameraAngle = (float) bearing;
            }
        } else {
            nowCameraAngle = 0;
        }
        mapManager.updateCamera(
                location.getLatitude(), location.getLongitude(),
                cameraLevel, nowCameraAngle, searchRange
        );
    }

    @Override
    public void onPeripheralUserDetailReceived(UserInfo userInfo, ArrayList<UserInfo> allPeripheralUsers) {
        mapManager.addOrUpdateMarker(userInfo, appController.getMyUserInfo().getSpeed());
    }

    @Override
    public void onPeripheralUsersRefreshed(ArrayList<UserInfo> allPeripheralUsers) {
        mapManager.removeStaleMarkers(allPeripheralUsers);
    }

    // =========================================================
    // ユーティリティ
    // =========================================================

    private void showToast(final String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }
}
