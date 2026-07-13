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
import com.example.test_g_locon.navigation.Intersection;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;

import java.io.File;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

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
    private EditText destLatInput;   // 目的地緯度入力
    private EditText destLngInput;   // 目的地経度入力
    private MaterialButton start;
    private MaterialButton end;
    private MaterialButton plus;
    private MaterialButton minus;
    private MaterialButton angle;
    private MaterialButton routeButton; // ルート設定ボタン
    private MaterialCardView inputCard;   // peerId入力カード（開始後に非表示）
    private MaterialCardView routeCard;  // 目的地入力カード（開始後に表示）
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
    private static final String SERVER_IP = "172.31.104.194"; //研究室
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
        inputCard    = findViewById(R.id.inputCard);
        routeCard    = findViewById(R.id.routeCard);
        peerId       = findViewById(R.id.peerId);
        destLatInput = findViewById(R.id.destLat);
        destLngInput = findViewById(R.id.destLng);
        start        = findViewById(R.id.start);
        end          = findViewById(R.id.end);
        plus         = findViewById(R.id.plus);
        minus        = findViewById(R.id.minus);
        angle        = findViewById(R.id.angle);
        routeButton  = findViewById(R.id.routeButton);

        start.setOnClickListener(this);
        end.setOnClickListener(this);
        routeButton.setOnClickListener(this);

        plus.setOnClickListener(this);
        minus.setOnClickListener(this);
        angle.setOnClickListener(this);

        angle.setText("N↑");

        // 実験用デフォルト目的地（実運用時は削除）
        destLatInput.setText("35.949066");
        destLngInput.setText("139.640614");

        // 目的地入力カードは開始前は非表示
        routeCard.setVisibility(View.GONE);
    }

    /** MapView と MapManager の初期化 */
    private void initMap() {
        mapView = findViewById(R.id.mapFragment);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);

        // [変更] 初期ズーム 4（大陸スケール）+ 日本付近を初期中心に
        mapView.getController().setZoom((double) cameraLevel);
        mapView.getController().setCenter(new GeoPoint(INITIAL_LATITUDE, INITIAL_LONGITUDE));

        // MapManager 初期化（地図操作を全委譲）
        mapManager = new MapManager(this, mapView);
        mapManager.initMyLocationMarker(createNavArrowBitmap());
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
            routeCard.setVisibility(View.VISIBLE); // 目的地入力カードを表示
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

        } else if (id == R.id.routeButton) {
            // 目的地を入力してルートを取得・V2V開始
            try {
                double destLat = Double.parseDouble(destLatInput.getText().toString().trim());
                double destLng = Double.parseDouble(destLngInput.getText().toString().trim());
                appController.setDestination(destLat, destLng);
                routeCard.setVisibility(View.GONE); // 入力後はカードを閉じる
                showToast("ルート取得中...");
            } catch (NumberFormatException e) {
                showToast("緯度・経度を正しく入力してください");
            }

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
        mapManager.updateMyLocation(location.getLatitude(), location.getLongitude(), (float) bearing);
    }

    @Override
    public void onPeripheralUserDetailReceived(UserInfo userInfo, ArrayList<UserInfo> allPeripheralUsers) {
        mapManager.addOrUpdateMarker(userInfo, appController.getMyUserInfo().getSpeed());
    }

    @Override
    public void onPeripheralUsersRefreshed(ArrayList<UserInfo> allPeripheralUsers) {
        mapManager.removeStaleMarkers(allPeripheralUsers);
    }

    @Override
    public void onRouteLoaded(List<Intersection> intersections) {
        mapManager.drawRoute(intersections);
        showToast("ルート取得完了: 交差点数=" + intersections.size());
    }

    @Override
    public void onIntersectionJoined(Intersection intersection) {
        mapManager.updateIntersectionMarkerJoined(intersection);
        // Toast はLogcatで確認するため表示しない（スパム防止）
    }

    @Override
    public void onIntersectionLeft(Intersection intersection) {
        mapManager.updateIntersectionMarkerLeft(intersection);
    }

    // =========================================================
    // ユーティリティ
    // =========================================================

    private void showToast(final String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    /**
     * ナビアプリ風の矢印アイコンを生成する。
     * MyLocationNewOverlay はビットマップ中心をGPS座標に合わせるため，
     * 矢印の重心がビットマップ中心に来るよう設計する。
     */
    private Bitmap createNavArrowBitmap() {
        final int SIZE = 64;
        final float cx = SIZE / 2f;
        final float cy = SIZE / 2f;
        Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // 矢印の頂点（ビットマップ中心基準）
        // 先端：上   左右裾：下   後部くびれ：中央より少し下
        float tipY   = cy - 22f;  // 先端（上）
        float baseY  = cy + 20f;  // 裾（下）
        float neckY  = cy + 6f;   // 後部くびれ
        float halfW  = 18f;       // 裾の半幅
        float neckW  = 7f;        // くびれの半幅

        // 白縁取り
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setColor(Color.WHITE);
        border.setStyle(Paint.Style.FILL);
        Path bp = new Path();
        bp.moveTo(cx, tipY - 3f);
        bp.lineTo(cx + halfW + 3f, baseY + 3f);
        bp.lineTo(cx + neckW + 2f, neckY + 2f);
        bp.lineTo(cx - neckW - 2f, neckY + 2f);
        bp.lineTo(cx - halfW - 3f, baseY + 3f);
        bp.close();
        canvas.drawPath(bp, border);

        // 矢印本体（青）
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.rgb(0, 120, 255));
        fill.setStyle(Paint.Style.FILL);
        Path ap = new Path();
        ap.moveTo(cx, tipY);
        ap.lineTo(cx + halfW, baseY);
        ap.lineTo(cx + neckW, neckY);
        ap.lineTo(cx - neckW, neckY);
        ap.lineTo(cx - halfW, baseY);
        ap.close();
        canvas.drawPath(ap, fill);

        return bmp;
    }
}
