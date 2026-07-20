package master_server;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.List;

import org.json.JSONObject;

/**
 * MasterServerのメイン受信スレッド。
 *
 * 受信する processType:
 *   INTERSECTION_QUERY - クライアントから交差点IDリストを受け取り，
 *                        対応するエッジサーバ情報をMasterServerSendで返送する。
 */
public class MasterServerReceive extends Thread {

    private static final String INTERSECTION_QUERY = "INTERSECTION_QUERY";

    private final DatagramSocket socket;
    private final EdgeServerRegistry registry;

    public MasterServerReceive(DatagramSocket socket, EdgeServerRegistry registry) {
        this.socket   = socket;
        this.registry = registry;
    }

    @Override
    public void run() {
        System.out.println("MasterServerReceive 起動");
        while (!Thread.currentThread().isInterrupted()) {
            DatagramPacket receivePacket = new DatagramPacket(new byte[4096], 4096);
            try {
                socket.receive(receivePacket);
                String raw = new String(receivePacket.getData(), 0, receivePacket.getLength());
                JSONObject jsonObject = new JSONObject(raw);
                ProcessJSONObject pjo = new ProcessJSONObject(jsonObject);
                String processType = pjo.getProcessType();

                if (processType.equals(INTERSECTION_QUERY)) {
                    List<String> ids = pjo.getIntersectionIds();
                    System.out.println("INTERSECTION_QUERY: " + ids.size() + "件の交差点IDを受信");

                    List<EdgeServerInfo> servers = registry.resolve(ids);

                    String clientIp   = receivePacket.getAddress().getHostAddress();
                    int    clientPort = receivePacket.getPort();

                    MasterServerSend send = new MasterServerSend(
                            socket, clientIp, clientPort, servers);
                    send.start();
                }

            } catch (Exception e) {
                System.err.println("MasterServerReceive 受信エラー: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}
