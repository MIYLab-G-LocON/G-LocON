package client;

//11/15日の成功時の変更履歴18:07らへん
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Created by MF17037 on 2017/12/05.
 */

public class P2PSender extends Thread{
    private DatagramSocket socket;
    private String publicIP;
    private int publicPort;
    private String processType;
    private ArrayList<UserInfo> peripheralUsers;
    private UserInfo srcUserInfo;
	private InetAddress endPointIP;
	private int endPointPort;
	private JSONObject jsonObject;
	private String peerID;
	private double Lat;
	private double Long;
	//private double speed;


    P2PSender(DatagramSocket socket, String publicIP, int publicPort, ArrayList<UserInfo> peripheralUsers, String processType) {
        this.socket = socket;
        this.publicIP = publicIP;
        this.publicPort = publicPort;
        this.peripheralUsers = peripheralUsers;
        this.processType = processType;
    }

    P2PSender(DatagramSocket socket, String publicIP, int publicPort, UserInfo srcUserInfo, String processType) {
        this.socket = socket;
        this.publicIP = publicIP;
        this.publicPort = publicPort;
        this.srcUserInfo = srcUserInfo;
        this.processType = processType;
    }

    P2PSender(DatagramSocket socket, String publicIP, int publicPort, InetAddress endPointIP, int endPointPort, JSONObject jsonObject) {
        this.socket = socket;
        this.publicIP = publicIP;
        this.publicPort = publicPort;
        this.endPointIP = endPointIP;
        this.endPointPort = endPointPort;
        this.jsonObject = jsonObject;
    }

    P2PSender(DatagramSocket socket, String publicIP, int publicPort, ArrayList<UserInfo> peripheralUsers, String processType,String ID,Double Lat,Double Long) {
        this.socket = socket;
        this.publicIP = publicIP;
        this.publicPort = publicPort;
        this.peripheralUsers = peripheralUsers;
        this.processType = processType;
        this.peerID = ID;
        this.Lat = Lat;
        this.Long = Long;
    }

