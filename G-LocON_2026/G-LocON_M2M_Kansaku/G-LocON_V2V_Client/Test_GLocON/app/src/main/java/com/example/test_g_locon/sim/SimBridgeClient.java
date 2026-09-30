package com.example.test_g_locon.sim;

import android.util.Log;

import com.example.test_g_locon.navigation.Intersection;
import com.example.test_g_locon.navigation.IntersectionManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/**
 * [SUMOモード] PC上の SimBridge（SimBridge/sim_bridge.py）とやりとりするクライアント。
 *
 * SimBridge が SUMO の車両1台をこの端末に割り当て，その車のルートと位置を送ってくる。
 * アプリはそれを OSRM のルート・GPS の位置の代わりに使う（JOIN/LEAVE・P2P は通常と同じ処理）。
 *
 * やりとり（UDP, 既定ポート 55700）:
 *   端末 → SimBridge : SIM_HELLO（割り当てまで2秒ごと）/ SIM_ROUTE_REQ / SIM_BYE
 *   SimBridge → 端末 : SIM_ROUTE（ルート上の交差点）/ SIM_LOCATION（1秒ごと）/ SIM_END（目的地到着）
 *                      SIM_VEHICLES（周りの全車両，1秒ごと。「表示:全車両」用）
 *
 * P2P用のソケットとは別のソケットを使う（SimBridge との制御通信を P2P の受信処理と混ぜないため）。
 */
public class SimBridgeClient implements Runnable {

    private static final String TAG = "SimBridgeClient";
    private static final long HELLO_INTERVAL_MS = 2000;

    public interface Listener {
        /** 新しい車が割り当てられ，そのルート上の交差点が届いた */
        void onSimRoute(String vehicleId, List<Intersection> intersections);
        /** 割り当てられた車の位置（1秒ごと）。speed は m/s，bearing は北=0の時計回り */
        void onSimLocation(double lat, double lng, double speedMps, double bearing);
        /** 車が目的地に着いた（次の車の割り当てを待つ） */
        void onSimEnd(String vehicleId);
        /** 周りの全車両（P2Pでつながっていない車も含む。1秒ごと） */
        default void onSimVehicles(List<SimVehicle> vehicles) {}
    }

    private final String bridgeIp;
    private final int bridgePort;
    private final String peerId;
    private final Listener listener;
    private volatile boolean running = true;
    private volatile String vehicleId = null;
    private DatagramSocket socket;

    public SimBridgeClient(String bridgeIp, int bridgePort, String peerId, Listener listener) {
        this.bridgeIp = bridgeIp;
        this.bridgePort = bridgePort;
        this.peerId = peerId;
        this.listener = listener;
    }

    public String getVehicleId() { return vehicleId; }

    /** 終了する（SimBridge に SIM_BYE を送ってからソケットを閉じる） */
    public void stop() {
        running = false;
        final DatagramSocket s = socket;
        if (s == null) return;
        new Thread(() -> {
            sendNow(s, "SIM_BYE");
            s.close();
        }).start();
    }

    /** UIスレッドから呼ばれても通信しないよう，別スレッドで送る */
    private void send(String processType) {
        final DatagramSocket s = socket;
        if (s == null) return;
        new Thread(() -> sendNow(s, processType)).start();
    }

    private void sendNow(DatagramSocket s, String processType) {
        try {
            JSONObject o = new JSONObject();
            o.put("processType", processType);
            o.put("peerID", peerId);
            byte[] d = o.toString().getBytes();
            s.send(new DatagramPacket(d, d.length, InetAddress.getByName(bridgeIp), bridgePort));
        } catch (Exception e) {
            Log.d(TAG, "送信エラー: " + e);
        }
    }

    @Override
    public void run() {
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(500);
        } catch (Exception e) {
            Log.e(TAG, "ソケット生成に失敗: " + e);
            return;
        }
        long lastHello = 0;
        byte[] buf = new byte[65507];
        while (running) {
            if (vehicleId == null && System.currentTimeMillis() - lastHello > HELLO_INTERVAL_MS) {
                send("SIM_HELLO");
                lastHello = System.currentTimeMillis();
            }
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (Exception e) {
                if (running) Log.d(TAG, "受信エラー: " + e);
                continue;
            }
            try {
                handle(new JSONObject(new String(p.getData(), 0, p.getLength())));
            } catch (JSONException e) {
                Log.d(TAG, "JSON解析エラー: " + e);
            }
        }
    }

    private void handle(JSONObject m) throws JSONException {
        String pt = m.getString("processType");
        switch (pt) {
            case "SIM_ROUTE": {
                vehicleId = m.getString("vehicleId");
                // 離脱円の半径は SimBridge の設定に合わせる（仮想クライアントと同じ条件で比較するため）
                if (m.has("leaveDist")) IntersectionManager.setLeaveThresholdM(m.getDouble("leaveDist"));
                JSONArray arr = m.getJSONArray("intersections");
                List<Intersection> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    list.add(new Intersection(o.getString("intersectionId"), o.getDouble("lat"), o.getDouble("lon")));
                }
                Log.i(TAG, "車両割り当て: " + vehicleId + " 交差点数=" + list.size());
                listener.onSimRoute(vehicleId, list);
                break;
            }
            case "SIM_LOCATION": {
                String vid = m.getString("vehicleId");
                if (!vid.equals(vehicleId)) {      // ルートを取りこぼした → 再送を依頼
                    send("SIM_ROUTE_REQ");
                    return;
                }
                listener.onSimLocation(m.getDouble("latitude"), m.getDouble("longitude"),
                        m.getDouble("speed"), m.getDouble("bearing"));
                break;
            }
            case "SIM_VEHICLES": {
                // [[peerID, 緯度, 経度, 進行方向, 実機なら1], ...]
                JSONArray arr = m.getJSONArray("vehicles");
                List<SimVehicle> list = new ArrayList<>(arr.length());
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray v = arr.getJSONArray(i);
                    list.add(new SimVehicle(v.getString(0), v.getDouble(1), v.getDouble(2),
                            (float) v.getDouble(3), v.getInt(4) == 1));
                }
                listener.onSimVehicles(list);
                break;
            }
            case "SIM_END": {
                String vid = m.getString("vehicleId");
                Log.i(TAG, "目的地到着: " + vid);
                vehicleId = null;
                listener.onSimEnd(vid);
                break;
            }
            default:
                break;
        }
    }
}
