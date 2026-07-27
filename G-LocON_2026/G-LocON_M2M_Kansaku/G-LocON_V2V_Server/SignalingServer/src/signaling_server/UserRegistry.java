package signaling_server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * シグナリングサーバが管理するユーザ一覧のレジストリ。
 *
 * 問題: 端末が強制終了・電池切れ・再インストール等でDELETEを送信できない場合、
 *      古いエントリがサーバに残り続け、ゴーストピアとして他端末のSEARCH結果に出続ける。
 *
 * 対策: TTLベースの自動削除
 *   - REGISTER / UPDATE / SEARCH を受信するたびにそのエントリのタイムスタンプを更新する
 *   - SEARCHは5秒ごとに必ず送られるためハートビートとして機能する
 *   - TTL_SEC 秒間何も受信しなければゴーストと判断して自動削除する
 */
public class UserRegistry {

    /** エントリの有効期限（秒）。SEARCHが5秒ごとなのでその数倍を設定 */
    private static final long TTL_SEC = 30;
    /** TTLチェックの実行間隔（秒） */
    private static final long TTL_CHECK_INTERVAL_SEC = 10;

    private final List<UserInfo> userList = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService ttlScheduler = Executors.newSingleThreadScheduledExecutor();

    public UserRegistry() {
        ttlScheduler.scheduleAtFixedRate(this::removeExpiredEntries,
                TTL_CHECK_INTERVAL_SEC, TTL_CHECK_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    /** ユーザを新規登録する */
    public void register(UserInfo userInfo) {
        userInfo.touchLastUpdated();
        userList.add(userInfo);
        System.out.println("REGISTER(新規): peerId=" + userInfo.getPeerId()
                + " ユーザ数=" + userList.size());
    }

    /**
     * ユーザ情報を更新する。
     * UPDATEはGPS更新ごとに送られるため、ハートビートを兼ねてタイムスタンプを更新する。
     */
    public void update(UserInfo userInfo) {
        for (int i = 0; i < userList.size(); i++) {
            UserInfo existing = userList.get(i);
            if (isSameUser(existing, userInfo)) {
                userInfo.touchLastUpdated();
                userList.set(i, userInfo);
                System.out.println("UPDATE: peerId=" + userInfo.getPeerId());
                return;
            }
        }
        System.out.println("UPDATE: 対象ユーザが見つからなかった peerId=" + userInfo.getPeerId());
    }

    /** ユーザを削除する */
    public void delete(UserInfo userInfo) {
        boolean removed = userList.removeIf(existing -> isSameUser(existing, userInfo));
        System.out.println("DELETE: " + (removed ? "成功" : "対象なし")
                + " peerId=" + userInfo.getPeerId() + " ユーザ数=" + userList.size());
    }

    /**
     * 周辺ユーザを検索し、SEARCHを送ってきたユーザのタイムスタンプを更新する。
     * SEARCHは5秒ごとに必ず送られるためハートビートとして機能する。
     */
    public ArrayList<UserInfo> search(UserInfo searcher, double searchDistance) {
        // SEARCHをハートビートとして扱い、タイムスタンプを更新する
        for (int i = 0; i < userList.size(); i++) {
            UserInfo existing = userList.get(i);
            if (isSameUser(existing, searcher)) {
                existing.touchLastUpdated();
                break;
            }
        }

        ArrayList<UserInfo> results = new ArrayList<>();
        HubenyDistance hubenyDistance = new HubenyDistance();

        for (UserInfo candidate : userList) {
            if (isSameUser(candidate, searcher)) continue;

            double distance = hubenyDistance.calcDistance(
                    searcher.getLatitude(), searcher.getLongitude(),
                    candidate.getLatitude(), candidate.getLongitude()
            );

            if (distance <= searchDistance) {
                System.out.printf("検索ヒット: %s と %s の距離=%.1fm (閾値=%.0fm)%n",
                        searcher.getPeerId(), candidate.getPeerId(), distance, searchDistance);
                results.add(candidate);
            } else {
                System.out.printf("検索ミス: %s と %s の距離=%.1fm > 閾値=%.0fm%n",
                        searcher.getPeerId(), candidate.getPeerId(), distance, searchDistance);
            }
        }
        return results;
    }

    /** TTL期限切れエントリを削除する（10秒ごとに実行） */
    private void removeExpiredEntries() {
        long now = System.currentTimeMillis();
        long ttlMs = TTL_SEC * 1000;
        List<UserInfo> expired = new ArrayList<>();
        for (UserInfo u : userList) {
            if (now - u.getLastUpdatedMs() > ttlMs) {
                expired.add(u);
            }
        }
        if (!expired.isEmpty()) {
            userList.removeAll(expired);
            for (UserInfo u : expired) {
                System.out.printf("[TTL削除] peerId=%s 最終受信から%d秒経過%n",
                        u.getPeerId(), (now - u.getLastUpdatedMs()) / 1000);
            }
            System.out.println("[TTL削除後] 残ユーザ数=" + userList.size());
        }
    }

    /** publicIP + publicPort + privateIP + privatePort の4要素で同一ユーザを判定 */
    private boolean isSameUser(UserInfo a, UserInfo b) {
        return a.getPublicIP().equals(b.getPublicIP())
                && a.getPublicPort() == b.getPublicPort()
                && a.getPrivateIP().equals(b.getPrivateIP())
                && a.getPrivatePort() == b.getPrivatePort();
    }

    public int size() { return userList.size(); }

    public void shutdown() { ttlScheduler.shutdownNow(); }
}