    public void run(){
        String tmpProcessType = "";
        if (jsonObject != null) {
            try {
                tmpProcessType = jsonObject.getString("processType");
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        //NATに通信相手の情報を記憶させる
        if (processType != null &&processType.equals("NATRegisterDstAddrPort")) {//ピア間でデータを送信する場合と場合分けする必要がある
            myNATRegisterDstUser();
        } else if (processType != null &&processType.equals("SendLocation")) {
        	//System.out.println("プロセスはSendLocation");
            sendMsg("");
        }
        /*
        else if(processType != null &&processType.equals("sendLocation")) {
        	sendLocation();
        }
        */
        else if (tmpProcessType.equals("DelayExperimentAck")) {
            endPointSendMsg();
        }
    }

    /*
    private void sendLocation() {
    	System.out.println("Log.d:P2PSender:called sendLocation");
    	JSONObject jsonObject = new JSONObject();
    	try {
            jsonObject.put("processType", "SendLocation");
            //jsonObject.put("locationUpdateCount",locationUpdateCount);
            jsonObject.put("latitude", srcUserInfo.getLatitude());
            jsonObject.put("longitude",srcUserInfo.getLongitude());
            jsonObject.put("peerId", srcUserInfo.getPeerID());
            //jsonObject.put("speed",srcUserInfo.getSpeed());
            byte[] sendData = jsonObject.toString().getBytes();
            for (int i = 0; i < peripheralUsers.size(); i++) {
            	DatagramPacket sendPacket;
            	 if (srcUserInfo.getPublicIP().equals(peripheralUsers.get(i).getPublicIP())) {
                     sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName(peripheralUsers.get(i).getPrivateIP()), peripheralUsers.get(i).getPrivatePort());
                     socket.send(sendPacket);
            }
            	 else {
            		 System.out.println("P2PSender_sendMsg:宛先IP:" + peripheralUsers.get(i).getPublicIP() + "であり宛先PORT" + peripheralUsers.get(i).getPublicPort());
                     sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName(peripheralUsers.get(i).getPublicIP()), peripheralUsers.get(i).getPublicPort());
                     socket.send(sendPacket);
            	 }
    	    }
    	}
            catch (Exception e) {
            e.printStackTrace();
        }
    }
    */

    private void sendMsg(String data) {
        System.out.println("Log.d:P2PSender:called sendMsg");
        JSONObject jsonObject = new JSONObject();
        try {
        	/*
            jsonObject.put("processType", "peermsg");
            jsonObject.put("msg","TEST^^");
            */

            jsonObject.put("locationUpdateCount",1);
        	jsonObject.put("processType", "SendLocation");
        	jsonObject.put("speed",0);//一応動くことを想定しているのでコンストラクタからもってくるように変更したい
            jsonObject.put("latitude", Lat);
            jsonObject.put("longitude",Long);
            jsonObject.put("peerID", peerID);
            //jsonObject.put("speed",speed);

            byte[] sendData = jsonObject.toString().getBytes();
            for (int i = 0; i < peripheralUsers.size(); i++) {
                DatagramPacket sendPacket;
                //同NAT内に存在する端末の場合はプライベートIPとPORTを指定する
                if (publicIP.equals(peripheralUsers.get(i).getPublicIP())) {
                    sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName(peripheralUsers.get(i).getPrivateIP()), peripheralUsers.get(i).getPrivatePort());
                    socket.send(sendPacket);
                }
                //異なるNATに存在する端末の場合はNAT通過処理を行う
                else {
                	System.out.println("P2PSender_sendMsg:宛先IP:"+peripheralUsers.get(i).getPublicIP()+"であり宛先PORT"+peripheralUsers.get(i).getPublicPort());
                    sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName(peripheralUsers.get(i).getPublicIP()), peripheralUsers.get(i).getPublicPort());
                    socket.send(sendPacket);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    /**
     * NAT通過を行う初期処理
     */
    private void myNATRegisterDstUser() {
    	System.out.println("Log.d:P2PSender:called myNATRegisterDstUser");
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("processType", "HelloPacket");//これは相手に届いても無視される
            byte[] sendData = jsonObject.toString().getBytes();
            if (peripheralUsers != null) {
                for (int i = 0; i < peripheralUsers.size(); i++) {
                    DatagramPacket sendPacket;
                    //同NAT内に存在する端末の場合はプライベートIPとPORTを指定する
                    if (publicIP.equals(peripheralUsers.get(i).getPublicIP())) {
                        sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName(peripheralUsers.get(i).getPrivateIP()), peripheralUsers.get(i).getPrivatePort());
                        socket.send(sendPacket);
                    }
                    //異なるNATに存在する端末の場合はNAT通過処理を行う
                    else {
                        sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName(peripheralUsers.get(i).getPublicIP()), peripheralUsers.get(i).getPublicPort());
                        socket.send(sendPacket);
                    }
                }
            } else if (srcUserInfo != null) {
                DatagramPacket sendPacket;
                //同NAT内に存在する端末の場合はプライベートIPとPORTを指定する
                if (publicIP.equals(srcUserInfo.getPublicIP())) {
                    sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName(srcUserInfo.getPrivateIP()), srcUserInfo.getPrivatePort());
                    socket.send(sendPacket);
                }
                //異なるNATに存在する端末の場合はNAT通過処理を行う
                else {
                    sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName(srcUserInfo.getPublicIP()), srcUserInfo.getPublicPort());
                    socket.send(sendPacket);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void endPointSendMsg() {
        try {
            byte[] sendData = jsonObject.toString().getBytes();
            DatagramPacket sendPacket;
            sendPacket = new DatagramPacket(sendData, sendData.length, endPointIP, endPointPort);
            socket.send(sendPacket);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
