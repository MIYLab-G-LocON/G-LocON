package master_server;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;

import org.json.JSONObject;

/**
 * エッジサーバ一覧をクライアントへ返送するスレッド。
 */
public class MasterServerSend extends Thread {

    private final DatagramSocket socket;
    private final String clientIp;
    private final int clientPort;
    private final List<EdgeServerInfo> edgeServers;

    public MasterServerSend(DatagramSocket socket, String clientIp, int clientPort,
                            List<EdgeServerInfo> edgeServers) {
        this.socket      = socket;
        this.clientIp    = clientIp;
        this.clientPort  = clientPort;
        this.edgeServers = edgeServers;
    }

    @Override
    public void run() {
        ProcessJSONObject pjo = new ProcessJSONObject();
        JSONObject json = pjo.getEdgeServerList(edgeServers);
        try {
            byte[] data = json.toString().getBytes();
            DatagramPacket packet = new DatagramPacket(
                    data, data.length,
                    InetAddress.getByName(clientIp), clientPort
            );
            socket.send(packet);
            System.out.println("EDGE_SERVER_LIST: " + clientIp + ":" + clientPort
                    + " へ " + edgeServers.size() + "件 を返送");
        } catch (Exception e) {
            System.err.println("MasterServerSend 送信エラー: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
