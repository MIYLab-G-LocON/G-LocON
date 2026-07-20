package edge_server;

public class UserInfo {

    private String publicIP;
    private int publicPort;
    private String privateIP;
    private int privatePort;
    private double latitude;
    private double longitude;
    private String peerId;
    private double eta; // 交差点への到達予測時間（秒）

    public UserInfo() {}

    public String getPublicIP()    { return publicIP; }
    public void setPublicIP(String publicIP) { this.publicIP = publicIP; }

    public int getPublicPort()     { return publicPort; }
    public void setPublicPort(int publicPort) { this.publicPort = publicPort; }

    public String getPrivateIP()   { return privateIP; }
    public void setPrivateIP(String privateIP) { this.privateIP = privateIP; }

    public int getPrivatePort()    { return privatePort; }
    public void setPrivatePort(int privatePort) { this.privatePort = privatePort; }

    public double getLatitude()    { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getLongitude()   { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public String getPeerId()      { return peerId; }
    public void setPeerId(String peerId) { this.peerId = peerId; }

    public double getEta()         { return eta; }
    public void setEta(double eta) { this.eta = eta; }
}
