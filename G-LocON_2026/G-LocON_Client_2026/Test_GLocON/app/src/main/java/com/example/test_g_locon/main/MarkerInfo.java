package com.example.test_g_locon.main;

// [変更] com.google.android.gms.maps.model.Marker → org.osmdroid.views.overlay.Marker に置き換え
import org.osmdroid.views.overlay.Marker;

public class MarkerInfo {
    // [変更] Markerの型がGoogle Maps → osmdroidに変わっている（APIはほぼ同一）
    private Marker marker;
    private String peerId;

    MarkerInfo(Marker marker, String peerId) {
        this.marker = marker;
        this.peerId = peerId;
    }

    public Marker getMarker() {
        return marker;
    }

    public String getPeerId() {
        return peerId;
    }

    public void setMarker(Marker marker) {
        this.marker = marker;
    }

    public void setPeerId(String peerId) {
        this.peerId = peerId;
    }
}
