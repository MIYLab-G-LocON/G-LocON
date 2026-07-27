package master_server;

/**
 * 交差点1つに対応するエッジサーバの情報。
 * intersectionId は "lat_lng" 形式を前提とし，lat/lng をフィールドに保持する。
 */
public class EdgeServerInfo {

    private final String intersectionId;
    private final String ip;
    private final int    port;
    private final double lat;
    private final double lng;

    public EdgeServerInfo(String intersectionId, String ip, int port) {
        this.intersectionId = intersectionId;
        this.ip   = ip;
        this.port = port;
        // "lat_lng" 形式から座標を解析
        String[] parts = intersectionId.split("_");
        double parsedLat = 0, parsedLng = 0;
        try {
            parsedLat = Double.parseDouble(parts[0]);
            parsedLng = Double.parseDouble(parts[1]);
        } catch (Exception ignored) {}
        this.lat = parsedLat;
        this.lng = parsedLng;
    }

    public String getIntersectionId() { return intersectionId; }
    public String getIp()             { return ip; }
    public int    getPort()           { return port; }
    public double getLat()            { return lat; }
    public double getLng()            { return lng; }
}
