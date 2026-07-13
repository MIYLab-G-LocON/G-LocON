package master_server;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * MasterServerで扱うJSONの解析・生成を担当するクラス。
 *
 * 受信: INTERSECTION_QUERY（交差点IDリスト）
 * 送信: EDGE_SERVER_LIST（交差点ID + エッジサーバIP/Port の一覧）
 */
public class ProcessJSONObject {

    private JSONObject jsonObject;

    public ProcessJSONObject() {}

    public ProcessJSONObject(JSONObject jsonObject) {
        this.jsonObject = jsonObject;
    }

    public String getProcessType() {
        try {
            return jsonObject.getString("processType");
        } catch (JSONException e) {
            e.printStackTrace();
            return "";
        }
    }

    /** INTERSECTION_QUERY に含まれる交差点IDリストを取得する */
    public List<String> getIntersectionIds() {
        List<String> ids = new ArrayList<>();
        try {
            JSONArray arr = jsonObject.getJSONArray("intersectionIds");
            for (int i = 0; i < arr.length(); i++) {
                ids.add(arr.getString(i));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return ids;
    }

    /** エッジサーバ一覧をJSONに変換して返す（EDGE_SERVER_LIST用） */
    public JSONObject getEdgeServerList(List<EdgeServerInfo> servers) {
        JSONObject json = new JSONObject();
        JSONArray arr = new JSONArray();
        for (EdgeServerInfo s : servers) {
            JSONObject entry = new JSONObject();
            try {
                entry.put("intersectionId", s.getIntersectionId());
                entry.put("ip",   s.getIp());
                entry.put("port", s.getPort());
                arr.put(entry);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        try {
            json.put("processType", "EDGE_SERVER_LIST");
            json.put("edgeServers", arr);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return json;
    }
}
