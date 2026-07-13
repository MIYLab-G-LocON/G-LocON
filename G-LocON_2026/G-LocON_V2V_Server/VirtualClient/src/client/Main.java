package client;

//11/15日の成功時の変更履歴18:07らへん
public class Main implements IP2P,DummyLocationListener{

	final static int NAT_TRAVEL_OK = 1;
    private int natTravel = 0;
    final private static int LOCATION_UPDATE_COUNT = 5; //位置情報を5回取得したらNiftyに現在位置を登録．また，周辺ユーザ検索も実施
    final private static int SYSTEM_END = 800;//システムを終了させる
    private double seachRangeNifty =100.0; //km単位(もともと100だったけど500に変更1113)
    private int getLocationUpdateCount = 4;//位置情報のカウント回数を記録 5回で初期化
    private int LocationCounter = 0;
    private DummyLocation dummyLocation;
    private static String peerID = "555";
    //private static double speed;


    private static P2P p2p;

	public static void main(String args[]){
		Main main  = new Main();
		//ここにforループを記述したら複数の仮想端末生成できそう？(11/13)
		p2p = new P2P(main);
        p2p.udpHolePunchingStart();
	}

    /**
     * 呼ばれた時点で自身の外部IPと外部ポートを取得
     * ピアやシグナリグサーバからのデータ受信状態に入る
     */
    @Override
    public void onGetExternalIPAddressAndPort(){
        //System.out.println("MAIN");
        System.out.println("onGetExternalIPAddressAndPort");
        natTravel = NAT_TRAVEL_OK;
        p2p.receive();

        dummyLocation = new DummyLocation(this);
        dummyLocation.start();
    }

    @Override
    public void onGetPeerMsg(){
    }

    @Override
    public void onDummyLocationChanged(Location geo) {
        //geo.setLongitude(35.0);
        //geo.setLongitude(35.0);

    	//p2p.setSpeed(geo);
    	//p2p.setLocation(geo);

        if(natTravel == NAT_TRAVEL_OK) {
            LocationCounter++;
            if(LocationCounter == SYSTEM_END) {
            	natTravel = 1111;
            	p2p.sendDataToSignalingServer(geo.getLatitude(), geo.getLongitude(), "DELETE");
            	try{

            		Thread.sleep(3000); //3000ミリ秒Sleepする

            		}catch(InterruptedException e){}
            	System.exit(1);
            }

            getLocationUpdateCount++;//位置情報更新回数を+1する.....................................................................................................................................................................................................................................
            if (getLocationUpdateCount == LOCATION_UPDATE_COUNT) {//位置情報が5回更新されたタイミングでNiftyから周辺端末のリストを取得し，自身の現在位置情報も送信，その後，ほかのpeerに自身の位置情報を送信(最初は強制的に呼ぶ)
                getLocationUpdateCount = 0;//カウント回数を初期化
                //////////////////////////////////////PeerListの更新//////////////////////////////
                p2p.sendDataToSignalingServer(geo.getLatitude(), geo.getLongitude(), "UPDATE");
                p2p.sendDataToSignalingServer(geo.getLatitude(), geo.getLongitude(), seachRangeNifty, "SEARCH");

               // p2p.send("TEST", "sendMsg");
                //p2p.sendLocation(geo.getLatitude(),geo.getLongitude(),"sendLocation");
                //System.out.println("trueです");
                p2p.send("SendLocation", "SendLocation",peerID,geo.getLatitude(),geo.getLongitude());
            } else {
            	//System.out.println("elseです");
                //p2p.send("TEST", "sendMsg");
            	 p2p.send("SendLocation", "SendLocation",peerID,geo.getLatitude(),geo.getLongitude());
            }
        }
    }

}