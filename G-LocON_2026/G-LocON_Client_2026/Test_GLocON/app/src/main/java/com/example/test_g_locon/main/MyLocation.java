package com.example.test_g_locon.main;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
// [変更] android.location.LocationListener を使用（GMS LocationListenerから変更）
import android.location.LocationListener;
import android.location.LocationManager;

import androidx.core.content.ContextCompat;

// [変更] GoogleApiClient/FusedLocationApi → Android標準のLocationManagerに置き換え
// GoogleApiClientはGoogle Play Services依存のため、osmdroid移行に合わせてAndroid標準APIに統一する
public class MyLocation {
    // [変更] GoogleApiClient → LocationManager
    private LocationManager locationManager;
    private Context context;
    // [変更] com.google.android.gms.location.LocationListener → android.location.LocationListener
    private LocationListener locationListener;
    private long updateIntervalMs;

    // [変更] コンストラクタの引数型を android.location.LocationListener に変更
    MyLocation(Context context, LocationListener locationListener, long updateIntervalMs) {
        this.context = context;
        this.locationListener = locationListener;
        this.updateIntervalMs = updateIntervalMs;
    }

    // [変更] createGoogleApiClient() の内容を LocationManager ベースに完全置換
    // メソッド名は呼び出し元(MainActivity)との互換性のため維持する
    public void createGoogleApiClient() {
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        // ACCESS_FINE_LOCATION パーミッションが付与されている場合のみ位置情報更新を開始
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            // [変更] LocationServices.FusedLocationApi.requestLocationUpdates → LocationManager.requestLocationUpdates
            // GPS_PROVIDERで高精度な位置情報を取得（元実装のPRIORITY_HIGH_ACCURACYに相当）
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    updateIntervalMs,   // 最小更新間隔(ms)
                    0f,                 // 最小移動距離(m)、0=制限なし
                    locationListener
            );
        }
    }

    // [変更] FusedLocationApi.removeLocationUpdates → LocationManager.removeUpdates
    public void stopGetLocation() {
        if (locationManager != null) {
            locationManager.removeUpdates(locationListener);
        }
    }
}
