package client;


/**
 * Created by pc on 2017/11/23.
 */

// 11/15日の成功時の変更履歴18:07らへん

public class DummyLocation extends Thread{
    private Location dummyLocation = new Location();
    //static final double MAX_LAT = 35.959819;//ベース地点から一キロ(+0.009013)
    //static final double MAX_LONG = 139.661819;//ベース地点から一キロ(+ 0.010966)
    //static final double MAX_LAT = 35.9553045743;//ベース地点から500M
    //static  final double MAX_LONG =139.657041191;//ベース地点から５００M
    //static final double BASED_LATITUDE = 35.950813;//芝浦工業大学入り口
    //static final double BASED_LONGITUDE =  139.651558;//芝浦工業大学入り口
    //static final double MAX_LAT = 35.418859;//自宅前から1キロ
    //static final double MAX_LONG = 139.599615;//自宅前から1キロ
    static final double MAX_LAT = 35.40950; //テスト用の自宅とほぼ同じ場所
    static final double MAX_LONG = 139.588650; //テスト用の自宅とほぼ同じ場所
    //static final double BASED_LATITUDE = 35.409846; //自宅前
    //static final double BASED_LONGITUDE = 139.588649;//自宅前
    static final double BASED_LATITUDE = 35.95189;
    static final double BASED_LONGITUDE = 139.65447;

    static final int REFRESH_INTERVAL = 1;//位置情報を更新する間隔 秒単位

    /*
	double latHigh = BASED_LATITUDE + 0.002; //東に180.2m
	double latLow = BASED_LATITUDE - 0.002; //西に180.2m
	double longHigh = BASED_LONGITUDE + 0.002; //北に222.6
	double longLow = BASED_LONGITUDE - 0.002; //南に22.6
	*/

    /*
	double latHigh = BASED_LATITUDE + 0; //東に180.2m
	double latLow = BASED_LATITUDE - 0; //西に180.2m
	double longHigh = BASED_LONGITUDE + 0; //北に222.6
	double longLow = BASED_LONGITUDE - 0; //南に22.6
	*/

	double latHigh = BASED_LATITUDE + 0.000001; //東に180.2m
	double latLow = BASED_LATITUDE - 0.000001; //西に180.2m
	double longHigh = BASED_LONGITUDE + 0.000001; //北に222.6
	double longLow = BASED_LONGITUDE - 0.000001; //南に22.6

	double startLat;
	double startLong;
	double currentLat;
	double currentLong;
    private Boolean dummyLocationUpdateStop = false;
    private DummyLocationListener dummyLocationListener;



    DummyLocation(DummyLocationListener dummyLocationListener){

        this.dummyLocationListener = dummyLocationListener;
        startLat = getRandomLocation(latLow, latHigh);
        startLong = getRandomLocation(longLow, longHigh);
        currentLat = startLat;
        currentLong = startLong;
    }




    public void run() {

    	double way = -1.0;
    	double speed = -1.0;


    	//double way = 0;
    	//double speed = 0;

        while(dummyLocationUpdateStop.equals(false)){


            /* SET random value */
            if(way == -1.0 && speed == -1.0) {
            	way = getRandom(0.0, 359.0);
    			//speed = getRandom(0.0, 60.0);
            	speed = getRandom(0.0, 1.0);
            	System.out.println(speed);
            }

            /* SET next point with speed and way */
    		double tmpLat = getCalcLatitude(currentLat, way, speed);
    		currentLong = getCalcLongitude(currentLat, currentLong, way, speed);
    		currentLat = tmpLat;


    		/* in Area */
			if (currentLat > latHigh) {
				currentLat = latLow + (currentLat - latHigh);
				System.out.println("HIT.1 (Latitude High)");
			}
			if (currentLong > longHigh) {
				currentLong = longLow + (currentLong - longHigh);
				System.out.println("HIT.2 (Longitude High)");
			}
			if (currentLat < latLow) {
				currentLat = latHigh + (currentLat - latLow);
				System.out.println("HIT.3 (Latitude Low)");
			}
			if (currentLong < longLow) {
				currentLong = longHigh + (currentLong - longLow);
				System.out.println("HIT.4 (Longitude Low)");
			}
			dummyLocation.setLatitude(currentLat);
			dummyLocation.setLongitude(currentLong);
            dummyLocationListener.onDummyLocationChanged(dummyLocation);
            //System.out.println("LocationChangedに値を渡した");
            sleepDummyLocationUpdate();

			way = -1.0;
			speed = -1.0;


        	// way = 0;
             //speed = 0;

        }
        //dummyLocationFinishListener.onDummyLocationFinishListener();
    }



    /**
     * ダミー位置情報更新処理を停止する
     */
    private void sleepDummyLocationUpdate() {
        try {
            Thread.sleep(REFRESH_INTERVAL*1000);
        } catch (InterruptedException e) {
            System.out.println(e);
        }
    }

    public void setDummyLocationUpdateStop(){
        dummyLocationUpdateStop = true;
    }


    public double getRandomLocation(double max,double min){
    	return Math.random() * (max - min) + min;
    }

    public double getRandom(double min, double max) {
    	return Math.floor( Math.random() * (max - min + 1) ) + min;
    }


    public double getCalcLatitude(double latitude, double way, double speed) {
    	double travelDistance = (speed / 3.6) * REFRESH_INTERVAL;
    	return latitude + travelDistance * 0.00090133729745762 * Math.cos(way * Math.PI / 180);
    }


    public double getCalcLongitude(double latitude, double longitude, double way, double speed) {
    	double travelDistance = (speed / 3.6) * REFRESH_INTERVAL;
    	double convertedLng = 36000 / (2 * Math.PI * 6378137 * Math.cos(latitude * Math.PI / 180));
    	return longitude + travelDistance * convertedLng * Math.sin(way * Math.PI / 180);
    }
}