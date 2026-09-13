package com.example.test_g_locon.main;

import com.google.android.gms.maps.model.Marker;

public class MarkerInfo {
    private Marker marker;
    private String peerId;

    MarkerInfo(Marker marker,String peerId){
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
