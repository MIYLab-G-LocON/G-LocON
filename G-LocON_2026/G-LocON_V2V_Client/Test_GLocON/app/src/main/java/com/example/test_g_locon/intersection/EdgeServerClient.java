package com.example.test_g_locon.intersection;

import android.content.Context;

import com.example.test_g_locon.main.OutputToCSV;
import com.example.test_g_locon.main.UserInfo;
import com.example.test_g_locon.navigation.Intersection;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * EdgeServerへJOIN/LEAVEをUDP送信するクライアント。
 *
 * P2P.javaと同じく ExecutorService + Runnable パターンで非同期実行する。
 * 送信後にJOINの場合はp2p_logにt_join_sentを記録する。
 */
public class EdgeServerClient {

    public interface IEdgeServerCallback {
        /** JOIN送信完了後に呼ばれる（P2P確立はP2P.java側でハンドリング） */
        void onJoinSent(Intersection intersection, long tJoinSentMs);
        void onLeaveSent(Intersection intersection);
    }

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final UserInfo myUserInfo;
    private IEdgeServerCallback callback;

    // p2p_log.csv
    private final OutputToCSV p2pLog;

    public EdgeServerClient(Context context, UserInfo myUserInfo) {
        this.myUserInfo = myUserInfo;
        p2pLog = new OutputToCSV(context, "p2p_log.csv");
        p2pLog.OutputFieledName(
                "intersectionId", "t_join_sent_ms", "eta_at_join_sec", "edgeServerIp", "edgeServerPort");
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
        executor.shutdown();
        p2pLog.fileClose();
    }

    // ---- 内部Runnableクラス ----

    private class JoinTask implements Runnable {
        private final Intersection intersection;

        JoinTask(Intersection intersection) { this.intersection = intersection; }

        @Override
        public void run() {
            try (DatagramSocket socket = new DatagramSocket()) {
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
            try (DatagramSocket socket = new DatagramSocket()) {
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

                System.out.println("LEAVE送信: intersectionId=" + intersection.getIntersectionId());

                if (callback != null) callback.onLeaveSent(intersection);

            } catch (Exception e) {
                System.err.println("EdgeServerClient LEAVE エラー: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}
