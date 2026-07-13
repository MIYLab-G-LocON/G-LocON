package client;


/**
*
* @author MF17037
*
*/
public class Location {
private double latitude;
private double longitude;

	Location(){

	}

	Location(double latitude, double longitude){
		this.latitude = latitude;
		this.longitude = longitude;
	}

	public void setLatitude(double latitude) {
		this.latitude = latitude;
	}

	public void setLongitude(double longitude) {
		this.longitude = longitude;
	}

	public double getLatitude() {
		return latitude;
	}

	public double getLongitude() {
		return longitude;
	}
}