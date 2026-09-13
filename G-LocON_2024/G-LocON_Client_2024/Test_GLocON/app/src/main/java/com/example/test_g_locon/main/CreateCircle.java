package com.example.test_g_locon.main;

import android.graphics.Color;

import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;

public class CreateCircle {
    public CreateCircle() {}
    public CircleOptions createCircleOptions(LatLng position, Double range) {
        CircleOptions options = new CircleOptions();
        options.center(position);
        options.radius(range);
        options.fillColor(Color.parseColor("#3300FFCC"));
        options.strokeColor(Color.parseColor("#FF0000FF"));
        options.strokeWidth(2);
        return options;
    }
}
