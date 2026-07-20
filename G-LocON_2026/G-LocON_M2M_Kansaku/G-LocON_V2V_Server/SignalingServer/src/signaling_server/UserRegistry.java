package signaling_server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * [新規] シグナリングサーバが管理する登録ユーザ一覧のスレッドセーフなレジストリ。
 *
 * 旧実装では SignalingServerReceive がフィールドに ArrayList<UserInfo> を持ち、
 * 複数スレッド（受信スレッド + 複数の SignalingServerSend スレッド）から
 * synchronized なしで操作していたため ConcurrentModificationException のリスクがあった。
 *
 * このクラスに切り出すことで:
 *   1. スレッドセーフな CopyOnWriteArrayList で内部管理する
 *   2. ユーザリストの CRUD を一箇所に集約し、バグ修正箇所を明確にする
 *   3. SignalingServerReceive はユーザリストの実装を知らなくてよくなる
 */
public class UserRegistry {

    /**
     * [変更] ArrayList → CopyOnWriteArrayList に変更。
     * 読み取り（search）が多く書き込み（register/update/delete）が少ないため
     * CopyOnWriteArrayList が適している。書き込み時にコピーを作るため
     * イテレーション中の ConcurrentModificationException が発生しない。
     */
    private final List<UserInfo> userList = new CopyOnWriteArrayList<>();

    /**
     * ユーザを登録する。
     * @param userInfo 登録するユーザ情報
     */
    public void register(UserInfo userInfo) {
        userList.add(userInfo);
        System.out.println("REGISTER: ユーザ数=" + userList.size()
                + " peerId=" + userInfo.getPeerId());
    }

    /**
     * ユーザ情報を更新する。
     * publicIP + publicPort + privateIP + privatePort の4要素で同一ユーザを判定する。
     *
     * [バグ修正] 旧実装: userInfo.getPublicIP().equals(userInfo.getPublicIP())
     *   → 自身と自身を比較する typo で常に true になっていた。
     *   正しくは userInfoList.get(i).getPublicIP().equals(userInfo.getPublicIP()) と
     *   リスト要素 と 引数 を比較する。
     *
     * @param userInfo 更新後のユーザ情報
     */
    public void update(UserInfo userInfo) {
        for (int i = 0; i < userList.size(); i++) {
            UserInfo existing = userList.get(i);
            // [バグ修正] 旧: userInfo.getPublicIP().equals(userInfo.getPublicIP()) （自己比較→常にtrue）
            //            新: existing.getPublicIP().equals(userInfo.getPublicIP())  （正しい比較）
            if (existing.getPublicIP().equals(userInfo.getPublicIP())
                    && existing.getPublicPort() == userInfo.getPublicPort()
                    && existing.getPrivateIP().equals(userInfo.getPrivateIP())
                    && existing.getPrivatePort() == userInfo.getPrivatePort()) {
                userList.set(i, userInfo);
                System.out.println("UPDATE: peerId=" + userInfo.getPeerId());
                return;
            }
        }
        System.out.println("UPDATE: 対象ユーザが見つからなかった peerId=" + userInfo.getPeerId());
    }

    /**
     * ユーザを削除する。
     * @param userInfo 削除するユーザ情報
     */
    public void delete(UserInfo userInfo) {
        boolean removed = userList.removeIf(existing ->
                existing.getPublicIP().equals(userInfo.getPublicIP())
                        && existing.getPublicPort() == userInfo.getPublicPort()
                        && existing.getPrivateIP().equals(userInfo.getPrivateIP())
                        && existing.getPrivatePort() == userInfo.getPrivatePort()
        );
        System.out.println("DELETE: " + (removed ? "成功" : "対象なし")
                + " peerId=" + userInfo.getPeerId() + " ユーザ数=" + userList.size());
    }

    /**
     * 指定ユーザの位置から searchDistance メートル以内にいる他ユーザを返す。
     * 検索元ユーザ自身は結果に含まれない。
     *
     * @param searcher       検索を行ったユーザ
     * @param searchDistance 検索半径 (メートル)
     * @return 検索結果の周辺ユーザ一覧（スナップショット）
     */
    public ArrayList<UserInfo> search(UserInfo searcher, double searchDistance) {
        ArrayList<UserInfo> results = new ArrayList<>();
        HubenyDistance hubenyDistance = new HubenyDistance();

        for (UserInfo candidate : userList) {
            // 自分自身は除外
            if (candidate.getPublicIP().equals(searcher.getPublicIP())
                    && candidate.getPublicPort() == searcher.getPublicPort()
                    && candidate.getPrivateIP().equals(searcher.getPrivateIP())
                    && candidate.getPrivatePort() == searcher.getPrivatePort()) {
                continue;
            }

            double distance = hubenyDistance.calcDistance(
                    searcher.getLatitude(), searcher.getLongitude(),
                    candidate.getLatitude(), candidate.getLongitude()
            );

            // [追加] 常に距離ログを出力（ヒット・ミスともに）
            // ログを見て "距離=Xm, 閾値=Ym, miss" が続く場合は
            // クライアントの searchRange を増やすか、GPS精度を確認する
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

    /** 現在の登録ユーザ数を返す */
    public int size() {
        return userList.size();
    }
}
