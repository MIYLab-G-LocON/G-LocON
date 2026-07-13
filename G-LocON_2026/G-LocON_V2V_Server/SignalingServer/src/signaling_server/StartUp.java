package signaling_server;

import java.net.DatagramSocket;
import java.net.SocketException;

/**
 * シグナリングサーバのエントリポイント。
 *
 * [変更] ArrayList<UserInfo> の生成と受け渡しをやめ、UserRegistry を生成して渡す。
 *   ユーザリストのスレッドセーフ性は UserRegistry が保証するため、
 *   StartUp 側では気にしなくてよい。
 */
public class StartUp {

    private static final int PORT = 55555;

    public static void main(String[] args) {
        try {
            DatagramSocket socket = new DatagramSocket(PORT);

            // [変更] new ArrayList<>() → new UserRegistry() に変更
            UserRegistry userRegistry = new UserRegistry();

            SignalingServerReceive receive = new SignalingServerReceive(socket, userRegistry);
            receive.start();
            System.out.println("シグナリングサーバ起動: ポート=" + PORT);

        } catch (SocketException e) {
            System.err.println("ソケット生成エラー");
            e.printStackTrace();
        }
    }
}
