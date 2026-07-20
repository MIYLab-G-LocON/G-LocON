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

                if (processType.equals(JOIN)) {
                    UserInfo user = pjo.getUserInfo();
                    onJoin(user);

                } else if (processType.equals(LEAVE)) {
                    UserInfo user = pjo.getUserInfo();
                    registry.leave(user);
                    logGroup();

                } else if (processType.equals(SEARCH)) {
                    UserInfo user = pjo.getUserInfo();
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
        // JOIN前の既存メンバー（NATホールパンチング対象）
        ArrayList<UserInfo> existingMembers = registry.getMembers(user);

        registry.join(user);
        logJoin(user);
        logGroup();

        // NAT_REGISTER: 既存メンバー全員へ新規車両の情報を通知
        EdgeServerSend natNotify = new EdgeServerSend(
                socket, user, existingMembers, EdgeServerSend.Mode.NAT_REGISTER);
        natNotify.start();

        // REPLY_RESULT: 新規車両へ既存メンバー一覧を返送
        EdgeServerSend reply = new EdgeServerSend(
                socket, user, existingMembers, EdgeServerSend.Mode.REPLY_RESULT);
        reply.start();
    }

    /** SEARCH処理: メンバー一覧を要求元へ返送（JOIN後の再取得など） */
    private void onSearch(UserInfo user) {
        ArrayList<UserInfo> members = registry.getMembers(user);
        EdgeServerSend reply = new EdgeServerSend(
                socket, user, members, EdgeServerSend.Mode.REPLY_RESULT);
        reply.start();
        System.out.println("SEARCH: " + user.getPeerId()
                + " へメンバー一覧 " + members.size() + "件 を返送");
    }

    // ---- ログ出力 ----

    private void logJoin(UserInfo user) {
        // join_log.csv 用: intersectionId, peerId, t_join_acked, eta_at_join
        System.out.printf("[join_log] intersectionId=%s peerId=%s t=%d eta=%.1fs%n",
                intersectionId, user.getPeerId(),
                System.currentTimeMillis(), user.getEta());
    }

    private void logGroup() {
        // group_log.csv 用: intersectionId, timestamp, member_count
        System.out.printf("[group_log] intersectionId=%s t=%d member_count=%d%n",
                intersectionId, System.currentTimeMillis(), registry.size());
    }
}
