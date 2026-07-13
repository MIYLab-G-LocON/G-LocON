package com.example.test_g_locon.map;

// [変更] パッケージを main → map へ移動。地図関連クラスを map パッケージに集約する
import org.osmdroid.views.overlay.Marker;

/**
 * osmdroid の Marker と peerId を対応付けるDTOクラス。
 * MapManager がマーカ管理リストの要素として使用する。
 */
public class MarkerInfo {
    private Marker marker;
    private String peerId;

    public MarkerInfo(Marker marker, String peerId) {
        this.marker = marker;
        this.peerId = peerId;
    }

    public Marker getMarker() { return marker; }
    public String getPeerId() { return peerId; }
    public void setMarker(Marker marker) { this.marker = marker; }
    public void setPeerId(String peerId) { this.peerId = peerId; }
}
