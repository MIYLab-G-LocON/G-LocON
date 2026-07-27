package master_server;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 交差点ID → エッジサーバ情報のテーブルを管理するクラス。
 *
 * 設定ファイル（edge_servers.csv）から初期化する。
 * フォーマット（1行1エントリ）:
 *   intersectionId,ip,port
 *   例: 35.6580_139.7016,192.168.1.10,55600
 */
public class EdgeServerRegistry {

    /** 近傍検索の閾値（メートル）。この距離以内なら同じ交差点とみなす */
    private static final double PROXIMITY_THRESHOLD_M = 15.0;

    private final Map<String, EdgeServerInfo> table = new HashMap<>();

    /**
     * CSVファイルからレジストリを初期化する。
     * @param csvPath edge_servers.csv のファイルパス
     */
    public void loadFromCsv(String csvPath) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(",");
                if (parts.length < 3) continue;
                String intersectionId = parts[0].trim();
                if (intersectionId.equalsIgnoreCase("intersectionId")) continue; // ヘッダ行スキップ
                String ip             = parts[1].trim();
                int    port;
                try { port = Integer.parseInt(parts[2].trim()); }
                catch (NumberFormatException e) { continue; }
                table.put(intersectionId, new EdgeServerInfo(intersectionId, ip, port));
            }
        }
        System.out.println("EdgeServerRegistry: " + table.size() + "件 ロード完了");
    }

    /**
     * 交差点IDリストに対応するエッジサーバ情報を返す。
     * 完全一致が見つからない場合は近傍検索（PROXIMITY_THRESHOLD_M 以内の最近傍）で代替する。
     */
    public List<EdgeServerInfo> resolve(List<String> intersectionIds) {
        List<EdgeServerInfo> result = new ArrayList<>();
        for (String id : intersectionIds) {
            // ① 完全一致
            EdgeServerInfo info = table.get(id);
            if (info != null) {
                result.add(info);
                continue;
            }
            // ② 近傍検索: PROXIMITY_THRESHOLD_M 以内で最も近いエントリを探す
            EdgeServerInfo nearest = findNearest(id);
            if (nearest != null) {
                System.out.println("EdgeServerRegistry: 近傍一致 " + id
                        + " → " + nearest.getIntersectionId());
                result.add(nearest);
            } else {
                System.out.println("EdgeServerRegistry: 未登録の交差点ID=" + id);
            }
        }
        return result;
    }

    /**
     * IDを "lat_lng" 形式として解析し，登録済みエントリの中から
     * PROXIMITY_THRESHOLD_M 以内で最も近いものを返す。
     * 見つからなければ null を返す。
     */
    private EdgeServerInfo findNearest(String id) {
        double[] coord = parseCoord(id);
        if (coord == null) return null;
        double queryLat = coord[0];
        double queryLng = coord[1];

        EdgeServerInfo nearest = null;
        double minDist = PROXIMITY_THRESHOLD_M;

        for (EdgeServerInfo info : table.values()) {
            double dist = haversineMeters(queryLat, queryLng, info.getLat(), info.getLng());
            if (dist < minDist) {
                minDist = dist;
                nearest = info;
            }
        }
        return nearest;
    }

    /** "lat_lng" 形式の文字列を [lat, lng] の配列に変換する。失敗時は null */
    private double[] parseCoord(String id) {
        try {
            String[] parts = id.split("_");
            return new double[]{ Double.parseDouble(parts[0]), Double.parseDouble(parts[1]) };
        } catch (Exception e) {
            return null;
        }
    }

    /** Haversine式による2点間距離（メートル） */
    private double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        final double R = 6378137.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public int size() { return table.size(); }
}
