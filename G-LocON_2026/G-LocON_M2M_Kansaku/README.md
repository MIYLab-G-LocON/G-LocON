# G-LocON V2V システム 設計・評価方針 設計書
2026年7月13日

---

## 目次

1. [システム概要](#1-システム概要)
2. [フォルダ・パッケージ構成](#2-フォルダパッケージ構成)
3. [各モジュール設計方針](#3-各モジュール設計方針)
4. [通信プロトコル設計](#4-通信プロトコル設計)
5. [実装順序とgit記録方針](#5-実装順序とgit記録方針)
6. [シミュレーション設計（SUMO）](#6-シミュレーション設計sumo)
7. [評価設計](#7-評価設計)
8. [起動手順](#8-起動手順)
9. [トラブルシューティング](#9-トラブルシューティング)
10. [評価指標の優先度まとめ](#10-評価指標の優先度まとめ)

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
├── G-LocON_Client_2026/        旧改修版（OSM導入・コード整理済）
├── G-LocON_Server_2026/        旧改修版
└── G-LocON_M2M_Kansaku/
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
| `sim/` | **★新規追加** | SUMOモード：SimBridgeから割り当てられた車両のルート・位置でアプリを動かす（SimBridgeClient） |

### 2.3 G-LocON_V2V_Server モジュール構成

| モジュール | 状態 | 役割 | ポート |
|-----------|------|------|--------|
| `STUNServer/` | G-LocONから流用（変更なし） | グローバルIP/Port取得 | 55554 |
| `SignalingServer/` | 残置（V2Vでは不使用） | 旧G-LocONの位置ベース検索 | 55555 |
| `MasterServer/` | **★新規** | 交差点ID → エッジサーバAddr配布 | 55556 |
| `EdgeServer/` | **★新規** | 交差点V2Vグループ管理（交差点ごとに1プロセス） | 556XX |
| `VirtualClient/` | 拡張（動作確認用） | V2Vシナリオのシミュレーション | - |
| `SimBridge/` | **★新規** | SUMO（TraCI）とAndroid・サーバを繋ぐPythonブリッジ．シナリオ作成，仮想クライアント，サーバ一括起動も含む | 55700 |

---

## 3. 各モジュール設計方針

### 3.1 EdgeServer（新規）

交差点ごとに1プロセスとして起動する．SignalingServerのV2V特化版と位置付ける．

| クラス | 役割 |
|--------|------|
| `StartUp.java` | 起動引数で交差点ID・ポート番号を受取りEdgeServerReceiveを起動 |
| `EdgeServerReceive.java` | JOIN/LEAVE/SEARCHの3種UDPを受信し処理を振り分け |
| `EdgeServerSend.java` | グループメンバー一覧の返送・NATホールパンチング通知・離脱通知（NAT_REGISTER/REPLY_RESULT/PEER_LEFT） |
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
| KEEPALIVE | Client | EdgeServer | JOIN中に15秒ごと送信するNATマッピング維持用パケット（応答なし） |
| getPeripheralUserInfoList | EdgeServer | Client | グループメンバー一覧を返送 |
| doUDPHolePunching | EdgeServer | Client（他車両） | NATホールパンチング通知 |
| peerLeft | EdgeServer | Client（残りの車両） | 他の車両がLEAVEしたことの通知．受け取った車両はその交差点グループのメンバーから外す |
| NATRegisterDstAddrPort | Client | 他車両 | NATに穴を開けるパケット |
| SendLocation | Client | 他車両 | P2P直接通信（位置情報送信） |
| VEHICLE_COMMAND | Client | SimBridge | シミュレータ車両への行動指令（減速・復帰など）（未実装） |
| SIM_HELLO / SIM_ROUTE_REQ / SIM_BYE | Client（SUMOモード） | SimBridge | 車両の割り当て要求／ルート再送要求／終了 |
| SIM_ROUTE | SimBridge | Client（SUMOモード） | 割り当てた車両のルート上の交差点列（OSRMの結果の代わり） |
| SIM_LOCATION | SimBridge | Client（SUMOモード） | 割り当てた車両の位置・速度・進行方向（1秒ごと，GPSの代わり） |
| SIM_END | SimBridge | Client（SUMOモード） | 車両が目的地に到着（次の車両を割り当てる） |
| peerLeft | EdgeServer | 仮想クライアント | 実機と同じく離脱通知を受け取る（仮想クライアントも同じプロトコルを使う） |

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

| セクション | 内容 | コミットメッセージ | 状況 |
|-----------|------|------------------|------|
| 1 | EdgeServer新規作成 | `add EdgeServer module for intersection V2V group management` | ✅ 実装済み |
| 2 | MasterServer新規作成 | `add MasterServer module for edge server address distribution` | ✅ 実装済み |
| 3 | Client: navigation/ 新規作成 | `add navigation package: OSRM route client and intersection manager` | ✅ 実装済み |
| 4 | Client: intersection/ 新規作成 | `add intersection package: edge server JOIN/LEAVE client` | ✅ 実装済み |
| 5 | AppController変更（V2Vロジック統合） | `extend AppController with V2V join/leave logic` | ✅ 実装済み |
| 6 | MapManager・MainActivity変更（UI） | `extend UI: route display and intersection group status` | ✅ 実装済み |
| 7 | SimBridge（SUMOシナリオ＋TraCIブリッジ） | `feat(Section7): SUMOシナリオとTraCIブリッジを追加` | ⬜ 未実装 |
| 8 | SimBridge（実時間ブリッジ・仮想クライアント）＋ Client: sim/ パッケージ（SUMOモード・表示切り替え） | `feat(Section8): SUMOモードと仮想クライアントを追加` | ✅ 実装済み（実機での確認待ち） |
| 9 | VEHICLE_COMMANDによる双方向制御 | `feat(Section9): V2V情報に基づく車両制御をSUMOへ返す` | ⬜ 未実装 |
| 10 | 評価用ログの拡充（P2P確立時刻・AoI・パケット） | `feat(Section10): 評価用ログを追加` | ⬜ 未実装 |

---

## 6. シミュレーション設計（SUMO）

> **実装状況**: SimBridge の段階1（エリア・エッジサーバ配置・シナリオ・V2Vなし／理想V2Vの実行と集計）と，段階2（実時間ブリッジ・仮想クライアント・アプリのSUMOモード）は実装済み．
> 段階2はPC上の試験（MasterServer・エッジサーバ10台・仮想クライアント・模擬端末）で動作を確認済みで，実機での確認は未実施（2026年9月時点）．VEHICLE_COMMAND は未実装．
> 当初はCARLAを想定していたが，下記の理由から**SUMOを主なシミュレータとし，3D表示などが必要になった段階でCARLAを追加する**方針に変更した．

### 6.1 シミュレータの選定

| 観点 | SUMO | CARLA |
|------|------|-------|
| 得意分野 | 道路網全体の交通流（多数車両・渋滞・信号） | 1台ごとの物理挙動・センサ・3D映像 |
| 必要な計算機 | 一般的なPC | GPU搭載PC |
| 車両台数 | 数百〜数千台 | 数十台程度 |
| 地図 | OpenStreetMapから実在地域を変換可能 | 付属の街マップが中心 |
| 外部制御 | TraCI（Python）で位置取得・速度変更 | Python API |
| 評価指標の取得 | TTC・PET（SSM機能），燃料・排出量，待ち時間などを標準出力 | 多くを自前で算出 |

本研究で必要なのは「多数車両での交差点グループ参加・離脱」「交通安全・効率への効果」「実在の交差点での評価」であり，いずれもSUMOの得意分野である．
V2V情報は位置情報のP2P共有であり，センサ認識や3D映像は評価に不要なため，SUMOを主とする．
CARLAはSUMOとの公式連携（co-simulation）があるため，デモ用の可視化などが必要になった時点で追加できる．

### 6.2 実験モード

アプリには従来の**実機モード**（目的地入力 → OSRMでルート取得 → ルート上の交差点をMasterServerに問い合わせ → GPS/SIM走行）を残したまま，
**SUMOモード**を追加する．SUMOモードでは，SimBridge が SUMO の車両1台を端末に割り当て，その車のルート（交差点列）と位置を送る．
アプリはそれを OSRM・GPS の代わりに使うだけで，MasterServer への問い合わせ・ETAによるJOIN/LEAVE・P2P通信は実機モードと同じ処理を行う．

| モード | 実機 | SUMOの他の車両 | 主な目的 |
|--------|------|---------------|---------|
| モードA（実機通信） | 3台（各1台=SUMO車両1台に乗る） | 走行のみ（サーバには参加しない） | 本物のP2P通信の性能（遅延・PDR・NAT越え），実機どうしが偶然同じグループに入ったときの動作 |
| モードB（多数車両） | 1台（SUMO車両1台に乗り，画面で目視確認） | **全車両が仮想クライアントとしてエッジサーバに実際にJOIN/LEAVE** | 多数車両でのグルーピング・サーバ処理の正しさと負荷 |

- 2つのモードは同じ仕組みで，SimBridge の設定（受け付ける実機の台数 `--phones`，仮想クライアントの有無 `--virtual`）だけが異なる
- **仮想クライアント**: SUMOの車両1台ごとに専用のUDPソケットを持ち，アプリと同じ手順・同じ形式で
  MasterServerへの問い合わせ，ETA<30秒でJOIN，30m離れて遠ざかったらLEAVE，15秒ごとのKEEPALIVE を行う．
  エッジサーバからは実機と区別がつかない．グループ内の実機へは位置（SendLocation）も送るため，実機の地図に仮想車両が表示される（peerID は `sim-<車両ID>`）
- **表示の切り替え**: アプリの「表示」ボタンで，他車両を「全て／実機のみ／なし」に切り替える．実機は赤・緑，仮想車両は半透明の灰色のピン
- **PC画面での確認**: `sim_bridge.py --gui` で sumo-gui を表示し，車両をグループ（交差点）ごとに色分けする（実機が乗っている車は紫，未参加は灰色）
- **自動検証**: SimBridge は，各仮想クライアントがエッジサーバから受け取ったグループ一覧と，実際にその交差点にJOIN中の車両を毎秒比較し，
  一覧の一致率・JOIN応答時間などを記録する（評価指標「①システム」）
- 実機なし・PCだけでもモードBを動かせる（`--phones 0 --virtual --local`）

### 6.3 構成と通信フロー

```
                        SIM_ROUTE / SIM_LOCATION / SIM_END
SUMO ──TraCI── SimBridge ─────────────────────────────────▶ Android（SUMOモード）── P2P ──┐
  (sumo-gui)       │                                              │ JOIN/LEAVE             │
                   │  仮想クライアント×車両数（モードB）             ▼                        │
                   └──── INTERSECTION_QUERY / JOIN / LEAVE ──▶ MasterServer・EdgeServer    │
                         ◀── メンバー一覧・新規参加・離脱通知 ──                           │
                         ── SendLocation（実機へ）────────────────────────────────────────┘
```

- **地図とエッジサーバ**: 実験エリアのOpenStreetMapデータから**正方形のエリア**（既定1km四方）を切り出してSUMO道路網に変換し，
  エリア内の**全交差点**（3方向以上に道がつながる地点）を洗い出す．その中から**ランダムにエッジサーバを選ぶ**（数・乱数シード・最小間隔を指定可能）．
  選んだ結果は MasterServer の `edge_servers.csv` と同じ形式で出力し，`start_servers.py` で MasterServer と全エッジサーバを一括起動する
- **交通**: 車両はエリア内のランダムな出発地→目的地を自由に走り，自分のルートが通るエッジサーバ交差点のグループに参加・離脱する
- **ルート（進行先の交差点）**: 自由に走る車は目的地入力もOSRMも使わないため，SimBridge が**その車のSUMO上のルートが通る交差点の列**を `SIM_ROUTE` で送る．
  交差点IDはエリア内の全交差点と同じ「緯度5桁_経度5桁」で，エッジサーバ一覧と完全に一致する．アプリはこれを OSRM の結果と同じ入口に渡すため，
  「ルート → 交差点リスト → MasterServerでエッジサーバ取得 → ETAでJOIN」の流れはそのまま使える
- **位置**: SimBridge が割り当てた車両の位置（緯度経度）・速度・進行方向を `SIM_LOCATION` で1秒ごとに送る．アプリはGPS・SIM走行の代わりにこの位置を使い，P2Pでも送る
- **乗り換え**: 車が目的地に着くと `SIM_END` を送り，アプリはJOIN中の交差点から離脱する．SimBridge は次に出発する車を割り当てる（実験中ずっとどこかの車として参加し続ける）
- **時間同期**: Android側は実時間で通信するため，SimBridgeはSUMOを実時間に合わせて0.5秒ずつ進める（`--speed` で動作確認用に速くできる）
- **Android → SUMO（予定）**: V2V情報共有に基づき生成した `VEHICLE_COMMAND` をSimBridgeへUDP送信し，TraCIで車両を制御する

### 6.4 VEHICLE_COMMAND の種類

| command | 内容 | トリガー | TraCIでの実現 |
|---------|------|---------|--------------|
| DECELERATE | 目標速度まで減速 | 前方急停止・渋滞情報の受信 | `vehicle.slowDown(id, 目標速度, 所要時間)` |
| RESUME | 通常速度に復帰 | 危険状況の解消 | `vehicle.setSpeed(id, -1)` |
| HOLD_SPEED | 現在速度を維持 | 前方渋滞継続中 | `vehicle.setSpeed(id, 現在速度)` |

### 6.5 シナリオ設計の注意点

- SUMOの標準の車両モデルは衝突しないよう安全側に動くため，そのままではヒヤリハットが発生しにくい．
  運転のばらつき（`sigma`）・反応時間（`actionStepLength`）・安全確認の緩和（`speedMode`）や，前方車両の急停止イベントを設定し，**V2Vの有無で差が出る危険場面を含むシナリオ**を用意する
- 同じシナリオ・同じ乱数シードで「V2Vあり」「V2Vなし」を実行して比較する

### 6.6 評価指標とSUMO出力の対応

| 指標 | SUMOでの取得方法 |
|------|-----------------|
| TTC・PET・ヒヤリハット | SSMデバイス（`--device.ssm.*`） |
| 急制動回数・急停止連鎖台数 | FCD出力（全車両の位置・速度・加速度の時系列）から集計 |
| 交差点スループット | 交差点手前・通過後の検知器（E1） |
| 渋滞長・渋滞解消時間 | 区間検知器（E2）の渋滞長，FCD出力 |
| 平均停止時間 | tripinfo出力の待ち時間 |
| 燃料消費量 | 排出モデル（emissionsデバイス） |

通信性能の指標（7.2）はAndroid・サーバ側のログで取得するため，シミュレータの種類に依存しない．

### 6.7 構築の段階

| 段階 | 内容 |
|------|------|
| 準備 | 実機3台・エッジサーバ3台でJOIN → P2P通信 → LEAVEを通しで確認 |
| 1 | 正方形エリアの地図をSUMO用に変換し，全交差点からエッジサーバをランダムに配置，エリア内を自由に走る交通流（シナリオ）を作成 ✅ |
| 2 | SimBridge（実時間ブリッジ・仮想クライアント・サーバ一括起動）とアプリのSUMOモード ✅（PC上の試験で確認済み．実機での確認待ち） |
| 3 | モードA：SUMO車両1台をスマホ1台に対応させ，実際の通信で動作確認 |
| 4 | VEHICLE_COMMANDによる双方向制御 |
| 5 | モードB：全車両を仮想クライアントとしてエッジサーバに参加させ，実機1台で目視確認 |
| 6 | 評価（V2Vあり／なし，通常G-LocONとの比較）．評価用ログは段階3までに並行して用意する |

---

## 7. 評価設計

### 7.1 評価軸①：交通への影響（サービスの目的）

V2Vあり vs V2Vなしを比較し，交通安全・効率への貢献を評価する．測定環境はSUMOシミュレーション（取得方法は6.6）．

#### 7.1.1 安全性指標

| 指標 | 定義 | 測定方法 |
|------|------|---------|
| TTC（Time To Collision） | 現在の速度差・車間距離から算出する衝突予測時間 | SUMOのSSMデバイスが算出 |
| PET（Post Encroachment Time） | 同一交差点空間を2台が通過した時間差 | 交差点通過タイムスタンプから算出 |
| 急制動発生回数 | 閾値以上の減速度（例: -3m/s²）が発生した回数 | SUMOのFCD出力（加速度）から検出 |
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
| 燃料消費量（推定） | SUMOの排出モデルで算出 |

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

| ログファイル | 記録内容（設計） | 実装状況 |
|------------|---------|---------|
| `join_log.csv` | intersectionId, vehicleId, t_join_sent, t_join_acked, eta_at_join | ⚠️ 実装済み（項目が異なる）: `intersectionId, t_update_ms, eta_sec, distance_m, event`（IntersectionManager） |
| `p2p_log.csv` | intersectionId, peerId, t_p2p_established, t_arrive, margin_sec（接近前余裕時間） | ⚠️ 実装済み（項目が異なる）: `intersectionId, t_join_sent_ms, eta_at_join_sec, edgeServerIp, edgeServerPort`（EdgeServerClient．実質JOIN送信ログ） |
| `reconnect_log.csv` | intersectionId, peerId, join_count, leave_count, redundant_attempts | ⬜ 未実装 |
| `packet_log.csv` | locationUpdateCount, endPointIP, endPointPort, send_time, ack_time, delay_ms | ⬜ 未実装 |
| `aoi_log.csv` | vehicleId, info_generated_at, info_received_at, aoi_ms | ⬜ 未実装 |
| `group_log.csv` | intersectionId, timestamp, member_count, member_ids | ⬜ 未実装（EdgeServerReceive のコメントに記載のみ） |

---

## 8. 起動手順

### 8.1 サーバ起動手順（IntelliJ IDEA）

#### 事前準備（新しい環境でクローンした場合）

- **JDK 17** を用意する（プロジェクトSDK名 `17`．IntelliJで「JDK "17" が見つかりません」と出たら「ダウンロードするJDKの選択」から17を入れる）
- 実行構成（Run Configuration）はリポジトリに含まれていない．各モジュールの `StartUp.java` を開き，`main` 横の▶で一度実行すると `master_server.StartUp` / `edge_server.StartUp` の構成が作られる
- バッチファイル（方法B）を使う場合は，先に **ビルド → プロジェクトのビルド** を実行し `out/production/EdgeServer` を生成しておく

#### MasterServer の起動

1. **Run → Edit Configurations...** を開く
2. 左ペインで `master_server.StartUp` を選択
3. **作業ディレクトリ(W)** を以下に設定する
   ```
   <クローン先>\G-LocON_2026\G-LocON_M2M_Kansaku\G-LocON_V2V_Server\MasterServer
   ```
4. **プログラムの引数** は空欄でよい（省略時は作業ディレクトリ直下の `edge_servers.csv` を自動参照）
5. **適用 → OK** → △で実行
6. コンソールに以下が表示されれば正常起動
   ```
   EdgeServerRegistry: 3件 ロード完了
   MasterServer 起動: port=55556 エッジサーバ登録数=3
   ```
   ※ `edge_servers.csv` にはルート上の全交差点を記載しているが，EdgeServerを設置する3か所（ES1〜ES3）以外は `#` で無効化しているため3件となる（9.5参照）．

#### EdgeServer の起動

EdgeServerは交差点1つにつき1プロセス起動する．起動する交差点IDは `edge_servers.csv` の有効行（`#` なし行）と一致させること．

**方法A: IntelliJ の実行構成を複製する**

1. **Run → Edit Configurations...** を開く
2. `edge_server.StartUp` の構成を交差点数だけ複製する（右クリック → Copy）
3. 各構成の **プログラムの引数** に `交差点ID ポート番号` を空白区切りで入力する
   ```
   構成ES1: 35.95151_139.65476 55601
   構成ES2: 35.94627_139.65333 55616
   構成ES3: 35.94763_139.64549 55625
   ```
4. 構成名は `ES1_begin` `ES2_middle` `ES3_end` などわかりやすい名前にすること（同名だと並列起動できない）
5. コンソールに以下が表示されれば正常起動
   ```
   EdgeServer 起動: intersectionId=35.95151_139.65476 port=55601
   ```

**方法B: バッチファイルで起動する（起動時はIntelliJ不要）**

`G-LocON_V2V_Server/EdgeServer/` に以下のバッチファイルを用意している．ダブルクリックで起動できる（事前にIntelliJでビルドしておくこと）．

| ファイル | 交差点 | ポート |
|---------|--------|--------|
| `start_ES1_begin.bat` | 35.95151_139.65476 | 55601 |
| `start_ES2_middle.bat` | 35.94627_139.65333 | 55616 |
| `start_ES3_end.bat` | 35.94763_139.64549 | 55625 |

---

### 8.2 SUMOモードでの起動手順（SimBridge）

SUMOと実時間でつないで実験する場合は，上記の個別起動の代わりに `G-LocON_V2V_Server/SimBridge/` のスクリプトを使う．
詳しい準備・引数・出力は [SimBridge/README.md](G-LocON_V2V_Server/SimBridge/README.md) を参照．

```
cd G-LocON_V2V_Server/SimBridge
python start_servers.py --stun                        # STUN・MasterServer・全エッジサーバを一括起動（別ウィンドウで）
python sim_bridge.py --phones 3                       # モードA: 実機3台
python sim_bridge.py --phones 1 --virtual --gui       # モードB: 仮想クライアント＋実機1台（sumo-guiで色分け表示）
python sim_bridge.py --phones 0 --virtual --gui --local   # 実機なし・PCだけで試す
```

スマホではアプリの「開始」→「SUMO」を押す．「表示」ボタンで他車両を「全て／実機のみ／なし」に切り替える．

---

## 9. トラブルシューティング

### 9.1 Windowsファイアウォールによる通信ブロック

**症状**: MasterServerが起動しているにもかかわらずAndroid側で `MasterServerClient エラー: Poll timed out` が繰り返し出力され，MasterServerコンソールに受信ログが出ない．

**原因**: Windowsファイアウォールが該当ポートへのUDP通信をブロックしている．

**対処**: PowerShell（**管理者として実行**）で以下を実行する．

```powershell
netsh advfirewall firewall add rule name="G-LocON UDP IN" protocol=UDP dir=in localport=55554-55639 action=allow profile=any
```

`OK` と表示されれば設定完了．

> **重要**: `profile=any` を必ず付けること．省略するとモバイルホットスポット・テザリング経由の接続（プロファイル: パブリック）でブロックされる．

---

### 9.2 ネットワーク・IPアドレス設定

**症状**: AndroidとPC間で通信が届かない．

**原因**: PCに複数のネットワークアダプタ（有線・無線など）がある場合，AndroidからはWiFiアダプタのIPアドレスのみ到達可能なことがある．

**対処**: `ipconfig` コマンドでIPを確認し，AndroidのWiFiと同じネットワークセグメントのIPを各設定箇所に記載する．

| 設定箇所 | ファイル | 変数名 |
|---------|---------|--------|
| SignalingServer / STUNServer IP | `MainActivity.java` | `SERVER_IP` |
| MasterServer IP | `AppController.java` | `MASTER_SERVER_IP` |
| EdgeServer IP（全交差点） | `MasterServer/edge_servers.csv` | 各行の ip フィールド |

> **注意**: `edge_servers.csv` を変更した場合は MasterServer を再起動すること（起動時のみCSVを読み込む）．

---

### 9.3 iPhoneテザリングでの通信不可

**症状**: iPhoneのテザリングを使用すると，同一ネットワーク上のAndroid→PCへのUDP通信ができない．

**原因**: iPhoneのテザリングはクライアント間通信を遮断する（クライアントアイソレーション）ため，AndroidからPCへのUDPパケットが届かない．

**対処**: iPhoneのテザリングを使わず，**PCのモバイルホットスポット**（Windowsの「モバイルホットスポット」機能）でネットワークを共有する．

| 設定箇所 | 値 |
|---------|-----|
| `MainActivity.java` の `SERVER_IP` | `192.168.137.1`（PC モバイルホットスポット側IP） |
| `AppController.java` の `MASTER_SERVER_IP` | `192.168.137.1` |
| `edge_servers.csv` の ip フィールド | `192.168.137.1` |

---

### 9.4 edge_servers.csv の交差点IDが一致しない

**症状**: MasterServerコンソールに `EdgeServerRegistry: 未登録の交差点ID=XXXXX` が出力され，アプリのマップにマーカーが表示されない．

**原因**: OSRMが返す交差点IDとCSVに登録されているIDが一致していない．

**対処**:
1. Logcatで `OsrmRouteClient[N]: id=XXXXX` のIDを確認する
2. `edge_servers.csv` に不足しているIDを追記する（ポート番号は未使用番号を割り当てる）
3. 追加したポートに対してEdgeServerプロセスを起動する
4. MasterServerを再起動する

> **参考**: MasterServerの近傍検索閾値は30m（`EdgeServerRegistry.java` の `PROXIMITY_THRESHOLD_M`）．CSVのIDとOSRMのIDが30m以内であれば自動的に近傍一致する．

---

### 9.5 テスト時のEdgeServer切り替え方法（コメントアウト方式）

CSVには全交差点を記載し，起動するEdgeServerの行だけ有効にする．`#` 始まりの行はMasterServerが無視するため，アプリのマップには有効行のみマーカー表示される．

```csv
# 有効（マーカー表示・JOIN/LEAVE対象）
35.95151_139.65476,192.168.137.1,55601
# 無効（コメントアウト・マーカー非表示）
#35.95064_139.65446,192.168.137.1,55602
```

**切り替え手順**:
1. `edge_servers.csv` を編集（使いたい行の `#` を外す／使わない行に `#` を付ける）
2. 有効にした行のポートでEdgeServerを起動する
3. MasterServerを再起動する（起動時のみCSVを読み込むため）

---

### 9.6 SignalingServerへのゴーストピア残留

**症状**: アプリを強制終了・再インストールした後，前回起動時のピア情報が別端末として検出され続ける．

**原因**: DELETEが送られずにサーバ側にエントリが残留する（電池切れ・強制終了時はDELETEを送れない）．

**対処**: SignalingServerにTTL（30秒）を実装済み．SEARCHパケット（5秒ごと送信）をハートビートとして使用し，30秒間SEARCHを受信しなかったエントリは自動削除される．通常は再インストール後30〜40秒で消える．

---

### 9.7 仮想位置モード・SIMの使い方

実験予定地点にいない場合でも，仮想位置を使ってSIMを動かしてV2Vロジックをデバッグできる．

**手順**:
1. アプリの「開始」ボタンで通信を開始する
2. ピアID・目的地を入力してルートを取得する
3. **「仮想位置」ボタン**を押す → カメラが仮想座標（コード内 `TEST_LATITUDE/LONGITUDE`）に移動する
4. **「SIM」ボタン**を押す → 仮想位置を起点にルート上を10m/sで自動走行する
5. 交差点に近づくとJOIN（緑マーカー），離れるとLEAVE（グレー）に変化する

> **注意**: 仮想位置モード中はGPSによる位置更新・速度計算が無効になる．これはGPS位置と仮想位置が離れている場合に生じる異常速度計算（数万km/h）によるETA誤算を防ぐためである．SIM走行はこの制約の影響を受けない．

---

## 10. 評価指標の優先度まとめ

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
