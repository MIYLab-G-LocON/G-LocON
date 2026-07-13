package com.example.test_g_locon.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;

import androidx.core.content.ContextCompat;

/**
 * [変更] MyLocation.java を location パッケージへ移動し、LocationProvider に改名。
 *
 * 旧クラス名 "MyLocation" は責務が不明確だった。
 * "LocationProvider" とすることで「GPS位置情報を提供するクラス」であることを明示する。
 *
 * また旧実装のメソッド名 createGoogleApiClient() は Google Play Services 依存時代の名残であり、
 * startLocationUpdates() / stopLocationUpdates() に改名して意図を明確にした。
 */
public class LocationProvider {

    private LocationManager locationManager;
    private final Context context;
    private final LocationListener locationListener;
    /** 位置情報の最小更新間隔 (ミリ秒) */
    private final long updateIntervalMs;

    /**
     * @param context          コンテキスト
     * @param locationListener 位置情報更新を受け取るリスナー (AppController が実装)
     * @param updateIntervalMs 更新間隔 (ミリ秒)
     */
    public LocationProvider(Context context, LocationListener locationListener, long updateIntervalMs) {
        this.context = context;
        this.locationListener = locationListener;
        this.updateIntervalMs = updateIntervalMs;
    }

    /**
     * GPS位置情報の取得を開始する。
     * [変更] 旧名: createGoogleApiClient() → startLocationUpdates() に改名
     *
     * [追加] NETWORK_PROVIDER フォールバックを追加。
     *   旧実装は GPS_PROVIDER のみだったため、屋内・衛星未測位状態では
     *   onLocationChanged が一切呼ばれず P2P通信が開始されない問題があった。
     *   NETWORK_PROVIDER (WiFi/基地局測位) を併用することで屋内テスト時でも
     *   位置情報更新が得られ、signalingSearch が確実に実行されるようにする。
     */
    public void startLocationUpdates() {
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            // GPS プロバイダ（屋外・高精度）
            // [バグ修正] requestLocationUpdates() 4引数版は呼び出しスレッドに Looper が必要。
            // onGetGlobalIP_Port() は STUNServerClientReceiver の Executor スレッド（Looper なし）
            // から呼ばれるため、4引数版では IllegalArgumentException が発生し GPS が起動しなかった。
            // 5引数版で Looper.getMainLooper() を明示することで、どのスレッドから呼ばれても
            // コールバックをメインスレッドに届けることができる。
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    updateIntervalMs,
                    0f,
                    locationListener,
                    Looper.getMainLooper()  // [バグ修正] Looper なしスレッドから呼ばれても安全
            );
            // [追加] ネットワークプロバイダ（WiFi/基地局測位: 屋内や衛星未取得時のフォールバック）
            // GPS が使えない環境でも onLocationChanged を発火させ、signalingSearch を継続させる
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        updateIntervalMs,
                        0f,
                        locationListener,
                        Looper.getMainLooper()  // [バグ修正] 同上
                );
            }
        }
    }

    /**
     * GPS位置情報の取得を停止する。
     * [変更] 旧名: stopGetLocation() → stopLocationUpdates() に改名
     */
    public void stopLocationUpdates() {
        if (locationManager != null) {
            locationManager.removeUpdates(locationListener);
        }
    }
}
