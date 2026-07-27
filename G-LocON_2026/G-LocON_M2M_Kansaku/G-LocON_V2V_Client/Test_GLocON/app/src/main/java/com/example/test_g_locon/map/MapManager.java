package com.example.test_g_locon.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.test_g_locon.main.UserInfo;
import com.example.test_g_locon.navigation.Intersection;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * [新規] 地図表示に関するすべての操作を担うクラス。
 *
 * 旧実装では以下の処理が MainActivity に直接書かれていた:
 *   - cameraPosition()      カメラ移動・向き・ズーム変更
 *   - arrangeMarker()       マーカのCRUD（synchronized + runOnUiThread が混在）
 *   - createColoredMarkerIcon() マーカアイコン生成
 *   - waitUntilFinishAddMarker() Thread.sleep() によるビジーウェイト
 *
 * このクラスに地図操作を集約することで MainActivity は地図のことを知らなくてよくなる。
 * すべての公開メソッドはワーカスレッドから呼んでも安全（内部でUIスレッドに切り替える）。
 */
public class MapManager {

    private static final String TAG = "MapManager";

    /**
     * 速度差の警告閾値 (km/h)。周辺ユーザの速度が自分より この値を超えて速い場合に赤マーカで表示。
     *
     * [変更] 7.0 → 30.0 km/h に変更。
     *
     * 旧値 7.0 km/h の問題:
     *   屋内・机上テストでは端末が静止していても GPS ノイズにより
     *   見かけ上 3〜6 km/h の速度が算出されることがある。
     *   閾値が 7 km/h では偶然の GPS ノイズ差でピンが赤になってしまう。
     *
     * 本来の用途:
     *   車両運用において、自端末より危険なほど速い車両（例: 制限速度大幅超過）を
     *   視覚的に警告することが目的。
     *   例) 自分: 60 km/h 走行中, 相手: 100 km/h で接近 → 差 40 km/h → 赤
     *       自分: 停車中,          相手: 30 km/h で通過 → 差 30 km/h → 赤
     *
     * 30.0 km/h にすることで:
     *   - GPS ノイズ（静止時: 最大 ±5 km/h 程度）では絶対に赤にならない
     *   - 車両が明らかに危険な速度差で近づいている場合のみ赤になる
     */
    private static final double TOLERANCE_SPEED = 30.0;

    private final Context context;
    private final MapView mapView;
    /** UIスレッドへのポスト用ハンドラ */
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    /** 周辺ユーザのマーカ管理リスト */
    private final List<MarkerInfo> markerList = new ArrayList<>();

    /** 検索範囲を示す円ポリゴン */
    private Polygon searchCircle = null;

    // ---- 自位置マーカー ----
    private Marker myLocationMarker = null;

    // ---- V2V拡張フィールド ----
    /** ルートラインオーバーレイ */
    private Polyline routePolyline = null;
    /** 交差点マーカー: intersectionId → Marker */
    private final Map<String, Marker> intersectionMarkers = new HashMap<>();
    /** 交差点マーカー色: JOIN前=グレー, JOIN中=緑 */
    private static final int COLOR_INTERSECTION_DEFAULT = Color.rgb(150, 150, 150); // グレー
    private static final int COLOR_INTERSECTION_JOINED  = Color.rgb(0, 200, 80);   // 緑

    /**
     * [変更] 旧実装の waitUntilFinishAddMarker() は Thread.sleep(100) のビジーウェイトだった。
     * AtomicBoolean に置き換えることでスピンループを排除し、スレッド安全性を向上させた。
     */
    private final AtomicBoolean isAddingMarker = new AtomicBoolean(false);

    /**
     * マーカタップ時にユーザ情報を表示するコールバック
     */
    public interface OnMarkerTapListener {
        void onMarkerTapped(String peerId);
    }

    private OnMarkerTapListener markerTapListener;

    public MapManager(Context context, MapView mapView) {
        this.context = context;
        this.mapView = mapView;
    }

    /** マーカタップ時のリスナーを設定する */
    public void setOnMarkerTapListener(OnMarkerTapListener listener) {
        this.markerTapListener = listener;
    }

    // =========================================================
    // 自位置マーカー
    // =========================================================

    /** 自位置矢印マーカーを初期化してオーバーレイに追加する */
    public void initMyLocationMarker(Bitmap arrowBitmap) {
        uiHandler.post(() -> {
            myLocationMarker = new Marker(mapView);
            myLocationMarker.setIcon(new BitmapDrawable(context.getResources(), arrowBitmap));
            myLocationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            myLocationMarker.setInfoWindow(null);
            mapView.getOverlays().add(myLocationMarker);
        });
    }

