package signaling_server;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.ArrayList;
import java.util.Objects;

import org.json.JSONObject;

public class SignalingServerReceive extends Thread {

	//メンバ変数
	private DatagramSocket socket;
	private ArrayList<UserInfo> userInfoList;

	//コンストラクタ
	public SignalingServerReceive() { super(); }

	//コンストラクタ
	public SignalingServerReceive(DatagramSocket socket, ArrayList<UserInfo> userInfoList) {
		this.socket = socket;
		this.userInfoList = userInfoList;
	}

	@Override
	public void run() {
		//受信メッセージの定義
		final String REGISTER = "REGISTER";
		final String UPDATE = "UPDATE";
		final String SEARCH = "SEARCH";
		final String DELETE = "DELETE";

		while (true) {
			// receive Data
			// [修正 2026/10] 1024バイトではSEARCH結果（ユーザ6人程度）が収まらないため，UDPの最大ペイロード長にする
			DatagramPacket receivePacket = new DatagramPacket(new byte[65507], 65507);
			try {
				socket.receive(receivePacket);

				String result = new String(receivePacket.getData(), 0, receivePacket.getLength());

				JSONObject jsonObject = new JSONObject(result);
				ProcessJSONObject processJSONObject = new ProcessJSONObject(jsonObject);
				String processType = processJSONObject.getProcessType();

				if (processType.equals(REGISTER)) { //登録
					if (onRegister(processJSONObject.getUserInfo())) { // [修正 2026/10] 不正な登録は無視する
						System.out.println(processJSONObject.getUserInfo().getPeerId() + "の端末情報を登録");
						showInfo(processJSONObject.getUserInfo(), REGISTER);
					}
				}

				else if (processType.equals(UPDATE)) { //更新
					onUpdate(processJSONObject.getUserInfo());
					System.out.println(processJSONObject.getUserInfo().getPeerId() + "の端末情報の更新");
					showInfo(processJSONObject.getUserInfo(), UPDATE);

				} else if (processType.equals(SEARCH)) { //検索
					onSearch(processJSONObject.getUserInfo(), processJSONObject.getSearchDistance());
					System.out.println(processJSONObject.getUserInfo().getPeerId() + "からの検索要求を実行");

				} else if (processType.equals(DELETE)) { //削除
					onDelete(processJSONObject.getUserInfo());
					System.out.println(processJSONObject.getUserInfo().getPeerId() + "の端末情報を削除");

				}
			} catch (Exception e) {
				// [修正 2026/10] 例外を握りつぶすと原因が分からないため，ログを出して受信を続ける
				System.out.println("SignalingServerReceive:受信データの処理に失敗：" + e);
				e.printStackTrace();
			}
		}
	}

	/**
	 * ユーザ情報登録
	 * @param userInfo
	 */
	public boolean onRegister(UserInfo userInfo) {
		// [修正 2026/10] peerIDが無い登録は識別できないため，ログを出して無視する
		if (userInfo.getPeerId() == null || userInfo.getPeerId().isEmpty()) {
			System.out.println("REGISTER:peerIDが無いため登録しない（publicIP:" + userInfo.getPublicIP()
					+ " publicPort:" + userInfo.getPublicPort() + "）");
			return false;
		}
		// [修正 2026/10] アプリ再起動等で同じpeerIDが再登録された場合，古い情報を置き換える
		// （以前は追加し続けたため，古いエントリがSEARCH結果に残っていた）
		for (int i = 0; i < userInfoList.size(); i++) {
			if (userInfo.getPeerId().equals(userInfoList.get(i).getPeerId())) {
				userInfoList.set(i, userInfo);
				System.out.println("REGISTER:同じpeerIDの情報を置き換え．現在のuserInfosのサイズ"+userInfoList.size());
				return true;
			}
		}
		userInfoList.add(userInfo);
		System.out.println("REGISTER:現在のuserInfosのサイズ"+userInfoList.size());
		//System.out.println(userInfo);
		return true;
	}

	/**
	 * [修正 2026/10] 2つのユーザ情報のアドレス（public/privateのIPとポート）が一致するか
	 * privateIP等がnullでもNullPointerExceptionにならないようObjects.equalsで比較する
	 */
	private boolean isSameAddress(UserInfo a, UserInfo b) {
		return Objects.equals(a.getPublicIP(), b.getPublicIP()) && a.getPublicPort() == b.getPublicPort()
				&& Objects.equals(a.getPrivateIP(), b.getPrivateIP()) && a.getPrivatePort() == b.getPrivatePort();
	}

	/**
	 * ユーザ情報を更新する
	 * @param userInfo //ユーザ情報
	 */
	public void onUpdate(UserInfo userInfo) {
		/*
		if(userInfoList.contains(userInfo)) { //含まれている場合
			int index = userInfoList.indexOf(userInfo); //要素番号抽出
			userInfoList.set(index, userInfo);
			//System.out.println(index);
			System.out.println("更新しました");
			//System.out.println(userInfo);
		}
		*/
	       for(int i = 0; i < userInfoList.size(); i++){
	            // [修正 2026/10] 以前はuserInfo自身とpublicIPを比較しており常にtrueだった．リスト側の要素と比較する
	            if(isSameAddress(userInfoList.get(i), userInfo)) {
	                userInfoList.set(i, userInfo);
	                break;
	            }
	        }

	}

