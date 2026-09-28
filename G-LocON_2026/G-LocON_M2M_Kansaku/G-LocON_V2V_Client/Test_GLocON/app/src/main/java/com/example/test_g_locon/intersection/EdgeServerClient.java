package com.example.test_g_locon.intersection;

import android.content.Context;

import com.example.test_g_locon.main.OutputToCSV;
import com.example.test_g_locon.main.UserInfo;
import com.example.test_g_locon.navigation.Intersection;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * EdgeServerへJOIN/LEAVEをUDP送信するクライアント。
 *
 * P2P.javaと同じく ExecutorService + Runnable パターンで非同期実行する。
 * 送信後にJOINの場合はp2p_logにt_join_sentを記録する。
 *
 * [NAT対応] JOIN/LEAVE は STUN・シグナリングと同じ共有ソケットから送信する。
 * EdgeServer は返信（メンバー一覧・NAT_REGISTER通知）を共有ソケットのアドレスへ送るため，
 * 使い捨てソケットから送ると NAT 環境では共有ソケット側にマッピングが無く返信が破棄される。
 *
 * [NAT対応] JOIN中の交差点には KEEPALIVE_INTERVAL_SEC 秒ごとに KEEPALIVE を送り，
 * NATのマッピング（EdgeServer→端末の経路）がタイムアウトで閉じないようにする。
 * 後から別車両がJOINした際の NAT_REGISTER（doUDPHolePunching）通知を確実に受け取るため。
 */
public class EdgeServerClient {

    public interface IEdgeServerCallback {
        /** JOIN送信完了後に呼ばれる（P2P確立はP2P.java側でハンドリング） */
        void onJoinSent(Intersection intersection, long tJoinSentMs);
        void onLeaveSent(Intersection intersection);
    }

    private final ExecutorService executor = Executors.newCachedThreadPool();
    /** 携帯キャリアのNATはUDPマッピングを数十秒で破棄することがあるため，それより短い間隔にする */
    private static final long KEEPALIVE_INTERVAL_SEC = 15;
    private final ScheduledExecutorService keepAliveScheduler = Executors.newSingleThreadScheduledExecutor();
    /** JOIN中の交差点（intersectionId → Intersection） */
    private final Map<String, Intersection> joined = new ConcurrentHashMap<>();
    private final UserInfo myUserInfo;
    /** STUN・シグナリング・P2Pと共有するソケット（受信は P2PReceiver が担当） */
    private final DatagramSocket socket;
    private IEdgeServerCallback callback;

    // p2p_log.csv
    private final OutputToCSV p2pLog;

    public EdgeServerClient(Context context, DatagramSocket socket, UserInfo myUserInfo) {
        this.socket = socket;
        this.myUserInfo = myUserInfo;
        p2pLog = new OutputToCSV(context, "p2p_log.csv");
        p2pLog.OutputFieledName(
                "intersectionId", "t_join_sent_ms", "eta_at_join_sec", "edgeServerIp", "edgeServerPort");
        keepAliveScheduler.scheduleAtFixedRate(this::sendKeepAlives,
                KEEPALIVE_INTERVAL_SEC, KEEPALIVE_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    /** JOIN中の全交差点のEdgeServerへKEEPALIVEを送る */
    private void sendKeepAlives() {
        for (Intersection intersection : joined.values()) {
            try {
                JSONObject json = new EdgeServerJSONObject().buildKeepAlive(
                        myUserInfo, intersection.getIntersectionId());
                byte[] data = json.toString().getBytes();
                socket.send(new DatagramPacket(
                        data, data.length,
                        InetAddress.getByName(intersection.getEdgeServerIp()),
                        intersection.getEdgeServerPort()));
            } catch (Exception e) {
                System.err.println("EdgeServerClient KEEPALIVE エラー: " + e.getMessage());
            }
        }
    }

    public void setCallback(IEdgeServerCallback callback) {
        this.callback = callback;
    }

    /** JOIN要求をEdgeServerへ非同期送信する */
    public void join(Intersection intersection) {
        executor.submit(new JoinTask(intersection));
    }

    /** LEAVE通知をEdgeServerへ非同期送信する */
    public void leave(Intersection intersection) {
        executor.submit(new LeaveTask(intersection));
    }

    public void shutdown() {
        keepAliveScheduler.shutdownNow();
        joined.clear();
        executor.shutdown();
        p2pLog.fileClose();
    }

    // ---- 内部Runnableクラス ----

    private class JoinTask implements Runnable {
        private final Intersection intersection;

        JoinTask(Intersection intersection) { this.intersection = intersection; }

        @Override
        public void run() {
            try {
                EdgeServerJSONObject jsonBuilder = new EdgeServerJSONObject();
                JSONObject json = jsonBuilder.buildJoin(
                        myUserInfo,
                        intersection.getIntersectionId(),
                        intersection.getEtaSec()
                );
                byte[] data = json.toString().getBytes();
                DatagramPacket packet = new DatagramPacket(
                        data, data.length,
                        InetAddress.getByName(intersection.getEdgeServerIp()),
                        intersection.getEdgeServerPort()
                );
                socket.send(packet);
                long tSent = System.currentTimeMillis();
                joined.put(intersection.getIntersectionId(), intersection);

                System.out.println("JOIN送信: intersectionId=" + intersection.getIntersectionId()
                        + " eta=" + intersection.getEtaSec() + "s");

                // p2p_log: JOIN送信時刻を記録（P2P確立時刻はP2P.java側で追記）
                p2pLog.OutputData(
                        intersection.getIntersectionId(),
                        String.valueOf(tSent),
                        String.format("%.1f", intersection.getEtaSec()),
                        intersection.getEdgeServerIp(),
                        String.valueOf(intersection.getEdgeServerPort())
                );

                if (callback != null) callback.onJoinSent(intersection, tSent);

            } catch (Exception e) {
                System.err.println("EdgeServerClient JOIN エラー: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private class LeaveTask implements Runnable {
        private final Intersection intersection;

        LeaveTask(Intersection intersection) { this.intersection = intersection; }

        @Override
        public void run() {
            try {
                EdgeServerJSONObject jsonBuilder = new EdgeServerJSONObject();
                JSONObject json = jsonBuilder.buildLeave(
                        myUserInfo,
                        intersection.getIntersectionId()
                );
                byte[] data = json.toString().getBytes();
                DatagramPacket packet = new DatagramPacket(
                        data, data.length,
                        InetAddress.getByName(intersection.getEdgeServerIp()),
                        intersection.getEdgeServerPort()
                );
                socket.send(packet);
                joined.remove(intersection.getIntersectionId());

                System.out.println("LEAVE送信: intersectionId=" + intersection.getIntersectionId());

                if (callback != null) callback.onLeaveSent(intersection);

            } catch (Exception e) {
                System.err.println("EdgeServerClient LEAVE エラー: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}
