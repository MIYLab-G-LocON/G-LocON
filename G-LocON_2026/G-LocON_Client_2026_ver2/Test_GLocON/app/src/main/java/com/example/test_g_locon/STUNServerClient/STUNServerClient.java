package com.example.test_g_locon.STUNServerClient;

// [変更] AsyncTask → ExecutorService に置き換え
import java.net.DatagramSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * STUNサーバとの通信シーケンスを管理するファサードクラス。
 * Sender → Receiver の順に起動し、グローバルIP・Portを取得してコールバックする。
 *
 * [変更] AsyncTask.executeOnExecutor() → ExecutorService.execute() に統一
 */
public class STUNServerClient implements ISTUNServerClientSender, ISTUNServerClientReceiver {

    private final ISTUNServerClient callback;
    private final DatagramSocket socket;

    // [変更] AsyncTask の代わりに ExecutorService でスレッドを管理
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public STUNServerClient(DatagramSocket socket, ISTUNServerClient callback) {
        this.socket = socket;
        this.callback = callback;
    }

    /** STUNServerClientSender を起動して通信シーケンスを開始する */
    public void stunServerClientStart() {
        executor.execute(new STUNServerClientSender(socket, this));
    }

    /** Sender の Hello 送信完了後に呼ばれる。Receiver を起動する */
    @Override
    public void onSendFinishMsgToStun() {
        executor.execute(new STUNServerClientReceiver(socket, this));
    }

    /** Receiver がグローバルIP・Portを受信後に呼ばれる。上位コールバックへ委譲 */
    @Override
    public void onReceiveMsgFromStun(String addr, int port) {
        callback.onGetGlobalIP_Port(addr, port);
    }
}
