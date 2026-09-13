package client;

/**
 * Created by MF17037 on 2017/12/15.
 */

import java.net.InetAddress;
import java.util.ArrayList;


public interface IReceive {
    void onGetPeripheralUser(ArrayList<UserInfo> peripheralUserInfos);
    void onDoUDPHolePunching(UserInfo srcUserInfo);
    void onDelayExperiment(int locationCount, InetAddress endPointIP, int endPointPort);
    void onGetPeerMsg();
}