    /** GPS更新のたびに自位置マーカーの座標と向きを更新する */
    public void updateMyLocation(double lat, double lng, float bearing) {
        uiHandler.post(() -> {
            if (myLocationMarker == null) return;
            myLocationMarker.setPosition(new GeoPoint(lat, lng));
            myLocationMarker.setRotation(-bearing); // osmdroidは反時計回り正
            mapView.invalidate();
        });
    }

    // =========================================================
    // カメラ操作
    // =========================================================

    /**
     * 地図カメラの中心・向きを更新し、検索範囲円を再描画する。
     * 旧実装の cameraPosition() に相当するが、UIロジックのみに純化している。
     *
     * [変更] zoomLevel パラメータは受け取るが setZoom は呼ばない。
     * 理由: このメソッドは uiHandler.post() でキューに積まれるため、
     * GPS更新のたびに呼ばれるラムダが「古い zoomLevel」でズームを上書きしてしまい、
     * ユーザが＋/－ボタンで変更したズームが即座に戻る問題があった。
     * ズームは MainActivity の onClick(plus/minus) および onClick(start) で
     * mapView.getController().setZoom() を直接呼ぶことで管理する。
     *
     * @param latitude    中心緯度
     * @param longitude   中心経度
     * @param zoomLevel   (未使用) 互換性のため残しているが setZoom は呼ばない
     * @param bearing     地図の向き（北を0度）
     * @param searchRange 検索半径 (メートル)
     */
    public void updateCamera(final double latitude, final double longitude,
                             final float zoomLevel, final float bearing,
                             final double searchRange) {
        uiHandler.post(() -> {
            GeoPoint location = new GeoPoint(latitude, longitude);

            // カメラの中心・向きを設定（ズームは MainActivity 側で直接管理）
            mapView.getController().setCenter(location);
            // osmdroid は Google Maps と符号が逆なので反転する
            mapView.setMapOrientation(-bearing);

            // 検索範囲の円ポリゴンを更新
            if (searchCircle == null) {
                searchCircle = new CreateCircle().createCirclePolygon(location, searchRange);
                mapView.getOverlays().add(searchCircle);
            } else {
                // 既存ポリゴンの点列を新しい中心座標で更新
                List<GeoPoint> circlePoints = Polygon.pointsAsCircle(location, searchRange);
                searchCircle.setPoints(circlePoints);
            }

            mapView.invalidate(); // 再描画
        });
    }

    // =========================================================
    // マーカ操作
    // =========================================================

