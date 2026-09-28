package edge_server;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;

import org.json.JSONObject;

/**
 * EdgeServerの送信スレッド。SignalingServerSendと同じ2モード構成。
 *
 * NAT_REGISTER : JOINしてきた車両の情報を既存メンバー全員へ通知
 * REPLY_RESULT : JOINしてきた車両へ既存メンバー一覧を返送
 * PEER_LEFT    : LEAVEした車両の情報を残りのメンバー全員へ通知
 */
public class EdgeServerSend extends Thread {

    public enum Mode { NAT_REGISTER, REPLY_RESULT, PEER_LEFT }

    private final DatagramSocket socket;
    private final UserInfo srcUser;
    private final ArrayList<UserInfo> peerList;
    private final Mode mode;
    private final String intersectionId;

    public EdgeServerSend(DatagramSocket socket, UserInfo srcUser,
                          ArrayList<UserInfo> peerList, Mode mode, String intersectionId) {
        this.socket   = socket;
        this.srcUser  = srcUser;
        this.peerList = peerList;
        this.mode     = mode;
        this.intersectionId = intersectionId;
    }

    @Override
    public void run() {
        switch (mode) {
            case NAT_REGISTER: sendNatRegister(); break;
            case REPLY_RESULT: sendReplyResult(); break;
            case PEER_LEFT:    sendPeerLeft();    break;
        }
    }

    /** 既存メンバー全員に，新規参加車両の情報を送信してNATホールパンチングを促す */
    private void sendNatRegister() {
        ProcessJSONObject pjo = new ProcessJSONObject();
        JSONObject json = pjo.getSrcUserInfo(srcUser, intersectionId);
        try {
            byte[] data = json.toString().getBytes();
            for (UserInfo peer : peerList) {
                DatagramPacket packet = new DatagramPacket(
                        data, data.length,
                        InetAddress.getByName(peer.getPublicIP()), peer.getPublicPort()
                );
                socket.send(packet);
                System.out.printf("[NAT ] new=%s → existing=%s%n",
                        srcUser.getPeerId(), peer.getPeerId());
            }
        } catch (Exception e) {
            System.err.println("NAT_REGISTER 送信エラー: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** 残りのメンバー全員に，LEAVEした車両の情報を送信して送信先リストから外させる */
    private void sendPeerLeft() {
        ProcessJSONObject pjo = new ProcessJSONObject();
        JSONObject json = pjo.getLeftUserInfo(srcUser, intersectionId);
        try {
            byte[] data = json.toString().getBytes();
            for (UserInfo peer : peerList) {
                DatagramPacket packet = new DatagramPacket(
                        data, data.length,
                        InetAddress.getByName(peer.getPublicIP()), peer.getPublicPort()
                );
                socket.send(packet);
                System.out.printf("[LEFT ] left=%s → remaining=%s%n",
                        srcUser.getPeerId(), peer.getPeerId());
            }
        } catch (Exception e) {
            System.err.println("PEER_LEFT 送信エラー: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** JOINしてきた車両へ既存メンバー一覧を返送 */
    private void sendReplyResult() {
        ProcessJSONObject pjo = new ProcessJSONObject();
        JSONObject json = pjo.getUserInfoList(peerList, intersectionId);
        try {
            byte[] data = json.toString().getBytes();
            DatagramPacket packet = new DatagramPacket(
                    data, data.length,
                    InetAddress.getByName(srcUser.getPublicIP()), srcUser.getPublicPort()
            );
            socket.send(packet);
            // ログはEdgeServerReceive側で出力済みのため省略
        } catch (Exception e) {
            System.err.println("REPLY_RESULT 送信エラー: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
