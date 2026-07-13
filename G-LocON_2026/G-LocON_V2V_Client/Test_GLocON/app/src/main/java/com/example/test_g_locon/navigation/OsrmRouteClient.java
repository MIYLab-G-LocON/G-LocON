package com.example.test_g_locon.navigation;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * OSRM公開APIを使ってルートと交差点座標を取得するクラス。
 *
 * エンドポイント:
 *   https://router.project-osrm.org/route/v1/driving/{lng1},{lat1};{lng2},{lat2}
 *   ?steps=true&geometries=geojson&overview=full
 *
 * レスポンスの routes[0].legs[0].steps[].intersections[].location から
 * 交差点座標リストを抽出する。
 *
 * ネットワーク通信を伴うため，呼び出し側は必ずバックグラウンドスレッドで実行すること。
 */
public class OsrmRouteClient {

    private static final String OSRM_BASE = "https://router.project-osrm.org/route/v1/driving/";

    /**
     * 出発地から目的地までのルートを取得し，ルート上の交差点リストを返す。
     *
     * @param fromLat 出発地の緯度
     * @param fromLng 出発地の経度
     * @param toLat   目的地の緯度
     * @param toLng   目的地の経度
     * @return 交差点リスト（ルート順）。失敗時は空リスト。
     */
    public List<Intersection> fetchIntersections(double fromLat, double fromLng,
                                                  double toLat,  double toLng) {
        List<Intersection> intersections = new ArrayList<>();
        try {
            String urlStr = OSRM_BASE
                    + fromLng + "," + fromLat + ";"
                    + toLng   + "," + toLat
                    + "?steps=true&geometries=geojson&overview=false";

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            // OSRMデモサーバはUser-Agentなしのリクエストを拒否する場合がある
            conn.setRequestProperty("User-Agent", "G-LocON-V2V/1.0");

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                // エラーレスポンスの内容をログに出す
                BufferedReader errReader = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream() != null
                                ? conn.getErrorStream() : conn.getInputStream()));
                StringBuilder errSb = new StringBuilder();
                String errLine;
                while ((errLine = errReader.readLine()) != null) errSb.append(errLine);
                errReader.close();
                System.err.println("OsrmRouteClient HTTP " + code + ": " + errSb);
                return intersections;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONObject response = new JSONObject(sb.toString());
            JSONArray steps = response
                    .getJSONArray("routes").getJSONObject(0)
                    .getJSONArray("legs").getJSONObject(0)
                    .getJSONArray("steps");

            for (int i = 0; i < steps.length(); i++) {
                JSONArray stepIntersections = steps.getJSONObject(i)
                        .getJSONArray("intersections");
                for (int j = 0; j < stepIntersections.length(); j++) {
                    JSONArray loc = stepIntersections.getJSONObject(j)
                            .getJSONArray("location");
                    double lng = loc.getDouble(0);
                    double lat = loc.getDouble(1);
                    // intersectionId は "緯度4桁_経度4桁" 形式（MasterServerのCSVと合わせる）
                    String id = String.format("%.4f_%.4f", lat, lng);
                    intersections.add(new Intersection(id, lat, lng));
                }
            }
            System.out.println("OsrmRouteClient: 交差点数=" + intersections.size());

        } catch (Exception e) {
            System.err.println("OsrmRouteClient エラー: " + e.getMessage());
            e.printStackTrace();
        }
        return intersections;
    }

    /** 交差点リストから intersectionId のリストだけを抽出する（MasterServer問い合わせ用） */
    public List<String> toIdList(List<Intersection> intersections) {
        List<String> ids = new ArrayList<>();
        for (Intersection i : intersections) ids.add(i.getIntersectionId());
        return ids;
    }
}
