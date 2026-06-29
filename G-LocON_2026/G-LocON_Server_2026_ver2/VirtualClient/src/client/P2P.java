package client;

//11/15日の成功時の変更履歴18:07らへん
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Scanner;

import org.json.JSONException;
import org.json.JSONObject;



/**
 * Created by MF17037 on 2017/12/05.
 */

public class P2P implements UDPHolePunchingFinish,IReceive{
    private IP2P iP2P;
    private String publicIP = "0.0.0.0";
    private int publicPort = 0;
    private DatagramSocket socket;
    private ArrayList<UserInfo> peripheralUserInfos;
    //private UserInfo myUserInfo;
    private String localIP;
    private final String peerID = "555";
    //private String peerID;
    //private HubenyDistance hubenyDistance;
    //private double speed;
    private final String LAN= /*"Ethernet"*/"Wi-Fi"; //TODO: 実機の場合はWi-Fiを選択

    P2P(IP2P iP2P) {
        this.iP2P = iP2P;
            localIP = GetPrivateIP();
            System.out.println("P2P:LocalIP:"+localIP);

        peripheralUserInfos = new ArrayList<>();
        //myUserInfo = new UserInfo();
        //hubenyDistance = new HubenyDistance();

        try {
            socket = new DatagramSocket();
            socket.setReuseAddress(true);
        } catch (Exception e) {

        }
    }


    /**
     * IPアドレスを返す
     *
     * @return IPアドレス
     */
    public String getPublicIP() {
        return publicIP;
    }


    /**
     * portを返す
     *
     * @return port
     */
    public int getPublicPort() {
        return publicPort;
    }
    /*
	public void setLocation(Location geo) {
		myUserInfo.setLatitude(geo.getLatitude());
		myUserInfo.setLongitude(geo.getLongitude());
	}

    public void setSpeed(Location geo) {
    	myUserInfo.setSpeed(hubenyDistance.calcDistance(geo.getLatitude(),geo.getLongitude(), myUserInfo.getLatitude(), myUserInfo.getLongitude()) * 3.6);
        //System.out.println("端末の速度は"+myUserInfo.getSpeed());
    }
    */
    /**
     * 自身の外部IPと外部ポート番号の取得を開始する
     */
    public void udpHolePunchingStart() {

        UDPHolePunchingClient udpHolePunchingClient = new UDPHolePunchingClient(socket, this);
        udpHolePunchingClient.udpHolePunchingStart();
    }


    /**
     * 外部IPとportを受信した時に呼ばれるリスナー
     *
     * @param IP   外部IP
     * @param port 外部port
     */
    @Override
    public void onUDPHolePunchingFinish(String IP, int port) {
        this.publicIP = IP;
        this.publicPort = port;
        System.out.println("UDP_HOLE_PUNCHING:IP:" + publicIP + "---Port:" + publicPort);

        //この段階でユーザの登録を行う
        UserInfo myUserInfo = new UserInfo();
        myUserInfo.setPublicIP(publicIP);
        myUserInfo.setPublicPort(publicPort);
        myUserInfo.setPrivateIP(localIP);
        myUserInfo.setPrivatePort(socket.getLocalPort());
        myUserInfo.setLatitude(0.0);
        myUserInfo.setLongitude(0.0);
        //myUserInfo.setSpeed(0.0);
        myUserInfo.setPeerID(peerID);

        SendToSignalingServer sendToSignalingServer = new SendToSignalingServer(socket,"REGISTER",myUserInfo);
        sendToSignalingServer.start();
        //////////////////////////////

        iP2P.onGetExternalIPAddressAndPort();
    }


    @Override
    public void onGetPeripheralUser(ArrayList<UserInfo> newPeripheralUserInfos){
        peripheralUserInfos = newPeripheralUserInfos;
        System.out.println("----------------------------------------------------------------Log.d:P2P:called onGetPeripheralUser:新しく追加されたユーザ数は"+peripheralUserInfos.size());
        P2PSender p2pSender= new P2PSender(socket,publicIP,publicPort,peripheralUserInfos,"NATRegisterDstAddrPort");
        p2pSender.start();
    }



