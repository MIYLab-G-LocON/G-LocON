package com.example.test_g_locon.main;

public class MemoryToSendData {
    private String locationUpdateCount;
    private String endPointIP;
    private String endPointPort;
    private String sendTime;

    public MemoryToSendData(String locationUpdateCount,String endPointIP,String endPointPort,String sendTime){
        this.locationUpdateCount = locationUpdateCount;
        this.endPointIP = endPointIP;
        this.endPointPort = endPointPort;
        this.sendTime = sendTime;
    }

    public String getLocationUpdateCount() {
        return locationUpdateCount;
    }

    public String getEndPointIP() {
        return endPointIP;
    }

    public String getEndPointPort() {
        return endPointPort;
    }

    public String getSendTime() {
        return sendTime;
    }
}
