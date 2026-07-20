package com.example.test_g_locon.map;

// [変更] パッケージを main → map へ移動。地図関連クラスを map パッケージに集約する
import android.graphics.Color;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Polygon;

import java.util.List;

/**
 * 検索範囲を示す円ポリゴンを生成するファクトリクラス。
 * MapManager から利用される。
 */
public class CreateCircle {
    public CreateCircle() {}

    /**
     * 指定した中心・半径の円を近似する osmdroid Polygon を生成する。
     *
     * @param center          円の中心座標
     * @param radiusInMeters  半径 (メートル)
     * @return 塗りつぶし円ポリゴン
     */
    public Polygon createCirclePolygon(GeoPoint center, double radiusInMeters) {
        Polygon polygon = new Polygon();
        List<GeoPoint> circlePoints = Polygon.pointsAsCircle(center, radiusInMeters);
        polygon.setPoints(circlePoints);
        polygon.getFillPaint().setColor(Color.parseColor("#3300FFCC"));
        polygon.getOutlinePaint().setColor(Color.parseColor("#FF0000FF"));
        polygon.getOutlinePaint().setStrokeWidth(2);
        return polygon;
    }
}
