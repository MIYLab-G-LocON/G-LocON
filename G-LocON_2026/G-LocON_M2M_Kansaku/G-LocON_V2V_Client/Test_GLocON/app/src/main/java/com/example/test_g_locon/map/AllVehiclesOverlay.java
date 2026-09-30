package com.example.test_g_locon.map;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;

import com.example.test_g_locon.sim.SimVehicle;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * [SUMOモード]「表示:全車両」で，P2Pでつながっていない車も小さな矢印で描くオーバーレイ。
 *
 * 車が多い（数百台）ので，Marker を1台ずつ作らず，1つのオーバーレイでまとめて描く。
 * P2Pでつながっている車（地図にピンが出ている車）は描かない（ピンとの違いが分かるように）。
 *   灰色の矢印 = つながっていない車（SUMOの車・仮想クライアント）
 *   青の矢印   = つながっていない実機
 */
public class AllVehiclesOverlay extends Overlay {

    private volatile List<SimVehicle> vehicles = Collections.emptyList();
    private volatile Set<String> hidden = Collections.emptySet();

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final Point pt = new Point();
    private final GeoPoint geo = new GeoPoint(0.0, 0.0);

    public AllVehiclesOverlay(float density) {
        float s = 7f * density;               // 矢印の大きさ（先端までの長さ）
        arrow.moveTo(0, -s);
        arrow.lineTo(0.7f * s, 0.8f * s);
        arrow.lineTo(0, 0.4f * s);
        arrow.lineTo(-0.7f * s, 0.8f * s);
        arrow.close();
        fill.setStyle(Paint.Style.FILL);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(1.5f * density);
        outline.setColor(Color.WHITE);
    }

    /** @param hiddenPeerIds 描かない車（P2Pのピンが出ている車） */
    public void setVehicles(List<SimVehicle> list, Set<String> hiddenPeerIds) {
        vehicles = list;
        hidden = hiddenPeerIds;
    }

    public void clear() {
        vehicles = Collections.emptyList();
    }

    @Override
    public void draw(Canvas c, Projection pj) {
        List<SimVehicle> list = vehicles;
        Set<String> skip = hidden;
        for (SimVehicle v : list) {
            if (skip.contains(v.peerId)) continue;
            geo.setCoords(v.lat, v.lon);
            pj.toPixels(geo, pt);
            // キャンバスは地図の向きに合わせて回っているので，進行方向（北=0）だけ回せば地図上の向きになる
            c.save();
            c.translate(pt.x, pt.y);
            c.rotate(v.bearing);
            fill.setColor(v.isPhone ? Color.rgb(30, 110, 230) : Color.rgb(90, 90, 90));
            c.drawPath(arrow, fill);
            c.drawPath(arrow, outline);
            c.restore();
        }
    }
}