    /**
     * 周辺ユーザのマーカを追加または更新する。
     * 既存マーカがあれば位置と色を更新、なければ新規作成する。
     *
     * 旧実装の arrangeMarker(UserInfo, ArrayList) のうち「作成・更新」部分に相当。
     * synchronized + runOnUiThread の入れ子構造を Handler + AtomicBoolean で整理した。
     *
     * @param userInfo      表示するユーザ情報
     * @param mySpeed       自端末の速度 (km/h)。マーカ色の判定に使用
     */
    public void addOrUpdateMarker(final UserInfo userInfo, final double mySpeed) {
        // 他スレッドでマーカ追加中の場合は完了を待つ
        // [変更] Thread.sleep(100) のビジーウェイト → AtomicBoolean で安全に待機
        waitForMarkerReady();

        // 既存マーカの更新を試みる
        synchronized (markerList) {
            for (int i = 0; i < markerList.size(); i++) {
                if (markerList.get(i).getPeerId().equals(userInfo.getPeerId())) {
                    final int idx = i;
                    final boolean isFaster = (userInfo.getSpeed() - mySpeed) > TOLERANCE_SPEED;
                    uiHandler.post(() -> {
                        markerList.get(idx).getMarker().setPosition(
                                new GeoPoint(userInfo.getLatitude(), userInfo.getLongitude()));
                        markerList.get(idx).getMarker().setIcon(
                                createColoredMarkerIcon(isFaster ? Color.RED : Color.GREEN));
                        mapView.invalidate();
                    });
                    return;
                }
            }
        }

        // 既存マーカがなければ新規作成
        isAddingMarker.set(true);
        uiHandler.post(() -> {
            Marker marker = new Marker(mapView);
            marker.setPosition(new GeoPoint(userInfo.getLatitude(), userInfo.getLongitude()));
            marker.setIcon(createColoredMarkerIcon(Color.GREEN));
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);

            // マーカタップ時のコールバック設定
            final String peerId = userInfo.getPeerId();
            marker.setOnMarkerClickListener((m, mv) -> {
                if (markerTapListener != null) {
                    markerTapListener.onMarkerTapped(peerId);
                }
                return true;
            });

            mapView.getOverlays().add(marker);
            synchronized (markerList) {
                markerList.add(new MarkerInfo(marker, peerId));
            }
            isAddingMarker.set(false);
            mapView.invalidate();

            Log.d(TAG, "マーカ追加: peerId=" + peerId);
        });
    }

    /**
     * 周辺ユーザ一覧と現在のマーカリストを比較し、不要なマーカを削除する。
     * 旧実装の arrangeMarker(null, ArrayList) に相当。
     *
     * @param currentUsers 現在シグナリングサーバが返した周辺ユーザ一覧
     */
    public void removeStaleMarkers(final ArrayList<UserInfo> currentUsers) {
        Log.d(TAG, "マーカ削除チェック: 現在マーカ数=" + markerList.size()
                + " 周辺ユーザ数=" + currentUsers.size());

        final List<MarkerInfo> toRemove = new ArrayList<>();
        final List<MarkerInfo> toKeep = new ArrayList<>();

        synchronized (markerList) {
            for (MarkerInfo info : markerList) {
                boolean found = false;
                for (UserInfo user : currentUsers) {
                    if (info.getPeerId().equals(user.getPeerId())) {
                        found = true;
                        break;
                    }
                }
                if (found) toKeep.add(info);
                else       toRemove.add(info);
            }
        }

        uiHandler.post(() -> {
            // [変更] marker.remove() → overlays からの削除（osmdroid の方式）
            for (MarkerInfo info : toRemove) {
                mapView.getOverlays().remove(info.getMarker());
            }
            synchronized (markerList) {
                if (currentUsers.isEmpty()) {
                    markerList.clear();
                } else {
                    markerList.clear();
                    markerList.addAll(toKeep);
                }
            }
            mapView.invalidate();
            Log.d(TAG, "マーカ削除完了: 削除数=" + toRemove.size());
        });
    }

    // =========================================================
    // V2V拡張: ルート・交差点マーカー
    // =========================================================

    /**
     * ルートラインと交差点マーカーを地図上に描画する。
     * onRouteLoaded() から呼ばれる。既存のルート・交差点マーカーは削除して再描画する。
     *
     * @param intersections ルート上の交差点リスト（ルート順）
     */
    public void drawRoute(List<Intersection> intersections) {
        uiHandler.post(() -> {
            // 既存ルートラインを削除
            if (routePolyline != null) {
                mapView.getOverlays().remove(routePolyline);
            }
            // 既存交差点マーカーを削除
            for (Marker m : intersectionMarkers.values()) {
                mapView.getOverlays().remove(m);
            }
            intersectionMarkers.clear();

            // ルートライン描画
            List<GeoPoint> points = new ArrayList<>();
            for (Intersection i : intersections) {
                points.add(new GeoPoint(i.getLat(), i.getLng()));
            }
            routePolyline = new Polyline();
            routePolyline.setPoints(points);
            routePolyline.getOutlinePaint().setColor(Color.rgb(0, 120, 255));
            routePolyline.getOutlinePaint().setStrokeWidth(8f);
            mapView.getOverlays().add(routePolyline);

            // 交差点マーカー描画（エッジサーバが登録されている交差点のみ）
            for (Intersection i : intersections) {
                if (i.hasEdgeServer()) {
                    addIntersectionMarker(i, false);
                }
            }
            mapView.invalidate();
            Log.d(TAG, "ルート描画完了: 交差点数=" + intersections.size());
        });
    }

    /**
     * 交差点マーカーの状態をJOIN済みに更新する（黄色表示）。
     * onIntersectionJoined() から呼ばれる。
     */
    public void updateIntersectionMarkerJoined(Intersection intersection) {
        updateIntersectionMarkerColor(intersection.getIntersectionId(), true);
    }

    /**
     * 交差点マーカーの状態をLEAVE済みに戻す（青色表示）。
     * onIntersectionLeft() から呼ばれる。
     */
    public void updateIntersectionMarkerLeft(Intersection intersection) {
        updateIntersectionMarkerColor(intersection.getIntersectionId(), false);
    }

    private void addIntersectionMarker(Intersection intersection, boolean joined) {
        Marker marker = new Marker(mapView);
        marker.setPosition(new GeoPoint(intersection.getLat(), intersection.getLng()));
        marker.setIcon(createSmallCircleIcon(joined ? COLOR_INTERSECTION_JOINED : COLOR_INTERSECTION_DEFAULT));
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        marker.setTitle(intersection.getIntersectionId());
        mapView.getOverlays().add(marker);
        intersectionMarkers.put(intersection.getIntersectionId(), marker);
    }

    private void updateIntersectionMarkerColor(String intersectionId, boolean joined) {
        uiHandler.post(() -> {
            Marker marker = intersectionMarkers.get(intersectionId);
            if (marker != null) {
                marker.setIcon(createSmallCircleIcon(
                        joined ? COLOR_INTERSECTION_JOINED : COLOR_INTERSECTION_DEFAULT));
                mapView.invalidate();
            }
        });
    }

    /** 交差点マーカー用アイコンを生成する（デフォルト:オレンジ六角形, JOIN中:黄色） */
    private Drawable createSmallCircleIcon(int color) {
        final int SIZE = 36;
        Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        float cx = SIZE / 2f;
        float cy = SIZE / 2f;
        float r = SIZE / 2f - 2f;

        // 白縁取り六角形
        paint.setColor(Color.WHITE);
        canvas.drawPath(hexagonPath(cx, cy, r + 2f), paint);

        // 本体六角形
        paint.setColor(color);
        canvas.drawPath(hexagonPath(cx, cy, r), paint);

        // 中央に「ES」テキスト
        paint.setColor(Color.WHITE);
        paint.setTextSize(10f);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        canvas.drawText("ES", cx, cy + 4f, paint);

        return new BitmapDrawable(context.getResources(), bmp);
    }

    private Path hexagonPath(float cx, float cy, float r) {
        Path path = new Path();
        for (int i = 0; i < 6; i++) {
            double angle = Math.PI / 180 * (60 * i - 30);
            float x = cx + r * (float) Math.cos(angle);
            float y = cy + r * (float) Math.sin(angle);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.close();
        return path;
    }

    // =========================================================
    // ユーティリティ
    // =========================================================

    /**
     * [変更] 旧実装の waitUntilFinishAddMarker() を AtomicBoolean を使った安全な待機に変更。
     * 最大待機時間を設けてデッドロックを防ぐ。
     */
    private void waitForMarkerReady() {
        int waitCount = 0;
        while (isAddingMarker.get() && waitCount < 20) { // 最大2秒待機
            try {
                Thread.sleep(100);
                waitCount++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * [変更] 蛍光丸点 → 地図アプリらしいティアドロップ型ピンに変更。
     *
     * 形状: 円形ヘッド（上部）＋ 先端を下に向けた三角形（尾部）
     * 構成:
     *   1. 白い縁取り（影代わりに視認性を高める）
     *   2. 指定色で塗りつぶしたピン本体
     *   3. 白い小円（中央ドット）でマップピンらしさを演出
     *
     * アンカーは ANCHOR_CENTER / ANCHOR_BOTTOM（尾部先端が座標に対応）。
     *
     * @param color ピン色（Color.RED / Color.GREEN など）
     */
    private Drawable createColoredMarkerIcon(int color) {
        // ピンの描画サイズ (px)
        final int W = 48;   // 幅
        final int H = 72;   // 高さ（ヘッド円 + 尾部）
        final float cx = W / 2f;          // 水平中心
        final float headR = W / 2f - 3f;  // ヘッド円の半径（縁取り分を除く）
        final float headCy = headR + 3f;  // ヘッド円の中心Y（上端に余白3px）

        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // ---- 白縁取り（ヘッド円 + 尾部三角形を白で一回り大きく描く） ----
        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.FILL);

        // 縁取り円
        canvas.drawCircle(cx, headCy, headR + 3f, borderPaint);

        // 縁取り三角形（尾部）
        Path borderTail = new Path();
        borderTail.moveTo(cx - headR * 0.55f - 2f, headCy + headR * 0.7f);
        borderTail.lineTo(cx + headR * 0.55f + 2f, headCy + headR * 0.7f);
        borderTail.lineTo(cx, H - 1f);
        borderTail.close();
        canvas.drawPath(borderTail, borderPaint);

        // ---- ピン本体（指定色） ----
        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setColor(color);
        fillPaint.setStyle(Paint.Style.FILL);

        // ヘッド円
        canvas.drawCircle(cx, headCy, headR, fillPaint);

        // 尾部三角形
        Path tail = new Path();
        tail.moveTo(cx - headR * 0.55f, headCy + headR * 0.7f);
        tail.lineTo(cx + headR * 0.55f, headCy + headR * 0.7f);
        tail.lineTo(cx, H - 4f);
        tail.close();
        canvas.drawPath(tail, fillPaint);

        // ---- 中央白ドット（地図ピンらしさの演出） ----
        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(Color.WHITE);
        dotPaint.setAlpha(200);
        canvas.drawCircle(cx, headCy, headR * 0.32f, dotPaint);

        return new BitmapDrawable(context.getResources(), bmp);
    }
}
