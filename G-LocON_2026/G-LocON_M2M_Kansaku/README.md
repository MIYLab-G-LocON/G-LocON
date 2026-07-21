# G-LocON V2V システム 設計・評価方針 設計書
2026年7月13日

---

## 1. システム概要

本システムはG-LocON（Geolocation Oriented Network）を基盤として，車車間通信（V2V）に応用したものである．従来の自車位置中心の周辺端末検索に代わり，**進行先の交差点を中心としたP2Pネットワーク構築**を実現する．

### 1.1 基本アーキテクチャ

| ノード | 役割 |
|--------|------|
| 自車両（Androidクライアント） | 走行しながらETAを計算し，交差点V2Vグループへ参加・離脱 |
| エッジサーバ（EdgeServer） | 交差点ごとに1プロセス起動し，V2Vグループを管理 |
| マスタサーバ（MasterServer） | 交差点IDとエッジサーバアドレスの対応テーブルを管理 |

### 1.2 通信シーケンス

1. 目的地を設定し，**OSRM公開API**でルートを取得（ルート上の交差点座標列を得る）
2. マスタサーバに交差点IDリストを送信し，各エッジサーバのIP/Portを受信
3. 走行中，各交差点へのETA（到達予測時刻）を常時計算
4. **ETA < τ**（閾値，例: 30秒）になったらエッジサーバへJOIN要求を送信
5. JOIN承認後，グループ内の他車両とG-LocONのNATホールパンチングでP2P通信を確立
6. 交差点中心からの距離 **d ≥ δ かつ Δd > 0**（離れている）になったらLEAVE通知を送信
7. 次の交差点に対して4〜6を繰り返す（複数交差点への同時参加もあり得る）

---

## 2. フォルダ・パッケージ構成

### 2.1 リポジトリ構成

```
G-LocON_2026/
├── G-LocON_Client_2026/    旧改修版（OSM導入・コード整理済）
├── G-LocON_Server_2026/    旧改修版
├── G-LocON_V2V_Client/     ★ V2V応用版クライアント（本研究）
└── G-LocON_V2V_Server/     ★ V2Vサーバ群（本研究）
```

### 2.2 G-LocON_V2V_Client パッケージ構成

| パッケージ | 状態 | 役割 |
|-----------|------|------|
| `P2P/` | G-LocONから流用（変更最小限） | P2P通信・NATホールパンチング・シグナリング |
| `STUNServerClient/` | G-LocONから流用（変更なし） | グローバルIP/Port取得 |
| `location/` | G-LocONから流用（変更なし） | GPS位置情報取得 |
| `controller/` | 拡張 | AppController・IAppControllerにV2Vロジックを追加 |
| `main/` | 拡張 | MainActivity（目的地UI追加）・UserInfo（bearingフィールド追加） |
| `map/` | 拡張 | MapManager（ルート・交差点マーカー表示追加） |
| `navigation/` | **★新規追加** | OSRM通信・ETA計算・IntersectionManager |
| `intersection/` | **★新規追加** | EdgeServerClient・JOIN/LEAVE通信 |
| `carla/` | **★新規追加（後期）** | CARLAシミュレーション対応（モードA/B） |

### 2.3 G-LocON_V2V_Server モジュール構成

| モジュール | 状態 | 役割 | ポート |
|-----------|------|------|--------|
| `STUNServer/` | G-LocONから流用（変更なし） | グローバルIP/Port取得 | 55554 |
| `SignalingServer/` | 残置（V2Vでは不使用） | 旧G-LocONの位置ベース検索 | 55555 |
| `MasterServer/` | **★新規** | 交差点ID → エッジサーバAddr配布 | 55556 |
| `EdgeServer/` | **★新規** | 交差点V2Vグループ管理（交差点ごとに1プロセス） | 556XX |
| `VirtualClient/` | 拡張（動作確認用） | V2Vシナリオのシミュレーション | - |
| `CARLABridge/` | **★新規（後期）** | CARLAとAndroidを繋ぐPythonブリッジ | - |

