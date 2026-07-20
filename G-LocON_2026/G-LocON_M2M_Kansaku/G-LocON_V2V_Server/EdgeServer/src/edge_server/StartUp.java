package edge_server;

import java.net.DatagramSocket;
import java.net.SocketException;

/**
 * EdgeServerのエントリポイント。
 *
 * 起動引数:
 *   args[0] - intersectionId  例: "35.6580_139.7016"（緯度_経度）
 *   args[1] - port            例: "55600"
 *
 * 使用例:
 *   java -cp .:json.jar edge_server.StartUp 35.6580_139.7016 55600
 */
public class StartUp {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("使用方法: StartUp <intersectionId> <port>");
            System.exit(1);
        }

        String intersectionId = args[0];
        int port;
        try {
            port = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("ポート番号が不正: " + args[1]);
            System.exit(1);
            return;
        }

        try {
            DatagramSocket socket = new DatagramSocket(port);
            V2VGroupRegistry registry = new V2VGroupRegistry(intersectionId);
            EdgeServerReceive receive = new EdgeServerReceive(socket, registry, intersectionId);
            receive.start();
            System.out.println("EdgeServer 起動: intersectionId=" + intersectionId + " port=" + port);
        } catch (SocketException e) {
            System.err.println("ソケット生成エラー: port=" + port);
            e.printStackTrace();
        }
    }
}
