package signaling_server;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;

import org.json.JSONObject;

/**
 * シグナリングサーバの送信スレッド。
 * 検索結果を送信元ユーザへ返すか、周辺ユーザへNAT登録通知を行うかの2モードを持つ。
 *
 * [変更] 送信モードを文字列 ("srcAddrPortRegisterToNat" / "replyFromMainActivity") から
 *   enum Mode (NAT_REGISTER / REPLY_RESULT) に変更。
 *   文字列リテラルの typo によるバグを防ぎ、IDEの補完も効くようになる。
 *
 * [バグ修正] srcAddrPortRegisterToNat 処理 (現 NAT_REGISTER) で
 *   item.getPublicIP() 宛に item.getPrivatePort() を指定していた。
 *   PublicIP 宛であれば PublicPort を使うべきであり、item.getPublicPort() に修正した。
 */
public class SignalingServerSend extends Thread {

    /**
     * [変更] 送信モードを文字列 → enum に変更。
     * 旧実装では "srcAddrPortRegisterToNat" / "replyFromMainActivity" という
     * 長い文字列を直接比較していた。
     */
    public enum Mode {
        /** 周辺ユーザへ検索元ユーザのAddr/Portを通知し、NATに穴を開けさせる */
        NAT_REGISTER,
        /** 検索元ユーザへ周辺ユーザ一覧を返す */
        REPLY_RESULT
    }

    private final DatagramSocket socket;
    private final UserInfo srcUser;             // 検索を行った（送信元）ユーザ
    private final ArrayList<UserInfo> peerList; // 検索結果の周辺ユーザ一覧
    private final Mode mode;

    public SignalingServerSend(DatagramSocket socket, UserInfo srcUser,
                               ArrayList<UserInfo> peerList, Mode mode) {
        this.socket = socket;
        this.srcUser = srcUser;
        this.peerList = peerList;
        this.mode = mode;
    }

    @Override
    public void run() {
        switch (mode) {
            case NAT_REGISTER:
                sendNatRegister();
                break;
            case REPLY_RESULT:
                sendReplyResult();
                break;
        }
    }

    /**
     * 周辺ユーザ全員に、検索元ユーザの情報を送信する。
     * 受信側がそのAddr/PortをNATに記録することでホールパンチングが成立する。
     *
     * [バグ修正] 旧実装:
     *   InetAddress.getByName(item.getPublicIP()), item.getPrivatePort()
     *   → PublicIP 宛に PrivatePort を使っており不正な送信先だった。
     *
     * 修正後:
     *   PublicIP宛 → PublicPort を使用（NATを越えて届けるため）
     */
    private void sendNatRegister() {
        ProcessJSONObject pjo = new ProcessJSONObject();
        JSONObject jsonObject = pjo.getSrcUserInfo(srcUser);
        try {
            byte[] sendData = jsonObject.toString().getBytes();
            for (UserInfo peer : peerList) {
                // [バグ修正] item.getPrivatePort() → item.getPublicPort() に修正
                DatagramPacket packet = new DatagramPacket(
                        sendData, sendData.length,
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

    /**
     * 検索元ユーザに周辺ユーザ一覧を返す。
     */
    private void sendReplyResult() {
        ProcessJSONObject pjo = new ProcessJSONObject();
        JSONObject jsonObject = pjo.getUserInfoList(peerList);
        try {
            byte[] sendData = jsonObject.toString().getBytes();
            DatagramPacket packet = new DatagramPacket(
                    sendData, sendData.length,
                    InetAddress.getByName(srcUser.getPublicIP()), srcUser.getPublicPort()
            );
            socket.send(packet);
            System.out.println("REPLY_RESULT: " + srcUser.getPeerId()
                    + " へ検索結果 " + peerList.size() + "件 を返送");
        } catch (Exception e) {
            System.err.println("REPLY_RESULT 送信エラー: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