---

## 3. 各モジュール設計方針

### 3.1 EdgeServer（新規）

交差点ごとに1プロセスとして起動する．SignalingServerのV2V特化版と位置付ける．

| クラス | 役割 |
|--------|------|
| `StartUp.java` | 起動引数で交差点ID・ポート番号を受取りEdgeServerReceiveを起動 |
| `EdgeServerReceive.java` | JOIN/LEAVE/SEARCHの3種UDPを受信し処理を振り分け |
| `EdgeServerSend.java` | グループメンバー一覧の返送・NATホールパンチング通知（NAT_REGISTER/REPLY_RESULT） |
| `V2VGroupRegistry.java` | CopyOnWriteArrayListでグループメンバーをスレッドセーフに管理 |
| `UserInfo.java` | publicIP/Port, privateIP/Port, lat, lng, peerId, **eta**（追加） |
| `ProcessJSONObject.java` | JOIN/LEAVE/SEARCHのJSON解析・グループメンバー一覧のJSON生成 |

### 3.2 MasterServer（新規）

交差点ID → エッジサーバアドレスのテーブルを管理し，クライアントに配布する．

| クラス | 役割 |
|--------|------|
| `StartUp.java` | ポート55556でUDP待受，EdgeServerRegistryを設定ファイルから初期化 |
| `MasterServerReceive.java` | クライアントから交差点IDリストを受信しRegistryで解決 |
| `MasterServerSend.java` | 解決結果（交差点ID + エッジサーバIP/Port）をJSONで返送 |
| `EdgeServerRegistry.java` | `Map<String, EdgeServerInfo>` で交差点IDとエッジサーバを管理 |
| `EdgeServerInfo.java` | intersectionId, ip, port のデータモデル |
| `ProcessJSONObject.java` | 交差点IDリストのJSON解析・エッジサーバアドレスリストのJSON生成 |

### 3.3 navigation/ パッケージ（新規・Client）

| クラス | 役割 |
|--------|------|
| `Intersection.java` | 交差点データモデル（OSM nodeID, lat/lng, エッジサーバAddr, ETA, 距離d, 参加状態フラグ） |
| `OsrmRouteClient.java` | OSRM公開APIへHTTPリクエスト送信，ルート上の交差点座標列を取得 |
| `IntersectionManager.java` | 交差点リスト管理，ETA計算（距離÷速度），JOIN判定（ETA<τ），LEAVE判定（d≥δ かつ Δd>0） |
| `MasterServerClient.java` | マスタサーバへ交差点IDリストを送信しエッジサーバAddrを受信 |

### 3.4 intersection/ パッケージ（新規・Client）

| クラス | 役割 |
|--------|------|
| `EdgeServerClient.java` | エッジサーバへJOIN/LEAVEをUDP送信（Runnable + ExecutorService構造） |
| `EdgeServerJSONObject.java` | JOIN/LEAVE用JSONの構築（processType: JOIN/LEAVE, intersectionId, eta含む） |

---

## 4. 通信プロトコル設計

### 4.1 processType 一覧

| processType | 送信元 | 送信先 | 内容 |
|-------------|--------|--------|------|
| REGISTER | Client | STUNServer | グローバルIP/Port取得 |
| INTERSECTION_QUERY | Client | MasterServer | 交差点IDリストを送りエッジサーバを問い合わせ |
| EDGE_SERVER_LIST | MasterServer | Client | 交差点ID ↔ エッジサーバAddr一覧を返送 |
| JOIN | Client | EdgeServer | 交差点V2Vグループへの参加要求（intersectionId, eta含む） |
| LEAVE | Client | EdgeServer | 交差点V2Vグループからの離脱通知 |
| SEARCH | Client | EdgeServer | グループメンバー一覧の問い合わせ |
| getPeripheralUserInfoList | EdgeServer | Client | グループメンバー一覧を返送 |
| doUDPHolePunching | EdgeServer | Client（他車両） | NATホールパンチング通知 |
| NATRegisterDstUsers | Client | 他車両 | NATに穴を開けるパケット |
| SendLocation | Client | 他車両 | P2P直接通信（位置情報送信） |
| CARLA_LOCATION | CARLABridge | Client | CARLA車両の位置・速度・進行方向 |
| VEHICLE_COMMAND | Client | CARLABridge | CARLA車両への行動指令（減速・復帰など） |

