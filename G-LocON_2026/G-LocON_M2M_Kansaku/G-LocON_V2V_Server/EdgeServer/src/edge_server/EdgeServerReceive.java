package edge_server;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.ArrayList;

import org.json.JSONObject;

/**
 * EdgeServerのメイン受信スレッド。
 *
 * 受信する processType:
 *   JOIN   - 車両がグループへ参加。既存メンバーとNATホールパンチングを開始。
 *   LEAVE  - 車両がグループから離脱。
 *   SEARCH - 車両が現在のメンバー一覧を取得（JOIN後の再取得など）。
 *
 * ログ出力項目（join_log, group_log）はここで行う。
 */
public class EdgeServerReceive extends Thread {

    private static final String JOIN   = "JOIN";
    private static final String LEAVE  = "LEAVE";
    private static final String SEARCH = "SEARCH";

    private final DatagramSocket socket;
    private final V2VGroupRegistry registry;
    private final String intersectionId;

    public EdgeServerReceive(DatagramSocket socket, V2VGroupRegistry registry, String intersectionId) {
        this.socket         = socket;
        this.registry       = registry;
        this.intersectionId = intersectionId;
    }

    @Override
    public void run() {
        System.out.println("EdgeServerReceive 起動: intersectionId=" + intersectionId);
        while (!Thread.currentThread().isInterrupted()) {
            DatagramPacket receivePacket = new DatagramPacket(new byte[1024], 1024);
            try {
                socket.receive(receivePacket);
                String raw = new String(receivePacket.getData(), 0, receivePacket.getLength());
                JSONObject jsonObject = new JSONObject(raw);
                ProcessJSONObject pjo = new ProcessJSONObject(jsonObject);
                String processType = pjo.getProcessType();

                // [NAT対応] 返信先はクライアント申告のpublicIP/Portではなく，実際の送信元アドレスを使う。
                // 端末がNAT配下でも，返信は端末が開けたマッピングを通って届く。
                // （NATが無いLAN内では送信元＝申告値のため動作は変わらない）
                String srcIP   = receivePacket.getAddress().getHostAddress();
                int    srcPort = receivePacket.getPort();

                if (processType.equals(JOIN)) {
                    UserInfo user = withObservedAddress(pjo.getUserInfo(), srcIP, srcPort);
                    onJoin(user);

                } else if (processType.equals(LEAVE)) {
                    UserInfo user = withObservedAddress(pjo.getUserInfo(), srcIP, srcPort);
                    int before = registry.size();
                    registry.leave(user);
                    System.out.printf("[LEAVE] ES=%-22s peer=%-8s members: %d→%d%n",
                            intersectionId, user.getPeerId(), before, registry.size());

                } else if (processType.equals(SEARCH)) {
                    UserInfo user = withObservedAddress(pjo.getUserInfo(), srcIP, srcPort);
                    onSearch(user);
                }

            } catch (Exception e) {
                System.err.println("EdgeServerReceive 受信エラー: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * JOIN処理:
     *   1. グループへ登録
     *   2. 既存メンバーに新規車両の情報をNAT_REGISTERで通知
     *   3. 新規車両へ既存メンバー一覧をREPLY_RESULTで返送
     */
    private void onJoin(UserInfo user) {
        ArrayList<UserInfo> existingMembers = registry.getMembers(user);
        int before = existingMembers.size();

        registry.join(user);
        System.out.printf("[JOIN ] ES=%-22s peer=%-8s eta=%.1fs  members: %d→%d%n",
                intersectionId, user.getPeerId(), user.getEta(), before, registry.size());

        // NAT_REGISTER: 既存メンバー全員へ新規車両の情報を通知
        if (!existingMembers.isEmpty()) {
            new EdgeServerSend(socket, user, existingMembers, EdgeServerSend.Mode.NAT_REGISTER).start();
            System.out.printf("[NAT ] ES=%-22s notify %d peers about %s%n",
                    intersectionId, existingMembers.size(), user.getPeerId());
        }

        // REPLY_RESULT: 新規車両へ既存メンバー一覧を返送
        new EdgeServerSend(socket, user, existingMembers, EdgeServerSend.Mode.REPLY_RESULT).start();
        System.out.printf("[SEND ] ES=%-22s → %-8s members=%d%n",
                intersectionId, user.getPeerId(), existingMembers.size());
    }

    /** 申告された publicIP/Port と実際の送信元が異なる場合（NAT配下）は送信元で上書きする */
    private UserInfo withObservedAddress(UserInfo user, String srcIP, int srcPort) {
        if (!srcIP.equals(user.getPublicIP()) || srcPort != user.getPublicPort()) {
            System.out.printf("[ADDR ] peer=%-8s 申告=%s:%d → 送信元=%s:%d%n",
                    user.getPeerId(), user.getPublicIP(), user.getPublicPort(), srcIP, srcPort);
            user.setPublicIP(srcIP);
            user.setPublicPort(srcPort);
        }
        return user;
    }

    private void onSearch(UserInfo user) {
        ArrayList<UserInfo> members = registry.getMembers(user);
        new EdgeServerSend(socket, user, members, EdgeServerSend.Mode.REPLY_RESULT).start();
        System.out.printf("[SRCH ] ES=%-22s → %-8s members=%d%n",
                intersectionId, user.getPeerId(), members.size());
    }
}
