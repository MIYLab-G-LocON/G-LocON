package com.example.test_g_locon.main;

import android.graphics.Color;

// [変更] Google Maps CircleOptions → osmdroid Polygon に置き換え
// osmdroidにはCircleクラスが存在しないため、Polygonで円を近似して描画する
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Polygon;

import java.util.List;

public class CreateCircle {
    public CreateCircle() {}

    // [変更] 戻り値をCircleOptions(Google Maps)からPolygon(osmdroid)に変更
    // Polygon.pointsAsCircleで円周上の点列を生成し、塗りつぶし円として表示する
    public Polygon createCirclePolygon(GeoPoint center, double radiusInMeters) {
        Polygon polygon = new Polygon();
        // [変更] osmdroidのユーティリティメソッドで円を近似するGeoPointリストを生成
        List<GeoPoint> circlePoints = Polygon.pointsAsCircle(center, radiusInMeters);
        polygon.setPoints(circlePoints);
        // 元のGoogle Maps実装と同じ色設定を引き継ぐ
        polygon.getFillPaint().setColor(Color.parseColor("#3300FFCC"));
        polygon.getOutlinePaint().setColor(Color.parseColor("#FF0000FF"));
        polygon.getOutlinePaint().setStrokeWidth(2);
        return polygon;
    }
}
