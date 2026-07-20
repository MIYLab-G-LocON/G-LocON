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
 */
public class EdgeServerSend extends Thread {

    public enum Mode { NAT_REGISTER, REPLY_RESULT }

    private final DatagramSocket socket;
    private final UserInfo srcUser;
    private final ArrayList<UserInfo> peerList;
    private final Mode mode;

    public EdgeServerSend(DatagramSocket socket, UserInfo srcUser,
                          ArrayList<UserInfo> peerList, Mode mode) {
        this.socket   = socket;
        this.srcUser  = srcUser;
        this.peerList = peerList;
        this.mode     = mode;
    }

    @Override
    public void run() {
        switch (mode) {
            case NAT_REGISTER: sendNatRegister(); break;
            case REPLY_RESULT: sendReplyResult(); break;
        }
    }

    /** 既存メンバー全員に，新規参加車両の情報を送信してNATホールパンチングを促す */
    private void sendNatRegister() {
        ProcessJSONObject pjo = new ProcessJSONObject();
        JSONObject json = pjo.getSrcUserInfo(srcUser);
        try {
            byte[] data = json.toString().getBytes();
            for (UserInfo peer : peerList) {
                DatagramPacket packet = new DatagramPacket(
                        data, data.length,
                        InetAddress.getByName(peer.getPublicIP()), peer.getPublicPort()
                );
                socket.send(packet);
                System.out.println("NAT_REGISTER: " + srcUser.getPeerId()
                        + " の情報を " + peer.getPeerId() + " へ送信");
            }
        } catch (Exception e) {
            System.err.println("NAT_REGISTER 送信エラー: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** JOINしてきた車両へ既存メンバー一覧を返送 */
    private void sendReplyResult() {
        ProcessJSONObject pjo = new ProcessJSONObject();
        JSONObject json = pjo.getUserInfoList(peerList);
        try {
            byte[] data = json.toString().getBytes();
            DatagramPacket packet = new DatagramPacket(
                    data, data.length,
                    InetAddress.getByName(srcUser.getPublicIP()), srcUser.getPublicPort()
            );
            socket.send(packet);
            System.out.println("REPLY_RESULT: " + srcUser.getPeerId()
                    + " へメンバー一覧 " + peerList.size() + "件 を返送");
        } catch (Exception e) {
            System.err.println("REPLY_RESULT 送信エラー: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