    //シグナリングサーバに接続した送信元ユーザのIPとポートを自身のNATに記録する
    @Override
    public void onDoUDPHolePunching(UserInfo srcUserInfo) {
        P2PSender p2pSender = new P2PSender(socket, publicIP, publicPort, srcUserInfo, "NATRegisterDstAddrPort");
        p2pSender.start();

        for(int i = 0; i < peripheralUserInfos.size(); i++) {
            if (srcUserInfo.getPublicIP().equals(peripheralUserInfos.get(i).getPublicIP()) && srcUserInfo.getPublicPort() == peripheralUserInfos.get(i).getPublicPort() &&
                    srcUserInfo.getPrivateIP().equals(peripheralUserInfos.get(i).getPrivateIP()) && srcUserInfo.getPrivatePort() == peripheralUserInfos.get(i).getPrivatePort()) {
            	return;
            }
        }
        peripheralUserInfos.add(srcUserInfo);
        System.out.println("Log.d:P2P:called onDoUDPHolePunching:新しくユーザ数が追加された___ユーザ数:"+peripheralUserInfos.size()+"___IP:"+srcUserInfo.getPublicIP()+"___Port:"+srcUserInfo.getPublicPort());
    }


    @Override
    public void onGetPeerMsg(){

    }


    /**
     * ACKを返す
     * @param locationCount
     * @param endPointIP
     * @param endPointPort
     */
    @Override
    public void onDelayExperiment(int locationCount,InetAddress endPointIP,int endPointPort){
        System.out.println("P2P::Called onDelayExperiment");
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("processType","DelayExperimentAck");
            jsonObject.put("locationUpdateCount",locationCount);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        send(jsonObject,endPointIP,endPointPort);
    }


    public void send(String msg,String processType) {
        P2PSender p2PSender = new P2PSender(socket,publicIP,publicPort,peripheralUserInfos,processType);
        p2PSender.start();
        //System.out.println("ここからスタートしています");
    }

    /*
    public void sendLocation(double Lat,double Long,String processType) {
        P2PSender p2pSender = new P2PSender(socket,publicIP,publicPort,peripheralUserInfos,processType,Lat,Long);
        p2pSender.start();
    }
    */

    /**
     * 実験遅延データを測定する際のAck送信時を想定
     */
    void send(JSONObject jsonObject,InetAddress endPointIP,int endPointPort){
        System.out.println("P2P:called seond__実験データAck送信時");
        P2PSender p2PSender = new P2PSender(socket,publicIP,publicPort,endPointIP,endPointPort,jsonObject);
        p2PSender.start();
    }


	/*
	 * 他端末に情報送信
	 * */
	public void send(String msg, String processType,String ID,Double Lat,Double Long) {
		P2PSender p2PSender = new P2PSender(socket, publicIP, publicPort, peripheralUserInfos, processType,ID,Lat,Long);
		p2PSender.start();
		//System.out.println("このsendをしています．");
	}

    public void receive() {
    	//System.out.println("receiveを始めます");
        System.out.println("Log.d:P2P:recieve:called");
        Receive recieve = new Receive(socket,this);
        recieve.start();
    }



    public void sendDataToSignalingServer(double latitude, double longitude, String processType){
        UserInfo myUserInfo = new UserInfo();
        myUserInfo.setPublicIP(publicIP);
        myUserInfo.setPublicPort(publicPort);
        myUserInfo.setPrivateIP(localIP);
        myUserInfo.setPrivatePort(socket.getLocalPort());
        myUserInfo.setLatitude(latitude);
        myUserInfo.setLongitude(longitude);
        //myUserInfo.setSpeed(0.0);//シグナリングサーバにも速度を送りたい
        myUserInfo.setPeerID(peerID);

        SendToSignalingServer sendToSignalingServer = new SendToSignalingServer(socket,processType,myUserInfo);
        sendToSignalingServer.start();
    }

    public void sendDataToSignalingServer(double latitude, double longitude, double searchDistance, String processType){
        UserInfo myUserInfo = new UserInfo();
        myUserInfo.setPublicIP(publicIP);
        myUserInfo.setPublicPort(publicPort);
        myUserInfo.setPrivateIP(localIP);
        myUserInfo.setPrivatePort(socket.getLocalPort());
        myUserInfo.setLatitude(latitude);
        myUserInfo.setLongitude(longitude);
        //myUserInfo.setSpeed(0.0);//シグナリングサーバにも速度を送りたい
        myUserInfo.setPeerID(peerID);

        SendToSignalingServer sendToSignalingServer = new SendToSignalingServer(socket,processType,myUserInfo,searchDistance);
        sendToSignalingServer.start();
    }


    public String GetPrivateIP() {
        String privateIP = null;

        try {
            for(NetworkInterface n: Collections.list(NetworkInterface.getNetworkInterfaces()) ) {
                if (!n.getDisplayName().contains(LAN)) continue; // 追加
                for (InetAddress addr : Collections.list(n.getInetAddresses()))  {
                    if( addr instanceof Inet4Address && !addr.isLoopbackAddress() ){
                        privateIP = addr.getHostAddress();
                        return privateIP;
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return privateIP;
    }

}
