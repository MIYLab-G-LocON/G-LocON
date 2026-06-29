package signaling_server;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

import org.json.JSONObject;

/**
 * シグナリングサーバのメイン受信スレッド。
 * UDPパケットを受信してprocessTypeに応じてユーザ登録・更新・検索・削除を行う。
 *
 * [変更] ユーザリストの管理を UserRegistry クラスに分離した。
 *   旧実装では ArrayList<UserInfo> userInfoList を直接フィールドに持ち、
 *   このクラスが CRUD 処理も行っていた。
 *   UserRegistry への委譲により:
 *     - スレッドセーフ性は UserRegistry が保証する
 *     - このクラスは「受信して振り分ける」責務に専念できる
 *
 * [バグ修正] onUpdate() の自己比較 typo は UserRegistry 側で修正済み。
 *
 * [変更] processType 文字列をローカル変数 → クラス定数に変更（可読性向上）
 *
 * [変更] 例外を catch(Exception e){} で握りつぶさず、スタックトレースを出力する
 */
public class SignalingServerReceive extends Thread {

    // [変更] processType 文字列をクラス定数として定義（旧はメソッド内ローカル変数だった）
    private static final String REGISTER = "REGISTER";
    private static final String UPDATE   = "UPDATE";
    private static final String SEARCH   = "SEARCH";
    private static final String DELETE   = "DELETE";

    private final DatagramSocket socket;
    // [変更] ArrayList<UserInfo> → UserRegistry に置き換え
    private final UserRegistry userRegistry;

    public SignalingServerReceive(DatagramSocket socket, UserRegistry userRegistry) {
        this.socket = socket;
        this.userRegistry = userRegistry;
    }

    @Override
    public void run() {
        System.out.println("SignalingServerReceive 起動");
        while (!Thread.currentThread().isInterrupted()) {
            DatagramPacket receivePacket = new DatagramPacket(new byte[1024], 1024);
            try {
                socket.receive(receivePacket);
                String result = new String(receivePacket.getData(), 0, receivePacket.getLength());
                JSONObject jsonObject = new JSONObject(result);
                ProcessJSONObject pjo = new ProcessJSONObject(jsonObject);
                String processType = pjo.getProcessType();

                if (processType.equals(REGISTER)) {
                    UserInfo user = pjo.getUserInfo();
                    userRegistry.register(user);
                    showInfo(user, REGISTER);

                } else if (processType.equals(UPDATE)) {
                    UserInfo user = pjo.getUserInfo();
                    userRegistry.update(user);
                    showInfo(user, UPDATE);

                } else if (processType.equals(SEARCH)) {
                    UserInfo searcher = pjo.getUserInfo();
                    double distance = pjo.getSearchDistance();
                    System.out.println(searcher.getPeerId() + " からの SEARCH 要求: 半径=" + distance + "m");
                    onSearch(searcher, distance);

                } else if (processType.equals(DELETE)) {
                    UserInfo user = pjo.getUserInfo();
                    userRegistry.delete(user);
                }

            } catch (Exception e) {
                // [変更] 旧実装: catch(Exception e){} で例外を握りつぶしていた
                //          → スタックトレースを出力して問題を可視化する
                System.err.println("SignalingServerReceive 受信エラー: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * 検索処理:
     *   1. UserRegistry で周辺ユーザを検索
     *   2. 周辺ユーザに検索元ユーザの情報を通知（NATホールパンチング準備）
     *   3. 検索元ユーザに検索結果を返送
     */
    private void onSearch(UserInfo searcher, double searchDistance) {
        var searchResults = userRegistry.search(searcher, searchDistance);

        // NATホールパンチング準備: 周辺ユーザに検索元ユーザのAddr/Portを通知
        SignalingServerSend natNotify = new SignalingServerSend(
                socket, searcher, searchResults, SignalingServerSend.Mode.NAT_REGISTER);
        natNotify.start();
        System.out.println(searcher.getPeerId() + ": NAT_REGISTER スレッド起動");

        // 検索元ユーザへの結果返送
        SignalingServerSend reply = new SignalingServerSend(
                socket, searcher, searchResults, SignalingServerSend.Mode.REPLY_RESULT);
        reply.start();
        System.out.println(searcher.getPeerId() + ": REPLY_RESULT スレッド起動");
    }

    /** デバッグ用ユーザ情報表示 */
    private void showInfo(UserInfo userInfo, String type) {
        System.out.println("\n--- " + type + " ---");
        System.out.println("peerId="      + userInfo.getPeerId());
        System.out.println("publicIP="    + userInfo.getPublicIP()    + ":" + userInfo.getPublicPort());
        System.out.println("privateIP="   + userInfo.getPrivateIP()   + ":" + userInfo.getPrivatePort());
        System.out.printf ("位置=(%.6f, %.6f)%n", userInfo.getLatitude(), userInfo.getLongitude());
        System.out.println("登録ユーザ数=" + userRegistry.size() + "\n");
    }
}