	/**
	 * 1.指定されたユーザの地点からか検索半径の円に存在するユーザ情報を検索し，該当ユーザを検索リストに格納
	 * 2.検索リストに格納されたユーザにNAT通過のために接続先ユーザのIPとポートを送信し，"ping"のような空データを送信させる
	 * 3.送信元のユーザに検索結果を返却する
	 * @param userInfo 検索元のユーザ情報
	 * @param searchDistance 検索半径
	 */
	public void onSearch(UserInfo userInfo, double searchDistance) {
		ArrayList<UserInfo> searchResultUserList = new ArrayList<>();

		//System.out.println(userInfo.getPeerId());
		//String myPeerID = userInfo.getPeerId();
		for(UserInfo item : userInfoList) {
			HubenyDistance hubenyDistance = new HubenyDistance();

			//距離算出
			double distance = hubenyDistance.calcDistance(userInfo.getLatitude(), userInfo.getLongitude(),
					item.getLatitude(), item.getLongitude());
			//itemの中身見る用
			/*
			System.out.println();
			System.out.println("******************************");
			System.out.println("public IP：" + item.getPublicIP());
			System.out.println("public Port：" + item.getPublicPort());
			System.out.println("private IP：" + item.getPrivateIP());
			System.out.println("private Port：" + item.getPrivatePort());
			System.out.println("Latitude：" + item.getLatitude());
			System.out.println("Longitude：" + item.getLongitude());
			System.out.println("PeerID：" + item.getPeerId());
			//System.out.println("Speed"+item.getSpeed());
			System.out.println("******************************");
			System.out.println();
			*/

			//System.out.println(userInfo.getPeerId()+"の緯度は"+userInfo.getLatitude()+"  経度は"+userInfo.getLongitude());
			//System.out.println(item.getPeerId()+"の緯度は"+item.getLatitude()+"  経度は"+item.getLongitude());
			//System.out.println(userInfo.getPeerId()+"と"+item.getPeerId()+"の距離は"+distance+"m");
			//範囲内にいるユーザを検出
			if(distance <= searchDistance) {
				// if(item.getPeerId() == myPeerId)に置き換える予定
				// 自分自身を検索結果から除外
				// [修正 2026/10] privateIPがnullでも落ちないよう比較をnull安全にし，同じpeerIDも自分自身として除外する
				if (isSameAddress(item, userInfo)
						|| (userInfo.getPeerId() != null && userInfo.getPeerId().equals(item.getPeerId()))) {
				}else {

					System.out.println(userInfo.getPeerId()+"の緯度は"+userInfo.getLatitude()+"  経度は"+userInfo.getLongitude());
					System.out.println(item.getPeerId()+"の緯度は"+item.getLatitude()+"  経度は"+item.getLongitude());
					System.out.println(userInfo.getPeerId()+"と"+item.getPeerId()+"の距離は"+distance+"m");
					//System.out.println("端末間の距離は" + distance + "m");
					searchResultUserList.add(item);
				}

			}
		}

		//System.out.println("MainActivity_onSearch:検索結果の個数:"+searchResult.size());

		//つぎにP2P通信を行うためにNATに検索元ユーザの情報を登録させる必要があるのでこれを行うよう促す
		//ここでSendクラスを呼び呼び出し元のユーザに検索結果を返す．引数はpublicIP,publicPort,searchResult

		SignalingServerSend UDPHolePunchingOtherUsers = new SignalingServerSend(socket, userInfo, searchResultUserList, "srcAddrPortRegisterToNat");
		UDPHolePunchingOtherUsers.start();
		System.out.println("");
		System.out.println(userInfo.getPeerId()+"のsrcAddrPortRegisterToNatをスタートしました");
		SignalingServerSend reply = new SignalingServerSend(socket, userInfo, searchResultUserList, "replyFromMainActivity");
		reply.start();
		System.out.println(userInfo.getPeerId()+"のreplyFromMainActivityをスタートしました");
		System.out.println("");
	}

	/**
	 * 指定されたユーザ情報を破棄する
	 * @param userInfo//ユーザ情報
	 */
	public void onDelete(UserInfo userInfo) {
		/*
		if(userInfoList.contains(userInfo)) { //含まれている場合
			int index = userInfoList.indexOf(userInfo); //要素番号抽出
			userInfoList.remove(index);
		}
		*/
        for(int i = 0; i < userInfoList.size(); i++){
            if(isSameAddress(userInfoList.get(i), userInfo)) { // [修正 2026/10] null安全な比較に変更
                userInfoList.remove(i);
                break;
            }
        }
	}


	/**
	 * 情報を見たいときに使用する
	 * @param userInfo//ユーザ情報
	 */
	public void showInfo(UserInfo userInfo, String type) {
		System.out.println();
		System.out.println("--------- " + type + " -----------");
		System.out.println("public IP：" + userInfo.getPublicIP());
		System.out.println("public Port：" + userInfo.getPublicPort());
		System.out.println("private IP：" + userInfo.getPrivateIP());
		System.out.println("private Port：" + userInfo.getPrivatePort());
		System.out.println("Latitude：" + userInfo.getLatitude());
		System.out.println("Longitude：" + userInfo.getLongitude());
		System.out.println("PeerID：" + userInfo.getPeerId());
		//System.out.println("speed："+userInfo.getSpeed());
		System.out.println("---------------------------");
		System.out.println();
	}

}
