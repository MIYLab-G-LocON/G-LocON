package edge_server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 交差点V2Vグループのメンバーを管理するレジストリ。
 *
 * SignalingServerのUserRegistryと異なり，距離によるフィルタリングは行わない。
 * 同一交差点に向かう全車両が対象であるため，joinした車両は全員グループメンバーとなる。
 */
public class V2VGroupRegistry {

    private final String intersectionId;
    private final List<UserInfo> memberList = new CopyOnWriteArrayList<>();

    public V2VGroupRegistry(String intersectionId) {
        this.intersectionId = intersectionId;
    }

    public void join(UserInfo userInfo) {
        // 同一ユーザの重複参加を防ぐ（publicIP+publicPort+privateIP+privatePortで識別）
        for (UserInfo existing : memberList) {
            if (isSameUser(existing, userInfo)) {
                // ETAのみ更新
                int idx = memberList.indexOf(existing);
                memberList.set(idx, userInfo);
                System.out.println("JOIN(UPDATE): intersectionId=" + intersectionId
                        + " peerId=" + userInfo.getPeerId()
                        + " eta=" + userInfo.getEta() + "s");
                return;
            }
        }
        memberList.add(userInfo);
        System.out.println("JOIN: intersectionId=" + intersectionId
                + " peerId=" + userInfo.getPeerId()
                + " eta=" + userInfo.getEta() + "s"
                + " メンバー数=" + memberList.size());
    }

    public void leave(UserInfo userInfo) {
        boolean removed = memberList.removeIf(existing -> isSameUser(existing, userInfo));
        System.out.println("LEAVE: " + (removed ? "成功" : "対象なし")
                + " intersectionId=" + intersectionId
                + " peerId=" + userInfo.getPeerId()
                + " メンバー数=" + memberList.size());
    }

    /** グループの全メンバーを返す（スナップショット）。自分自身は除外。 */
    public ArrayList<UserInfo> getMembers(UserInfo requester) {
        ArrayList<UserInfo> result = new ArrayList<>();
        for (UserInfo member : memberList) {
            if (!isSameUser(member, requester)) {
                result.add(member);
            }
        }
        return result;
    }

    public int size() { return memberList.size(); }

    private boolean isSameUser(UserInfo a, UserInfo b) {
        return a.getPublicIP().equals(b.getPublicIP())
                && a.getPublicPort() == b.getPublicPort()
                && a.getPrivateIP().equals(b.getPrivateIP())
                && a.getPrivatePort() == b.getPrivatePort();
    }
}
