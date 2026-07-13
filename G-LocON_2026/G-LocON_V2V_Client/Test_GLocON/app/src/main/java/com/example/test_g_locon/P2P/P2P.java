package com.example.test_g_locon.P2P;

// [変更] AsyncTask.THREAD_POOL_EXECUTOR → ExecutorService に置き換え
import android.location.Location;

import com.example.test_g_locon.main.MemoryResult;
import com.example.test_g_locon.main.MemoryToReceiveData;
import com.example.test_g_locon.main.MemoryToSendData;
import com.example.test_g_locon.main.OutputToCSV;
import com.example.test_g_locon.main.SetDate;
import com.example.test_g_locon.main.UserInfo;

import java.net.DatagramSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P2P通信全体のファサードクラス。
 * シグナリングサーバ操作・NATホールパンチング・位置情報送受信を統括する。
 *
 * [変更] AsyncTask.executeOnExecutor() → ExecutorService.execute() に全面移行
 *   各通信クラス (Signaling, P2PReceiver, P2PSender, P2PNatRegisterSender) が
 *   Runnable を実装するように変更したため、executorに直接渡せるようになった。
 *
 * [変更] executor を P2P クラスで一元管理することで、スレッドプールの
 *   生成コストを削減し、スレッド数を制御しやすくした。
 */
public class P2P implements IP2PReceiver {

    private final IP2P iP2P;
    private final DatagramSocket socket;
    private UserInfo myUserInfo;
    private ArrayList<UserInfo> peripheralUsers;

    // [変更] AsyncTask → ExecutorService（キャッシュスレッドプール）
    // 複数の非同期通信タスクを並行実行するために使用する
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // 遅延計測用フィールド（使用する場合は setUpMemory() を呼ぶこと）
    private OutputToCSV sendFileInput;
    private OutputToCSV receiveFileInput;
    private List<MemoryToSendData> sendMemory;
    private List<MemoryToReceiveData> receiveMemory;

    /**
     * @param socket     共有 DatagramSocket
     * @param myUserInfo 自端末情報
     * @param iP2P       UIへのコールバック先（AppController が実装）
     */
    public P2P(DatagramSocket socket, UserInfo myUserInfo, IP2P iP2P) {
        this.iP2P = iP2P;
        this.socket = socket;
        this.myUserInfo = myUserInfo;
        peripheralUsers = new ArrayList<>();
        sendMemory = Collections.synchronizedList(new ArrayList<>());
    }

    public ArrayList<UserInfo> getPeripheralUsers() {
        return peripheralUsers;
    }

    public void setMyUserInfo(UserInfo myUserInfo) {
        this.myUserInfo = myUserInfo;
    }

    // =========================================================
    // 非同期タスク起動（全て executor.execute() に統一）
    // =========================================================

    /** [変更] executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR) → executor.execute() */
    public void p2pReceiverStart() {
        executor.execute(new P2PReceiver(socket, this));
    }

    public void signalingRegister() {
        executor.execute(new Signaling(socket, myUserInfo, ESignalingProcess.REGISTER));
    }

    public void signalingUpdate() {
        executor.execute(new Signaling(socket, myUserInfo, ESignalingProcess.UPDATE));
    }

    public void signalingSearch(double searchDistance) {
        executor.execute(new Signaling(socket, myUserInfo, searchDistance, ESignalingProcess.SEARCH));
    }

    public void signalingDelete() {
        executor.execute(new Signaling(socket, myUserInfo, ESignalingProcess.DELETE));
    }

    public void natRegisterDstUsers() {
        executor.execute(new P2PNatRegisterSender(
                socket, myUserInfo.getPublicIP(), myUserInfo.getPublicPort(),
                peripheralUsers, EP2PProcess.NATRegisterDstUsers));
    }

    public void natRegisterSrcUser(UserInfo srcUser) {
        executor.execute(new P2PNatRegisterSender(
                socket, myUserInfo.getPublicIP(), myUserInfo.getPublicPort(),
                srcUser, EP2PProcess.NATRegisterSrcUser));
    }

    public void sendLocation(int locationUpdateCount) {
        MemoryToCSV_Send(locationUpdateCount);
        executor.execute(new P2PSender(
                socket, locationUpdateCount, myUserInfo, peripheralUsers, EP2PProcess.SendLocation));
    }

    // =========================================================
    // IP2PReceiver 実装（P2PReceiver からのコールバック）
    // =========================================================

