package master_server;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 交差点ID → エッジサーバ情報のテーブルを管理するクラス。
 *
 * 設定ファイル（edge_servers.csv）から初期化する。
 * フォーマット（1行1エントリ）:
 *   intersectionId,ip,port
 *   例: 35.6580_139.7016,192.168.1.10,55600
 */
public class EdgeServerRegistry {

    private final Map<String, EdgeServerInfo> table = new HashMap<>();

    /**
     * CSVファイルからレジストリを初期化する。
     * @param csvPath edge_servers.csv のファイルパス
     */
    public void loadFromCsv(String csvPath) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(",");
                if (parts.length < 3) continue;
                String intersectionId = parts[0].trim();
                String ip             = parts[1].trim();
                int    port           = Integer.parseInt(parts[2].trim());
                table.put(intersectionId, new EdgeServerInfo(intersectionId, ip, port));
            }
        }
        System.out.println("EdgeServerRegistry: " + table.size() + "件 ロード完了");
    }

    /**
     * 交差点IDリストに対応するエッジサーバ情報を返す。
     * 登録されていない交差点IDは無視する。
     */
    public List<EdgeServerInfo> resolve(List<String> intersectionIds) {
        List<EdgeServerInfo> result = new ArrayList<>();
        for (String id : intersectionIds) {
            EdgeServerInfo info = table.get(id);
            if (info != null) {
                result.add(info);
            } else {
                System.out.println("EdgeServerRegistry: 未登録の交差点ID=" + id);
            }
        }
        return result;
    }

    public int size() { return table.size(); }
}
