package com.example.test_g_locon.main;

public class MemoryToReceiveData {
    private String locationUpdateCount;
    private String endPointIP;
    private String endPointPort;
    private String receiveTime;

    public MemoryToReceiveData(String locationUpdateCount,String endPointIP,String endPointPort, String receiveTime){
        this.locationUpdateCount = locationUpdateCount;
        this.endPointIP = endPointIP;
        this.endPointPort = endPointPort;
        this.receiveTime = receiveTime;
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

    public String getReceiveTime() {
        return receiveTime;
    }
}