### 4.2 ポート設計

| サーバ | ポート | 役割 |
|--------|--------|------|
| STUNServer | 55554 | グローバルIP/Port取得（変更なし） |
| SignalingServer | 55555 | V2Vでは不使用（残置） |
| MasterServer | 55556 | 交差点ID → エッジサーバAddr配布 |
| EdgeServer（交差点A） | 55600 | 交差点AのV2Vグループ管理 |
| EdgeServer（交差点B） | 55601 | 交差点BのV2Vグループ管理 |
| EdgeServer（交差点N） | 556XX | 交差点ごとに1ポートずつ割り当て |

---

## 5. 実装順序とgit記録方針

各セクション完了後に1コミットを記録する．

| セクション | 内容 | コミットメッセージ |
|-----------|------|------------------|
| 1 | EdgeServer新規作成 | `add EdgeServer module for intersection V2V group management` |
| 2 | MasterServer新規作成 | `add MasterServer module for edge server address distribution` |
| 3 | Client: navigation/ 新規作成 | `add navigation package: OSRM route client and intersection manager` |
| 4 | Client: intersection/ 新規作成 | `add intersection package: edge server JOIN/LEAVE client` |
| 5 | AppController変更（V2Vロジック統合） | `extend AppController with V2V join/leave logic` |
| 6 | MapManager・MainActivity変更（UI） | `extend UI: route display and intersection group status` |
| 7 | carla/ パッケージ（モードA: 3端末） | `add CARLA bridge mode A: per-vehicle Android mapping` |
| 8 | CARLAMultiVehicleSimulator（モードB: 1端末） | `add CARLA bridge mode B: single device multi-vehicle simulation` |
| 9 | CARLABridge Pythonブリッジ | `add Python CARLA bridge for bidirectional simulation control` |

---

## 6. CARLAシミュレーション設計

### 6.1 実験モード

| モード | Android台数 | P2P通信 | 主な評価対象 |
|--------|------------|---------|-------------|
| モードA（実機分散型） | 3台（各1台=1車両） | 実際のUDP通信 | 通信遅延・PDR・グループ参加離脱の正確性 |
| モードB（単端末集約型） | 1台（全車両を仮想処理） | メモリ内疑似通信 | グループ参加離脱の正確性・交通への情報共有効果 |

### 6.2 双方向通信フロー

- **CARLA → Android**: 各車両の位置・速度・進行方向を `CARLA_LOCATION` としてUDP送信（1秒ごと）
- **Android → CARLA**: V2V情報共有に基づき生成した `VEHICLE_COMMAND` をCARLAへUDP返信
- CARLAはTrafficManager APIで `VEHICLE_COMMAND` を受信し車速・ブレーキを直接制御

### 6.3 VEHICLE_COMMAND の種類

| command | 内容 | トリガー |
|---------|------|---------|
| DECELERATE | 目標速度まで減速 | 前方急停止・渋滞情報の受信 |
| RESUME | 通常速度に復帰 | 危険状況の解消 |
| HOLD_SPEED | 現在速度を維持 | 前方渋滞継続中 |

---

## 7. 評価設計

### 7.1 評価軸①：交通への影響（サービスの目的）

V2Vあり vs V2Vなしを比較し，交通安全・効率への貢献を評価する．測定環境はCARLAシミュレーション．

