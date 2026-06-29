package com.example.test_g_locon.P2P;

// [変更] AsyncTask → Runnable に置き換え
import android.util.Log;

import com.example.test_g_locon.main.UserInfo;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;

/**
 * NATホールパンチング用の HelloPacket を送信するクラス。
 *
 * [変更] extends AsyncTask → implements Runnable
 *   - doInBackground() の内容を run() に移動
 *   - P2P クラスの ExecutorService から executor.execute(this) で呼び出す
 *
 * [変更] 同NAT判定ロジックを sendTo() ヘルパーメソッドに共通化
 *   （P2PSender と重複していたロジックを統一形式で整理）
 */
public class P2PNatRegisterSender implements Runnable {

    private static final String TAG = "P2PNatRegSender";

    private final DatagramSocket socket;
    private final String myPublicIP;
    private final int myPublicPort;
    private final EP2PProcess eP2PProcess;
    private final ArrayList<UserInfo> peripheralUsers; // NATRegisterDstUsers で使用
    private final UserInfo srcUser;                    // NATRegisterSrcUser で使用

    /** 周辺ユーザ全員にHelloPacketを送るコンストラクタ */
    P2PNatRegisterSender(DatagramSocket socket, String myPublicIP, int myPublicPort,
                         ArrayList<UserInfo> peripheralUsers, EP2PProcess eP2PProcess) {
        this.socket = socket;
        this.myPublicIP = myPublicIP;
        this.myPublicPort = myPublicPort;
        this.peripheralUsers = peripheralUsers;
        this.srcUser = null;
        this.eP2PProcess = eP2PProcess;
    }

    /** 自分を検索してきたユーザ1人にHelloPacketを送るコンストラクタ */
    P2PNatRegisterSender(DatagramSocket socket, String myPublicIP, int myPublicPort,
                         UserInfo srcUserInfo, EP2PProcess eP2PProcess) {
        this.socket = socket;
        this.myPublicIP = myPublicIP;
        this.myPublicPort = myPublicPort;
        this.peripheralUsers = null;
        this.srcUser = srcUserInfo;
        this.eP2PProcess = eP2PProcess;
    }

    /**
     * [変更] doInBackground() → run()
     */
    @Override
    public void run() {
        byte[] sendData;
        try {
            JSONObject jsonObject = new JSONObject();
            // HelloPacket は相手に届いても processType が未知なので無視される
            jsonObject.put("processType", "HelloPacket");
            sendData = jsonObject.toString().getBytes();
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        switch (eP2PProcess) {
            case NATRegisterDstUsers:
                // 周辺ユーザ全員に HelloPacket を送ってNATに穴を開ける
                for (UserInfo peer : peripheralUsers) {
                    sendTo(sendData, peer);
                }
                Log.d(TAG, "NATRegisterDstUsers 完了: " + peripheralUsers.size() + "件");
                break;

            case NATRegisterSrcUser:
                // 自分を検索してきたユーザ1人にだけ送る
                sendTo(sendData, srcUser);
                Log.d(TAG, "NATRegisterSrcUser 完了");
                break;

            default:
                break;
        }
    }

    /**
     * [変更] 旧実装で NATRegisterDstUsers / NATRegisterSrcUser の両 case に
     * 同じ if-else 送信ロジックが重複していた → このヘルパーメソッドに共通化
     *
     * 同NAT内の端末にはプライベートIP・Portを、異NAT間にはパブリックIP・Portを使用する。
     */
    private void sendTo(byte[] sendData, UserInfo target) {
        try {
            DatagramPacket sendPacket;
            if (myPublicIP.equals(target.getPublicIP())) {
                // 同NAT内 → プライベートアドレスで通信
                sendPacket = new DatagramPacket(sendData, sendData.length,
                        InetAddress.getByName(target.getPrivateIP()), target.getPrivatePort());
            } else {
                // 異NAT間 → パブリックアドレスでNATホールパンチング
                sendPacket = new DatagramPacket(sendData, sendData.length,
                        InetAddress.getByName(target.getPublicIP()), target.getPublicPort());
            }
            socket.send(sendPacket);
        } catch (Exception e) {
            Log.d(TAG, "送信エラー: " + e);
        }
    }
}
