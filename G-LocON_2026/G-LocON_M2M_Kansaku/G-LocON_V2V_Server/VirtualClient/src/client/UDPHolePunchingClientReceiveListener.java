package client;

/**
 * Created by MF17037 on 2017/12/04.
 */

public interface UDPHolePunchingClientReceiveListener {
    void onReceiveMsgFromStun(String addr,int port);
}