#### 7.1.1 安全性指標

| 指標 | 定義 | 測定方法 |
|------|------|---------|
| TTC（Time To Collision） | 現在の速度差・車間距離から算出する衝突予測時間 | CARLA上の車間距離・相対速度から算出 |
| PET（Post Encroachment Time） | 同一交差点空間を2台が通過した時間差 | 交差点通過タイムスタンプから算出 |
| 急制動発生回数 | 閾値以上の減速度（例: -3m/s²）が発生した回数 | CARLAの加速度データから検出 |
| ヒヤリハット率 | TTC < 閾値（例: 3秒）となったイベント数 / 全交差点通過数 | TTC算出結果から集計 |
| 急停止連鎖台数 | 急停止に連鎖して二次停止した車両数 | V2Vあり vs なしで比較 |

#### 7.1.2 交通効率指標

| 指標 | 定義 |
|------|------|
| 交差点スループット | 単位時間に交差点を通過した車両台数（台/分） |
| 渋滞長 | 停止・低速車両が連続する区間の長さ（m） |
| 渋滞解消時間 | 渋滞発生から全車両が通常速度に戻るまでの時間（秒） |
| 交通波の伝播速度 | 渋滞が上流へ伝播するスピード（V2Vで抑制できるか） |
| 平均停止時間 | 走行中に速度がゼロになった累計時間の平均 |
| 燃料消費量（推定） | CARLAの速度プロファイルから急加減速を抽出し推定 |

---

### 7.2 評価軸②：通信性能（ネットワーク研究室の観点）

本システム vs 通常G-LocON vs 既存V2Vシステム（文献値）の3つを比較する．

#### 7.2.1 遅延（Delay）― AoIを特に推奨

> **AoI（Age of Information）** は「どれだけ新鮮な情報を持ち続けられるか」を評価する近年注目の指標．単純な遅延と異なりV2V情報共有の質を論じる際に説得力がある．

| 指標 | 定義 | 本システムの優位点（仮説） |
|------|------|------------------------|
| エンドツーエンド遅延 | 情報生成から対向車両が受信するまでの時間 | P2Pが事前に確立されるため低遅延 |
| グループ形成遅延 | JOIN送信 〜 全メンバーとのP2P確立完了時間 | ETAベース早期JOINにより時間的余裕あり |
| NATホールパンチング所要時間 | 各車両ペアのP2P確立に要した時間 | G-LocON固有の技術要素（論文の核心） |
| ハンドオーバ遅延 | 交差点Aを離脱し交差点BのP2P確立完了までの時間 | 連続交差点での通信継続性に影響 |
| AoI（Age of Information） | 情報が生成されてから受信者が利用するまでの経過時間 | グループ参加が早いほどAoIが低くなる |

#### 7.2.2 パケット配送（Packet Delivery）

| 指標 | 定義 |
|------|------|
| PDR（Packet Delivery Ratio） | 受信確認数 / 送信パケット数 |
| ジッタ（遅延ゆらぎ） | エンドツーエンド遅延の標準偏差 |
| PDRの交差点距離依存性 | 交差点からの距離ごとのPDR変化 |

#### 7.2.3 スケーラビリティ（Scalability）

| 指標 | 定義 |
|------|------|
| 車両密度 vs 遅延 | 同一グループの車両数増加時の遅延変化 |
| 車両密度 vs PDR | 車両数増加に対するPDRの変化（輻輳の影響） |
| グループ形成遅延 vs グループ規模 | 参加車両数が多いほどP2P確立に時間がかかるか |
| シグナリングオーバヘッド比 | 制御パケット数 / 全パケット数（通常G-LocONと比較） |

#### 7.2.4 接続性（Connectivity）

