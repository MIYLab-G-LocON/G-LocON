package client;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

import org.json.JSONObject;


/**
 * Created by MF17037 on 2017/12/05.
 */

public class Receive extends Thread{
    private DatagramSocket socket;
    private IReceive iReceive;

    Receive(DatagramSocket socket, IReceive iReceive) {
        this.socket = socket;
        this.iReceive = iReceive;
    }

    public void run() {
        final String GET_PERIPHERAL_USER = "getPeripheralUserInfoList";
        final String RECEIVE_MSG_PEER = "peermsg";
        final String DO_UDP_HOLE_PUNCHING = "doUDPHolePunching";
        final String DELAY_EXPERIMENT = "DelayExperiment";
        //final String SEND_DATA = "sendLocation";
        do {
            DatagramPacket receivePacket = new DatagramPacket(new byte[1024], 1024);
            try {
                System.out.println("P2P:P2Pレシーブ起動直前");
                socket.receive(receivePacket);
                String result = new String(receivePacket.getData(), 0, receivePacket.getLength());
                JSONObject jsonObject = new JSONObject(result);
                ProcessJSONObject processJSONObject = new ProcessJSONObject(jsonObject);
                String processType = processJSONObject.getProcessType();
                //System.out.println("Receive:送信元IP:"+receivePacket.getAddress());


                if(processType.equals(GET_PERIPHERAL_USER)){
                	System.out.println("processType-----GET_PERIPHERAL_USER");
                    iReceive.onGetPeripheralUser(processJSONObject.getPerioheralUserInfos());
                }

                else if(processType.equals(DO_UDP_HOLE_PUNCHING)){
                	System.out.println("processType-----DO_UDP_HOLE_PUNCHING");
                    iReceive.onDoUDPHolePunching(processJSONObject.getSrcUserInfo());
                }

              //遅延実験データを受信した際の処理を記述
                else if(processType.equals(DELAY_EXPERIMENT)){
                	System.out.println("processType-----DELAY_EXPERIMENT");
                    iReceive.onDelayExperiment(processJSONObject.getDelayExperimentLocationCount(),receivePacket.getAddress(),receivePacket.getPort());
                }
                else {
                	System.out.println("Log.d:Receive:called run:通信相手IP:"+receivePacket.getAddress()+"Port:"+receivePacket.getPort()+"からメッセージが届き、それは"+processJSONObject.getP2PMsg());
                }
            } catch (Exception e) {
            	System.out.println("P2P:P2Preceiverのレシーブエラー");
            }
        }while (true);
    }

}