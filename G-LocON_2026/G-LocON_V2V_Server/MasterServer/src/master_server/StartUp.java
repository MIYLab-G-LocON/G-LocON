package master_server;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.SocketException;

/**
 * MasterServerのエントリポイント。
 *
 * 起動引数:
 *   args[0] - edge_servers.csv のパス（省略時: "edge_servers.csv"）
 *
 * edge_servers.csv フォーマット（#始まりはコメント行）:
 *   intersectionId,ip,port
 *   35.6580_139.7016,192.168.1.10,55600
 *   35.6591_139.7030,192.168.1.10,55601
 *
 * 使用例:
 *   java -cp .:json.jar master_server.StartUp edge_servers.csv
 */
public class StartUp {

    private static final int PORT = 55556;

    public static void main(String[] args) {
        String csvPath = (args.length >= 1) ? args[0] : "edge_servers.csv";

        EdgeServerRegistry registry = new EdgeServerRegistry();
        try {
            registry.loadFromCsv(csvPath);
        } catch (IOException e) {
            System.err.println("CSVファイル読み込みエラー: " + csvPath);
            e.printStackTrace();
            System.exit(1);
        }

        try {
            DatagramSocket socket = new DatagramSocket(PORT);
            MasterServerReceive receive = new MasterServerReceive(socket, registry);
            receive.start();
            System.out.println("MasterServer 起動: port=" + PORT
                    + " エッジサーバ登録数=" + registry.size());
        } catch (SocketException e) {
            System.err.println("ソケット生成エラー: port=" + PORT);
            e.printStackTrace();
        }
    }
}