| 指標 | 定義 |
|------|------|
| P2P確立成功率 | NATホールパンチング成功数 / 試行数 |
| 接続持続時間 | 1ペアのP2P接続が切断されずに維持された時間 |
| 再接続回数 | 同一ペアへの接続試行が複数回発生した回数（無駄な試行） |
| グループ参加率 | 交差点通過時にV2Vグループへ参加できた車両の割合 |

#### 7.2.5 本システム固有の指標

| 指標 | 定義 | 本システムの優位点（仮説） |
|------|------|------------------------|
| **事前接続率**（Pre-arrival Connection Rate） | 交差点到達前にP2Pが確立できた割合 | ETAベースの早期JOINにより高い値が期待できる |
| 有効通信時間比 | 交差点滞在時間のうちP2P通信が確立していた割合 | グループ形成遅延が小さいほど高くなる |
| グループ粒度の適切性 | 同一グループ内で実際に同じ交差点を通過した車両の割合 | 交差点ベースのため無関係な車両が混入しにくい |

---

### 7.3 比較対象の整理

| 比較対象 | 接続方式 | 主な比較指標 |
|---------|---------|-------------|
| 既存V2V（DSRC/IEEE 802.11p） | ブロードキャスト（範囲内全員） | 遅延・PDR・スケーラビリティ（文献値と比較） |
| 既存V2V（C-V2X/LTE） | 基地局経由 | 遅延・AoI（インフラ依存 vs P2P直接通信の差） |
| 通常G-LocON | 自車位置中心の距離ベースP2P | 再接続回数・グループ形成遅延・事前接続率・シグナリングオーバヘッド |
| **本システム（G-LocON V2V）** | 交差点中心ETAベースP2P | 上記すべての改善量 |

---

### 7.4 ログ取得機構（実装時に並行して仕込む）

既存の`OutputToCSV`クラスを流用し以下のCSVを出力する．

| ログファイル | 記録内容 |
|------------|---------|
| `join_log.csv` | intersectionId, vehicleId, t_join_sent, t_join_acked, eta_at_join |
| `p2p_log.csv` | intersectionId, peerId, t_p2p_established, t_arrive, margin_sec（接近前余裕時間） |
| `reconnect_log.csv` | intersectionId, peerId, join_count, leave_count, redundant_attempts |
| `packet_log.csv` | locationUpdateCount, endPointIP, endPointPort, send_time, ack_time, delay_ms |
| `aoi_log.csv` | vehicleId, info_generated_at, info_received_at, aoi_ms |
| `group_log.csv` | intersectionId, timestamp, member_count, member_ids |

---

## 8. 起動手順・トラブルシューティング

### 8.1 サーバ起動手順（IntelliJ IDEA）

#### MasterServer の起動

1. **Run → Edit Configurations...** を開く
2. 左ペインで `master_server.StartUp` を選択
3. **作業ディレクトリ(W)** を以下に設定する
   ```
   D:\Research\Program\G-LocON_2026\G-LocON_V2V_Server\MasterServer
   ```
4. **プログラムの引数** は空欄でよい（省略時は作業ディレクトリ直下の `edge_servers.csv` を自動参照）
5. **適用 → OK** → △で実行
6. コンソールに以下が表示されれば正常起動
   ```
   EdgeServerRegistry: 37件 ロード完了
   MasterServer 起動: port=55556 エッジサーバ登録数=37
   ```

#### EdgeServer の起動

EdgeServerは交差点1つにつき1プロセス起動する．

1. **Run → Edit Configurations...** を開く
2. `edge_server.StartUp` の構成を選択（なければ **+** で新規作成）
3. **作業ディレクトリ(W)** を以下に設定する
   ```
   D:\Research\Program\G-LocON_2026\G-LocON_V2V_Server\EdgeServer
   ```
4. **プログラムの引数** に `交差点ID ポート番号` を空白区切りで入力する
   ```
   35.9515_139.6545 55600
   ```
