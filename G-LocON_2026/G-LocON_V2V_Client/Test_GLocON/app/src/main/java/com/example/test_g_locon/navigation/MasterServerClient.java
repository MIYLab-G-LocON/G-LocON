package com.example.test_g_locon.navigation;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * MasterServerへ交差点IDリストを送信し，エッジサーバAddr/Portを受信するクラス。
 *
 * Runnable + ExecutorService パターンで非同期実行する（P2P.javaと同じ構造）。
 * 結果はコールバックインタフェース IMasterServerCallback で返す。
 */
public class MasterServerClient implements Runnable {

    public interface IMasterServerCallback {
        /** エッジサーバAddr/Portの取得が完了したとき呼ばれる */
        void onEdgeServerListReceived(List<Intersection> updatedIntersections);
    }

    private static final int TIMEOUT_MS = 5000;

    private final String masterServerIp;
    private final int masterServerPort;
    private final String myPublicIp;
    private final int myPublicPort;
    private final List<Intersection> intersections; // エッジサーバAddrを書き込む対象
    private final IMasterServerCallback callback;

    public MasterServerClient(String masterServerIp, int masterServerPort,
                              String myPublicIp, int myPublicPort,
                              List<Intersection> intersections,
                              IMasterServerCallback callback) {
        this.masterServerIp   = masterServerIp;
        this.masterServerPort = masterServerPort;
        this.myPublicIp       = myPublicIp;
        this.myPublicPort     = myPublicPort;
        this.intersections    = intersections;
        this.callback         = callback;
    }

    @Override
    public void run() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MS);

            // INTERSECTION_QUERY 送信
            JSONObject query = new JSONObject();
            query.put("processType", "INTERSECTION_QUERY");
            JSONArray ids = new JSONArray();
            for (Intersection i : intersections) ids.put(i.getIntersectionId());
            query.put("intersectionIds", ids);

            byte[] sendData = query.toString().getBytes();
            DatagramPacket sendPacket = new DatagramPacket(
                    sendData, sendData.length,
                    InetAddress.getByName(masterServerIp), masterServerPort
            );
            socket.send(sendPacket);

            // EDGE_SERVER_LIST 受信
            DatagramPacket recvPacket = new DatagramPacket(new byte[4096], 4096);
            socket.receive(recvPacket);
            String raw = new String(recvPacket.getData(), 0, recvPacket.getLength());
            JSONObject response = new JSONObject(raw);

            JSONArray edgeServers = response.getJSONArray("edgeServers");
            for (int i = 0; i < edgeServers.length(); i++) {
                JSONObject entry = edgeServers.getJSONObject(i);
                String id   = entry.getString("intersectionId");
                String ip   = entry.getString("ip");
                int    port = entry.getInt("port");
                // 対応する Intersection オブジェクトにエッジサーバAddrを設定
                for (Intersection intersection : intersections) {
                    if (intersection.getIntersectionId().equals(id)) {
                        intersection.setEdgeServerIp(ip);
                        intersection.setEdgeServerPort(port);
                        break;
                    }
                }
            }
            System.out.println("MasterServerClient: " + edgeServers.length() + "件 取得完了");
            callback.onEdgeServerListReceived(intersections);

        } catch (Exception e) {
            System.err.println("MasterServerClient エラー: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
