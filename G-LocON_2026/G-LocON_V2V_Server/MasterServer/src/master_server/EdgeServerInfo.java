package master_server;

/**
 * 交差点1つに対応するエッジサーバの情報。
 */
public class EdgeServerInfo {

    private final String intersectionId;
    private final String ip;
    private final int port;

    public EdgeServerInfo(String intersectionId, String ip, int port) {
        this.intersectionId = intersectionId;
        this.ip   = ip;
        this.port = port;
    }

    public String getIntersectionId() { return intersectionId; }
    public String getIp()             { return ip; }
    public int    getPort()           { return port; }
}