5. 複数の交差点に起動する場合は構成を複数コピーして引数を変更する
   ```
   構成1: 35.9515_139.6545 55600
   構成2: 35.9515_139.6548 55601
   構成3: 35.9506_139.6545 55602
   ```
6. コンソールに以下が表示されれば正常起動
   ```
   EdgeServer 起動: intersectionId=35.9515_139.6545 port=55600
   ```

---

### 8.2 Windowsファイアウォール設定

AndroidからMasterServer・EdgeServerへのUDP通信がブロックされる場合，以下をPowerShell（**管理者として実行**）で実行する．

```powershell
netsh advfirewall firewall add rule name="MasterServer UDP 55556" protocol=UDP dir=in localport=55556 action=allow
netsh advfirewall firewall add rule name="EdgeServer UDP 55600-55636" protocol=UDP dir=in localport=55600-55636 action=allow
```

各コマンドに `OK` と表示されれば設定完了．

> **症状**: MasterServerが起動しているにもかかわらずAndroid側で `MasterServerClient エラー: Poll timed out` が繰り返し出力され，MasterServerコンソールに受信ログが出ない場合はファイアウォールが原因の可能性が高い．

---

### 8.3 ネットワーク・IPアドレス設定

AndroidとPC（サーバ群）が同一WiFiネットワーク上にある必要がある．

PCに複数のネットワークアダプタ（有線・無線など）がある場合，AndroidからはWiFiアダプタのIPアドレスのみ到達可能なことがある．`ipconfig` コマンドで確認し，AndroidのWiFiと同じネットワークセグメントのIPを使用すること．

| 設定箇所 | ファイル | 変数名 |
|---------|---------|--------|
| SignalingServer / STUNServer IP | `MainActivity.java` | `SERVER_IP` |
| MasterServer IP | `AppController.java` | `MASTER_SERVER_IP` |
| EdgeServer IP（全交差点） | `MasterServer/edge_servers.csv` | 各行の ip フィールド |

> **注意**: `edge_servers.csv` を変更した場合は MasterServer を再起動すること（起動時のみCSVを読み込む）．

---

### 8.4 交差点ID・edge_servers.csv の更新手順

OSRMが返す交差点IDはルートや出発地座標によってわずかに変わることがある．未登録IDが発生した場合は以下の手順で対応する．

1. Logcatで `OsrmRouteClient[N]: id=XXXXX` のIDを確認する
2. MasterServerコンソールで `EdgeServerRegistry: 未登録の交差点ID=XXXXX` として出力されたIDを確認する
3. `edge_servers.csv` に不足しているIDを追記する（ポート番号は未使用番号を割り当てる）
4. 追加したポートに対してEdgeServerプロセスを起動する
5. MasterServerを再起動してCSVを再読み込みする

---

## 9. 評価指標の優先度まとめ

| 優先度 | 指標 | 軸 | 理由 |
|--------|------|-----|------|
| **最重要** | グループ形成遅延・事前接続率 | ②通信 | 本システムの主張（交差点到達前にP2Pを確立できる）の直接的な根拠 |
| **最重要** | AoI（情報鮮度） | ②通信 | V2V情報共有の質を示す近年重視される指標 |
| **最重要** | TTC・急停止連鎖台数 | ①交通 | サービスの目的（安全性改善）の最直接な指標 |
| **重要** | シグナリングオーバヘッド比・再接続回数 | ②通信 | 通常G-LocONとの差異を定量的に示す |
| **重要** | NATホールパンチング成功率・所要時間 | ②通信 | G-LocON固有技術の性能を示す |
| **重要** | 交差点スループット・渋滞解消時間 | ①交通 | サービスの目的（効率改善）の指標 |
| 補足 | 車両密度 vs 遅延・PDR（スケーラビリティ） | ②通信 | 将来の実用化に向けたスケーラビリティの証明 |
| 補足 | ハンドオーバ遅延・グループ粒度 | ②通信 | 連続交差点での通信継続性の評価 |