    @Override
    public void onGetPeripheralUser(ArrayList<UserInfo> newPeripheralUsers) {
        peripheralUsers = newPeripheralUsers;
        iP2P.onGetPeripheralUsersInfo(peripheralUsers);
        natRegisterDstUsers();
    }

    @Override
    public void onDoUDPHolePunching(UserInfo srcUserInfo) {
        natRegisterSrcUser(srcUserInfo);
        // 未登録のユーザであれば peripheralUsers に追加
        for (UserInfo peer : peripheralUsers) {
            if (srcUserInfo.getPublicIP().equals(peer.getPublicIP())
                    && srcUserInfo.getPublicPort() == peer.getPublicPort()
                    && srcUserInfo.getPrivateIP().equals(peer.getPrivateIP())
                    && srcUserInfo.getPrivatePort() == peer.getPrivatePort()) {
                return;
            }
        }
        peripheralUsers.add(srcUserInfo);
    }

    @Override
    public void onGetPeripheralUserLocation(int locationUpdateCount, String srcIP, int srcPort,
                                            Location location, String peerId, double speed) {
        for (UserInfo peer : peripheralUsers) {
            boolean matched = srcIP.equals(peer.getPublicIP()) && srcPort == peer.getPublicPort()
                    || srcIP.equals(peer.getPrivateIP()) && srcPort == peer.getPrivatePort();
            if (matched) {
                peer.setLatitude(location.getLatitude());
                peer.setLongitude(location.getLongitude());
                peer.setPeerId(peerId);
                peer.setSpeed(speed);
                iP2P.onGetDetailUserInfo(peer, peripheralUsers);
                return;
            }
        }
    }

    @Override
    public void onGetAck(int locationCount, String endPointIP, int endPointPort) {
        MemoryToCSV_Receive(locationCount, endPointIP, endPointPort);
    }

    // =========================================================
    // 遅延計測用メソッド（使用する場合は setUpMemory() を呼ぶこと）
    // =========================================================

    public void setUpMemory() {
        sendMemory = Collections.synchronizedList(new ArrayList<>());
        receiveMemory = Collections.synchronizedList(new ArrayList<>());
        sendFileInput = new OutputToCSV("/send.csv");
        sendFileInput.setFieledName(new String[]{"LocationUpdateCount", "endPointIP", "endPointPort", "sendTime"});
        receiveFileInput = new OutputToCSV("/receive.csv");
        receiveFileInput.setFieledName(new String[]{"LocationUpdateCount", "endPointIP", "endPointPort", "AckReceiveTime"});
    }

    public void MemoryToCSV_Send(int locationUpdateCount) {
        String sendTime = new SetDate().convertLong(System.currentTimeMillis());
        for (UserInfo peer : peripheralUsers) {
            String ip = myUserInfo.getPublicIP().equals(peer.getPublicIP())
                    ? peer.getPrivateIP() : peer.getPublicIP();
            int port = myUserInfo.getPublicIP().equals(peer.getPublicIP())
                    ? peer.getPrivatePort() : peer.getPublicPort();
            sendMemory.add(new MemoryToSendData(
                    String.valueOf(locationUpdateCount), ip, String.valueOf(port), sendTime));
        }
    }

    public void MemoryToCSV_Receive(int locationCount, String endPointIP, int endPointPort) {
        String receiveTime = new SetDate().convertLong(System.currentTimeMillis());
        receiveMemory.add(new MemoryToReceiveData(
                String.valueOf(locationCount), endPointIP, String.valueOf(endPointPort), receiveTime));
    }

    public void fileInputMemorySendData() {
        for (MemoryToSendData tmp : sendMemory) {
            sendFileInput.OutputData(tmp.getLocationUpdateCount(), tmp.getEndPointIP(),
                    tmp.getEndPointPort(), tmp.getSendTime());
        }
        sendMemory.clear();
        sendFileInput.fileClose();
    }

    public void fileInputMemoryReceiveData() {
        for (MemoryToReceiveData tmp : receiveMemory) {
            receiveFileInput.OutputData(tmp.getLocationUpdateCount(), tmp.getEndPointIP(),
                    tmp.getEndPointPort(), tmp.getReceiveTime());
        }
        receiveMemory.clear();
        receiveFileInput.fileClose();
    }

    public void fileInputMemoryResult() {
        new MemoryResult(sendMemory, receiveMemory).OutputToCSV();
    }
}
