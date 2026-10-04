# G-LocON V2V システム 設計・評価方針 設計書
2026年7月13日 作成，2026年10月5日 全体を見直し

---

> **実験の記録**: 実験ごとの目的・方法・結果・考察・次に変えることは [EXPERIMENTS.md](EXPERIMENTS.md) に残している。
> **正式評価の手順**（自分のPCで実行 → 表 → グラフ）は [SimBridge/EVALUATION.md](G-LocON_V2V_Server/SimBridge/EVALUATION.md)。

## 目次

1. [システム概要](#1-システム概要)
2. [フォルダ・パッケージ構成](#2-フォルダパッケージ構成)
3. [各モジュール設計方針](#3-各モジュール設計方針)
4. [通信プロトコル設計](#4-通信プロトコル設計)
5. [実装順序とgit記録方針](#5-実装順序とgit記録方針)
6. [シミュレーション設計（SUMO）](#6-シミュレーション設計sumo)
7. [評価設計](#7-評価設計)
8. [起動手順・検証の手順](#8-起動手順)
9. [トラブルシューティング](#9-トラブルシューティング)
10. [評価指標の優先度まとめ](#10-評価指標の優先度まとめ)
11. [P2P通信とNAT](#11-p2p通信とnat)

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
4. **ETA < τ**（参加タイミング），または**参加円（交差点から半径 ρ）の中**に入ったらエッジサーバへJOIN要求を送信
   - τ は評価項目とし **15秒 / 30秒 / 45秒** を比較する．既定値は **15秒**．
     τ が大きいほど交差点の手前で早くグループに入り P2P 接続の準備時間が長くとれるが，グループの人数・サーバの負荷が増える
   - τ はアプリでは `IntersectionManager.DEFAULT_JOIN_ETA_SEC`，SimBridgeでは `common.JOIN_ETA_SEC`（実行時は `--join-eta`）．
     SUMOモードでは δ と同じく `SIM_ROUTE` で実機に送る．実機だけの評価では，アプリの状態カードをタップして τ・δ を候補から選べる
   - ρ（参加円の半径）は **0（なし）/ 50m / 100m / 150m** を比較する．既定値は **100m**（2026/10/04 追加）．
     渋滞でゆっくり進む車は ETA が大きくなり，交差点のすぐ手前にいても参加しないため，離脱円と同じように距離でも参加させる．
     アプリでは `IntersectionManager.DEFAULT_JOIN_RADIUS_M`，SimBridgeでは `common.JOIN_DIST_M`（`--join-dist`）．地図には青い枠線の円で表示する
   - ETA は交差点までの直線距離÷速度（速度の下限 1 m/s）．速度は GPS が測った速度を使う（止まっているときの GPS の位置の揺れで速度が出ないように）．
     アプリ・SimBridge の仮想クライアント・方式の比較（`compare_schemes.py`，2026/10/05 から）で同じ計算
5. JOIN承認後，グループ内の他車両とG-LocONのNATホールパンチングでP2P通信を確立
6. 交差点を**通過済み**（交差点から p=20m 以内に近づいた）で，交差点中心からの距離 **d ≥ δ かつ Δd > 0**（離れている）になったらLEAVE通知を送信
   - δ（離脱円の半径）は評価項目とし **30m / 60m / 100m / 150m** を比較する．既定値は **100m**（2026/10/04 に 60m から変更．すれ違いは交差点を出た後にも起きるため．実験記録 E9）．
     δ が大きいほど交差点を出た後もグループに長く残り（P2P接続時間が長い），小さいほどグループの人数が少なくサーバ・通信の負荷が小さい
   - δ はアプリでは `IntersectionManager.DEFAULT_LEAVE_THRESHOLD_M`，SimBridgeでは `common.LEAVE_DIST_M`（実行時は `--leave-dist`）．
     SUMOモードでは SimBridge が `SIM_ROUTE` で δ を実機に送り，実機と仮想クライアントを同じ δ にそろえる．アプリの地図にはこの半径で離脱円を描く
   - 「通過済み」の条件が無いと，道が曲がって直線距離が一時的に増えたときに交差点の手前でLEAVEしてしまい，
     一度LEAVEした交差点には再JOINしないため，グループ外のまま交差点を通過していた（SUMOでの試験で，エッジサーバ交差点を通過した車の約3割）
   - p=20m は，SUMOで全車両の位置を1秒ごと（アプリと同じ間隔）に記録し，交差点に最も近づいた距離を集計して決めた
     （位置は車の中心．エッジサーバ交差点312回の通過で 99% が 10.8m 以内・最大 11.4m，全交差点8,922回で最大 19.0m．実機のGPS誤差を見込んで 20m）
   - 20m 以内に入らないまま通り過ぎた場合に備え，ルート上の後の交差点を通過した時点で前の交差点もLEAVEする
     （通過判定はルート順に，まだ通過していない最初の交差点から3つ先までを対象とする）
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
| `EdgeServer/` | **★新規** | 交差点V2Vグループ管理（交差点ごとに1プロセス） | 55600〜（SUMO用シナリオは20か所: 55600〜55619） |
| `VirtualClient/` | 拡張（動作確認用） | V2Vシナリオのシミュレーション | - |
| `SimBridge/` | **★新規** | SUMO（TraCI）とAndroid・サーバを繋ぐPythonブリッジ．シナリオ作成，仮想クライアント，サーバ一括起動，従来G-LocONとの方式の比較（`compare_schemes.py`），正式評価の実行と集計（`eval_run.py`・`eval_report.py`）も含む | 55700 |

---

## 3. 各モジュール設計方針

### 3.1 EdgeServer（新規）

交差点ごとに1プロセスとして起動する．SignalingServerのV2V特化版と位置付ける．

| クラス | 役割 |
|--------|------|
| `StartUp.java` | 起動引数で交差点ID・ポート番号を受取りEdgeServerReceiveを起動 |
| `EdgeServerReceive.java` | JOIN/LEAVE/SEARCH/KEEPALIVE のUDPを受信し処理を振り分け |
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
| `IntersectionManager.java` | 交差点リスト管理，ETA計算（距離÷速度），JOIN判定（ETA<τ または参加円の中），LEAVE判定（通過済み かつ d≥δ かつ Δd>0） |
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
| SendLocation | Client | 他車両 | P2P直接通信（位置情報送信）．急停止中は危険情報 `hazard {id, intersectionId, active, latitude, longitude, bearing}` を付ける（解消時は active=false） |
| VEHICLE_COMMAND | Client（SUMOモード） | SimBridge | シミュレータ車両への行動指令 `{command: DECELERATE / RESUME, hazardId, gap}`．危険情報を受けて接近中と判定したときに送る |
| SIM_HELLO / SIM_ROUTE_REQ / SIM_BYE | Client（SUMOモード） | SimBridge | 車両の割り当て要求／ルート再送要求／終了 |
| SIM_ROUTE | SimBridge | Client（SUMOモード） | 割り当てた車両のルート上の交差点列と道の形（OSRMの結果の代わり），参加タイミング τ・離脱円 δ |
| SIM_LOCATION | SimBridge | Client（SUMOモード） | 割り当てた車両の位置・速度・進行方向（1秒ごと，GPSの代わり） |
| SIM_END | SimBridge | Client（SUMOモード） | 車両が目的地に到着（次の車両を割り当てる） |
| SIM_VEHICLES | SimBridge | Client（SUMOモード） | 自車の周り（既定400m）の全車両の位置・向き（1秒ごと，「表示:全車両」用） |
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
| SimBridge | 55700 | SUMOモードのアプリとの通信（SIM_HELLO など） |

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
| 7 | SimBridge（SUMOシナリオ＋TraCIブリッジ） | `feat(Section7): SUMOシナリオとTraCIブリッジを追加` | ✅ 実装済み |
| 8 | SimBridge（実時間ブリッジ・仮想クライアント）＋ Client: sim/ パッケージ（SUMOモード・表示切り替え） | `feat(Section8): SUMOモードと仮想クライアントを追加` | ✅ 実装済み（実機1台で確認済み．3台同時は未確認） |
| 9 | VEHICLE_COMMANDによる双方向制御 | `feat(Section9): V2V情報に基づく車両制御をSUMOへ返す` | ✅ 実装済み（仮想クライアントで確認済み．実機の警告表示は確認待ち） |
| 10 | 評価用ログの拡充（P2P確立時刻・AoI・パケット） | `feat(Section10): 評価用ログを追加` | ⬜ 未実装 |
| 11 | 従来G-LocONとの方式の比較，正式評価の実行と集計 | `compare_schemes.py`，`eval_run.py`，`eval_report.py` | ✅ 実装済み（繰り返し実行はこれから） |

---

## 6. シミュレーション設計（SUMO）

> **実装状況（2026年10月時点）**: SimBridge の段階1（エリア・エッジサーバ配置・シナリオ・V2Vなし／理想V2Vの実行と集計），
> 段階2（実時間ブリッジ・仮想クライアント・アプリのSUMOモード），車両制御（VEHICLE_COMMAND），従来G-LocONとの方式の比較は実装済み．
> PC上の試験（MasterServer・エッジサーバ20台・仮想クライアント）と実機1台で動作を確認済み．実機3台同時のSUMO連携と，評価用ログの拡充は未実施．
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
  MasterServerへの問い合わせ，ETA<τ（既定15秒）または参加円（既定100m）の中でJOIN，通過後にδ（既定100m）離れて遠ざかったらLEAVE，15秒ごとのKEEPALIVE を行う．
  エッジサーバからは実機と区別がつかない．グループ内の実機へは位置（SendLocation）も送るため，実機の地図に仮想車両が表示される（peerID は `sim-<車両ID>`）
- **表示の切り替え**: アプリの下の操作パネルで，他車両の表示を「P2P / 全車両 / 実機 / なし」から選ぶ．
  「P2P」はP2Pでつながった車（実機は赤・緑，仮想車両は半透明の灰色のピン），「実機」はつながっていない実機も青い矢印で出す，
  「全車両」はそれに加えてつながっていない車も小さな矢印で出す（灰色=SUMOの車，青=実機．SimBridge が `SIM_VEHICLES` で送る）．
  2つを見比べると，グループに入ってP2Pでつながった車がどれかが分かる
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

- **地図とエッジサーバ**: 実験エリアのOpenStreetMapデータから**長方形のエリア**（既定 東西1.8km×南北1.15km の大学周辺．環境変数 `GLOCON_SCENARIO=arterial` で国道16号・県道5号を含む 3.0km×2.7km の地図に切り替え）を切り出してSUMO道路網に変換し，
  エリア内の**全交差点**（3方向以上に道がつながる地点）を洗い出す．その中から**交通量の多い順にエッジサーバを置く**（既定20か所．数・最小間隔を指定可能，ランダムにも選べる）．
  選んだ結果は MasterServer の `edge_servers.csv` と同じ形式で出力し，`start_servers.py` で MasterServer と全エッジサーバを一括起動する
- **交通**: 車両はエリア内のランダムな出発地→目的地を自由に走り，自分のルートが通るエッジサーバ交差点のグループに参加・離脱する
- **ルート（進行先の交差点）**: 自由に走る車は目的地入力もOSRMも使わないため，SimBridge が**その車のSUMO上のルートが通る交差点の列**を `SIM_ROUTE` で送る．
  交差点IDはエリア内の全交差点と同じ「緯度5桁_経度5桁」で，エッジサーバ一覧と完全に一致する．アプリはこれを OSRM の結果と同じ入口に渡すため，
  「ルート → 交差点リスト → MasterServerでエッジサーバ取得 → ETAでJOIN」の流れはそのまま使える
- **位置**: SimBridge が割り当てた車両の位置（緯度経度）・速度・進行方向を `SIM_LOCATION` で1秒ごとに送る．アプリはGPS・SIM走行の代わりにこの位置を使い，P2Pでも送る．
  位置は車の中心（SUMOの車両位置は車の先端なので，車長の半分だけ後ろにずらす．スマホは車内にあるため．仮想クライアントも同じ）
- **乗り換え**: 車が目的地に着くと `SIM_END` を送り，アプリはJOIN中の交差点から離脱する．SimBridge は次に出発する車を割り当てる（実験中ずっとどこかの車として参加し続ける）
- **時間同期**: Android側は実時間で通信するため，SimBridgeはSUMOを実時間に合わせて0.5秒ずつ進める（`--speed` で動作確認用に速くできる）
- **車両制御（システム経由，`sim_bridge.py --control system`）**: 危険情報を本システムの経路（交差点グループ＋P2P）で届け，SUMO の車を減速させる
  1. SUMOの車が急停止する（シナリオの急停止イベント．通信なしの比較と同じ車・同じ場所）
  2. その車（仮想クライアントまたは実機）が，**参加中の交差点グループのメンバー**へ P2P で危険情報を送る（`SendLocation` の `hazard`，1秒ごと．解消時は active=false）
  3. 受け取った車は，**危険地点が自分のルートの前方にあるか**を判定する（ルートの線から12m以内・向きの差60度以内・自分より先で400m以内）．
     対向車・別の道の車・通過済みの車は何もしない（絞り込み）
  4. 接近中の車だけが `VEHICLE_COMMAND: DECELERATE` を出し，SimBridge がその車の希望速度を 2 m/s² で 5 m/s まで下げる．
     解消（active=false，または3秒間届かない）で `RESUME`
  - 仮想クライアントは3〜4をブリッジ内で行い，実機はアプリ（`RouteGeometry`・`AppController.onHazardReceived`）が判定して `VEHICLE_COMMAND` を送る．実機には状態カードに警告を出す
  - 急停止した車がどのグループにも参加していない場合（τ が小さく，交差点の手前で止まったときなど）は情報を送れない．これは本システムの特性として記録する（`HAZARD_NOT_IN_GROUP`）

### 6.4 VEHICLE_COMMAND の種類

| command | 内容 | トリガー | TraCIでの実現 |
|---------|------|---------|--------------|
| DECELERATE | 目標速度（既定 5 m/s）まで減速 | 前方の急停止の危険情報を受信し，接近中と判定 | 速度の上限 `vehicle.setMaxSpeed` を，今の速度から 2 m/s² ずつ下げる（車間は車両モデルIDMが保つ．速度を直接指定すると車間が保たれず追突するため） |
| RESUME | 通常速度に復帰 | 危険情報の解消（active=false または3秒間届かない） | `vehicle.setMaxSpeed(id, 元の値)` |
| HOLD_SPEED | 現在速度を維持（未実装） | 前方渋滞継続中 | — |

### 6.5 シナリオ設計の注意点

- SUMOの標準の車両モデルは衝突しないよう安全側に動くため，そのままではヒヤリハットが発生しにくい．
  反応時間（`actionStepLength`）・車ごとの希望速度の違い（`speedFactor`）や，前方車両の急停止イベントを設定し（車両モデルは IDM），**V2Vの有無で差が出る危険場面を含むシナリオ**を用意する
- 同じシナリオ・同じ乱数シードで「V2Vあり」「V2Vなし」を実行して比較する
- エリア全体の指標は急停止と関係のない減速が大半を占め，通知の有無で差が出ない．
  通知の評価は「急停止した車に実際に後ろから近づいた車」だけを取り出して行う（`followers.csv`．届いた割合・届いた時点の距離・後続車の減速度）．
  急停止は後続車がいるときだけ起こす（`--hazard-rule follower`）．結果と詳しい説明は
  [SimBridge/README.md](G-LocON_V2V_Server/SimBridge/README.md) の「急停止の通知の評価」

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
| 1 | 長方形エリアの地図をSUMO用に変換し，全交差点から交通量の多い順にエッジサーバを配置，エリア内を自由に走る交通流（シナリオ）を作成 ✅ |
| 2 | SimBridge（実時間ブリッジ・仮想クライアント・サーバ一括起動）とアプリのSUMOモード ✅ |
| 3 | モードA：SUMO車両1台をスマホ1台に対応させ，実際の通信で動作確認 ✅（実機1台．3台同時は未実施） |
| 4 | VEHICLE_COMMANDによる双方向制御 ✅（仮想クライアントどうしはPC上の試験で確認済み．実機側は実装済み・実機での確認待ち） |
| 5 | モードB：全車両を仮想クライアントとしてエッジサーバに参加させ，実機1台で目視確認 ✅ |
| 6 | 評価（通常G-LocONとの比較が主，V2Vあり／なしは補助）．各1回の試行（EXPERIMENTS.md E4〜E13）まで済み．条件を固定した繰り返し実行はこれから（[EVALUATION.md](G-LocON_V2V_Server/SimBridge/EVALUATION.md)）．実機での通信の実測用のログは未実装 |

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
| 通常G-LocON | 自車位置中心の距離ベースP2P（元の既定: 半径100m，約2秒ごとに問い合わせ） | 制御メッセージ数・判断の余裕を持って接続・無駄な接続・つなぎ直し（7.5） |
| **本システム（G-LocON V2V）** | 交差点中心ETAベースP2P | 上記すべての改善量 |

**通常G-LocONとの比較（方式そのもの）**: 同じ SUMO の走行の上で接続相手の決め方だけを比べる（`compare_schemes.py`）．
各1回の試行の結果（EXPERIMENTS.md E12・E13）では，本システムは制御メッセージが約1/3，エッジサーバ交差点で判断の余裕を持ってつながっていた割合が
74〜97%（従来 50〜92%），ルートが交わらない相手との接続が 0%．測位誤差（5m）を入れると従来方式は境目でのつなぎ直しが増え，本システムは変わらない．
エッジサーバの無い場所でのすれ違いは拾わない（狙いの違い）．数値は繰り返し実行で確定させる．

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

### 7.5 主にする指標（2026/10/05 見直し）

本システムは「交通量・事故の多い交差点でだけ，事前に，必要な相手とつながる」方式で，常に周辺とつながる従来G-LocONとは狙いが違う．
そのため，すれ違い全部ではなく，**狙った交差点で判断の余裕を持ってつながれているか**と，負荷・無駄・安定で比べる．
結果は [EXPERIMENTS.md](EXPERIMENTS.md)（E12・E13）．「計算」は SUMO 上の方式の比較（`compare_schemes.py`）．
正式評価（繰り返し実行）で表・グラフにする指標は ★ を付けた6つ（`eval_report.py`）．

| 分類 | 指標 | 定義 | 7.2 との対応 | どこで測るか |
|---|---|---|---|---|
| **負荷** | ★接続数 | 1台が同時につながっている相手の数（平均・95%） | スケーラビリティ | 計算 |
| **負荷** | ★制御メッセージ数 | サーバとの制御メッセージ（1台1分あたり） | シグナリングオーバヘッド | 計算（実測で確認） |
| **狙った交差点** | ★判断の余裕を持って接続 | エッジサーバ交差点で出会った2台（5秒以内に続けて通過）が，2台とも「必要な余裕」より前からつながっていた割合 | 事前接続率・接近前余裕時間 | 計算 |
| **狙った交差点** | 何秒前から接続 | 出会う何秒前からつながっていたか（中央値），10秒以上前の割合 | 接近前余裕時間 | 計算 |
| **無駄** | ★交わらない相手との接続 | ルートが1か所も交わらない相手との接続の割合 | グループ粒度の適切性 | 計算 |
| **無駄** | 無駄な接続 | ルートが交わらない，または接続の間に一度も50m以内に近づかなかった接続の割合・回数 | グループ粒度の適切性 | 計算 |
| **安定** | ★つなぎ直し A（境目の出入り） | 同じ2台のつなぎ直しのうち，切れてから10秒以内に，2台の距離の変化が50m未満のままつながり直したもの（1台1分あたり）．同じ2台で2回以上繰り返した組の割合も見る | 再接続回数 | 計算 |
| **安定** | つなぎ直し B（離れてからの再会） | A 以外（10秒を超えて切れていた，または距離が50m以上変わった．信号で追いつく，別の道に分かれた後に再び出会う，など） | 再接続回数 | 計算 |
| **安定** | ★短い接続 | 10秒以下で切れた接続の割合 | 接続持続時間 | 計算 |
| 条件付き | 交差点の近くで同じ向きの車と接続 | エッジサーバ交差点から100m以内で起きた，同じ向きの2台のすれ違いで，つながっていた割合 | グループ参加率 | 計算 |
| 通信の実測 | P2P確立までの時間 | JOIN送信から最初の位置が届くまで | グループ形成遅延 | 実機 |
| 通信の実測 | 遅延・情報の鮮度・届いた割合 | 位置情報の送受信の記録から | エンドツーエンド遅延・AoI・PDR | 実機 |
| 参考 | すれ違い全部 | 距離が初めて30m未満になった2台がつながっていた割合（交差・対向・同じ向き） | — | 計算（従来方式は常に周辺とつながるので高くて当然．狙いの違いとして示す） |
| 補助 | 急停止の通知・連鎖した急ブレーキ | 7.1 | TTC・急停止連鎖台数 | 計算＋実サーバ |

- **必要な余裕**: 国土交通省「通信利用型運転支援システムのガイドライン」（平成23年3月）の値を使う．
  情報提供から運転者の反応まで 3.7秒＋システム遅延 0.3秒＋送信間隔 0.1秒＝4.1秒，その後 2.0 m/s² で減速．
  必要な余裕 = 4.1秒 + 速度 ÷ 2.0 m/s²（時速30kmで約8秒，40kmで約10秒，60kmで約12秒）．この式はガイドラインの値を組み合わせたもので，
  ガイドラインが余裕の秒数を直接定めているわけではない．
- 従来G-LocONの条件は元のプログラム（`G-LocON_2024`）に合わせる: 検索半径100m（比較では 100/150/200m），位置の更新2回ごと（約2秒）に問い合わせ．
- 「交わらない相手との接続」は，本システムでは同じ交差点を通る車どうししかつながらないため定義上 0% になる．
- エッジサーバを置く交差点は交通量で選んでいる．事故の多さは使っていない（どこに置くかは今後の課題）．
- NATホールパンチング成功率・ハンドオーバ遅延は，同じLAN内の実験では測れない（今後の課題）．
- **測位誤差**: SUMO の車の位置は誤差ゼロなので，そのままでは従来方式の円の境目での出入りが起きない（E13）．
  接続の判断に使う位置にだけ誤差を加える設定（`--gps-noise`，`--gps-corr`）を用意し，正式評価では「誤差なし」と「5m（10秒ほどかけて変わる）」の2通りで比べる．
  誤差の大きさ・変わり方は仮定で，実機では測っていない．
- **正式評価の条件**: 方式（本システム，従来 100・150・200m）× 地図2つ × 交通量3段階 × 測位誤差2通り × 乱数5回．
  本システムの τ・ρ・δ は1つずつ変えて別に調べる．手順は [EVALUATION.md](G-LocON_V2V_Server/SimBridge/EVALUATION.md)．

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
   ※ `edge_servers.csv` にはルート上の全交差点を記載しているが，EdgeServerを設置する3か所（ES1〜ES3）以外は `#` で無効化しているため3件となる（9.6参照）．

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

### 8.2 サーバの一括起動（IntelliJ 不要）

> **おすすめ: デスクトップから起動する**
> 毎回フォルダを開いてコマンドを打つ代わりに，デスクトップの起動ファイルをダブルクリックするだけで動かせる．
> 初回だけ `G-LocON_V2V_Server/SimBridge/make_desktop_launchers.bat` をダブルクリックすると，デスクトップに次の起動ファイルができる
> （リポジトリの場所を移したときも，もう一度実行する）．
> 神作のPCには同じ内容の日本語名の起動ファイル（`GLocON_1_サーバ起動.bat`，`GLocON_2_SUMO_実機1台と仮想車両.bat` など）を置いている．
>
> | デスクトップの起動ファイル | 内容 |
> |---|---|
> | `GLocON_1_servers.bat`（`GLocON_1_サーバ起動.bat`） | サーバ一式（SUMO用） |
> | `GLocON_1_servers_fixed_route.bat` | サーバ一式（固定ルート用，評価①） |
> | `GLocON_2_sumo_phone1_virtual.bat` | SUMO＋実機1台＋仮想車両（評価③．ふだんの確認はこれ） |
> | `GLocON_2_sumo_phone1_virtual_control.bat` | 上に車両制御を加えたもの（急停止が起き，危険情報がグループ経由で届く．警告の確認用） |
> | `GLocON_2_sumo_phones3.bat` | SUMO＋実機3台（評価②） |
> | `GLocON_2_sumo_pc_only.bat` | スマホなし・PCだけ |
>
> **使い方**: 番号の順に「1 → 2」とダブルクリックする（サーバを先に起動する）．
> 終了は各ウィンドウで Ctrl+C（「バッチ ジョブを終了しますか」には Y）．ウィンドウは結果を読めるよう終了後も残る．
> コマンドで起動したサーバが残っていると「起動できません: 次のポートが既に使われています」と出るので，先に止める．

**ダブルクリックで起動する（フォルダから）**: `G-LocON_V2V_Server/SimBridge/` にあるバッチファイルを使えば，フォルダの移動やコマンドの入力は要らない．
デスクトップの起動ファイルは，これらを呼び出しているだけ．

| ファイル | 内容 |
|---|---|
| `run_servers.bat` | サーバ一式（STUN・MasterServer・SUMO用エッジサーバ20か所） |
| `run_servers_fixed_route.bat` | 固定ルート用（評価①: ES1〜ES3）のサーバ一式 |
| `run_bridge_A_phones3.bat` | 評価②: SUMO＋実機3台（sumo-gui あり） |
| `run_bridge_B_virtual.bat` | 評価③: SUMO＋仮想クライアント＋実機1台（sumo-gui あり，実機の車を追従） |
| `run_bridge_C_control.bat` | 評価③＋車両制御（`--control system --hazard-rule follower`．急停止が起き，接近中の車だけ減速．実機には警告が出る） |
| `run_bridge_pc_only.bat` | スマホなし・PCだけで試す |
| `make_desktop_launchers.bat` | 上の起動ファイルをデスクトップに作る（初回に1回） |

τ・δ などを変えるときは，バッチファイルをコピーして最後の `python ...` の行に引数（`--join-eta 30` など）を足す．

**コマンドで起動する**場合は次のとおり．

8.1 の個別起動の代わりに，`G-LocON_V2V_Server/SimBridge/start_servers.py` で STUN・MasterServer・エッジサーバをまとめて起動できる（事前に IntelliJ で **ビルド → プロジェクトのビルド** をしておくこと）．

```
cd G-LocON_V2V_Server\SimBridge
python start_servers.py --stun                                      # SUMO用: scenario/edge_servers.csv の20か所
python start_servers.py --stun --csv ..\MasterServer\edge_servers.csv   # 固定ルート用（評価①）: ES1〜ES3 の3か所
```

- ウィンドウに JOIN / LEAVE が時刻・ポート付きで流れる（`--show all` で全出力，`--show none` で非表示）．
  スマホで「開始」を押すと `[STUNServer] スマホが接続（STUN）: IP>> ...` が出る（出なければスマホの通信がPCに届いていない → 9.2）
- 全出力はサーバごとに `SimBridge/out/servers/EdgeServer_<ポート>.log`，`MasterServer.log`，`STUNServer.log` に時刻付きで保存される．
  1つのサーバだけ追う場合は PowerShell で `Get-Content out\servers\EdgeServer_55600.log -Wait -Tail 20`
- Ctrl+C で全サーバを終了する

---

### 8.3 検証の手順

PC側の起動は，下の手順のコマンドの代わりに **8.2 のデスクトップ起動ファイル**を使ってもよい
（評価①: `GLocON_1_servers_fixed_route`，評価②: `GLocON_1_servers` → `GLocON_2_sumo_phones3`，評価③: `GLocON_1_servers` → `GLocON_2_sumo_phone1_virtual`）．

#### 初回の準備（PC・スマホ）

| 項目 | 内容 |
|---|---|
| PC | JDK 17，Python 3（`cd G-LocON_V2V_Server\SimBridge` → `pip install -r requirements.txt` で SUMO も入る），IntelliJ でサーバをビルド |
| ネットワーク | PCのモバイルホットスポットをオン（「省電力」はオフ），スマホをつなぐ．ファイアウォールでUDP 55554〜55700 を許可（9.1）．使える通信手段は 11 |
| IPアドレス | アプリの `MainActivity.SERVER_IP`・`AppController.MASTER_SERVER_IP` を PC のIP（ホットスポットなら `192.168.137.1`）に合わせる（9.3） |
| スマホ | Android Studio でアプリをビルドして入れる（認識しないとき 9.11） |
| ターミナル | エクスプローラーで `G-LocON_V2V_Server\SimBridge` を開き，アドレス欄に `powershell` と入力して Enter すると，そのフォルダで PowerShell が開く |

#### アプリの画面

| 場所 | 内容 |
|---|---|
| 上（開始前） | Peer ID の入力と「開始」．**端末ごとに別の Peer ID**（phone1 など）にする |
| 上（開始後） | 状態カード: 1行目=走行モード（GPS / 仮想走行 / SUMO車両），2行目=参加中のグループ数・P2Pでつながっている車（実機/仮想），3行目=参加タイミング τ・参加円 ρ・離脱円 δ．**カードをタップ（右端の設定アイコン）で τ・ρ・δ を候補から順に選ぶ**（SUMOモードでは sim_bridge.py の値） |
| 右 | コンパス（N↑ 北が上 → H↑ 進行方向が上 → 固定 地図を動かさない）とズーム |
| 下 | 他の車の表示（P2P / 全車両 / 実機 / なし）と，SUMO・SIM・仮想位置・目的地・終了 |
| 地図 | 六角形 = エッジサーバ交差点（JOIN中は緑），円 = 離脱円（半径 δ），青い枠線の円 = 参加円（半径 ρ），ピン = P2Pでつながった車（実機は赤・緑，仮想は灰色．10秒届かないと消える），小さい矢印 = つながっていない車（全車両・実機表示のとき） |

#### 評価① 実機だけ（固定ルート，SUMOなし）

1. PC: `python start_servers.py --stun --csv ..\MasterServer\edge_servers.csv`（ES1〜ES3）
2. 各スマホ: Peer ID を入れて「開始」 → 状態カードが「現在地（GPS）」になり，地図が現在地に移る
3. 状態カードをタップして τ・ρ・δ を選ぶ（**全端末で同じ値にする**）
4. 「目的地」 → 緯度・経度を入れて「設定」（既定 35.949066, 139.640614） → 「ルート取得完了」と出て，ルート・エッジサーバ交差点・離脱円が出る
5. そのまま走行する（GPS）．机上で試すときは「仮想位置」→「SIM」でルート上を10m/sで自動走行（9.8）
6. 確認すること:
   - 交差点の手前（ETA < τ，または参加円の中）で六角形と円が緑になり，状態カードの「グループ」が増える．サーバのウィンドウに `JOIN:` が出る
   - 交差点を通過して離脱円の外に出ると灰色に戻り，`LEAVE:` が出る
   - 同じ交差点グループに入った他のスマホがピンで出る（状態カードの「つながっている車 実機n」）
7. 「終了」で終了（サーバからも離脱する）

#### 評価② SUMO＋実機3台（モードA: 実機どうしの通信）

1. PC ターミナル1: `python start_servers.py --stun`
2. PC ターミナル2: `python sim_bridge.py --phones 3 --gui`（`--gui` を付けると sumo-gui が開く）
3. 各スマホ: 「開始」 → 「SUMO」 → 状態カードが「SUMO車両 vXX に乗車（交差点 n）」になり，その車の位置で走り出す．
   車が目的地に着くと自動で次の車に乗り換える
4. 確認すること:
   - sumo-gui では実機が乗っている車が紫（紫の円で囲む）
   - 実機どうしが同じ交差点グループに入ると，互いの地図にピンが出る（「全車両」表示にすると，つながっていない実機は青の矢印）
5. 終了: ターミナル2で Ctrl+C（全ての車が着くと自動で終わる）．記録は `SimBridge/out/live_<日時>_<制御>_t<τ>_j<ρ>_d<δ>/`

#### 評価③ SUMO＋仮想クライアント＋実機1台（モードB: 多数車両のグルーピング・サーバ負荷）

1. PC ターミナル1: `python start_servers.py --stun`
2. PC ターミナル2: `python sim_bridge.py --phones 1 --virtual --gui --follow-phone`
   （`--follow-phone`: sumo-gui の画面が実機の車を追いかける）
3. スマホ: 「開始」 → 「SUMO」
4. 確認すること:
   - sumo-gui で車がグループ（交差点）ごとの色に変わる（交差点の印・離脱円と同じ色，未参加は灰色）
   - スマホの地図に同じグループの仮想車両が灰色のピンで出る．「全車両」にすると，グループ外の車も矢印で出て違いが分かる
   - 終了時に出る集計（`summary.txt`）: JOIN の返信率，JOIN 応答時間，グループ一覧の一致率（100% が正常）
5. パラメータを変える: `--join-eta 15/30/45`（τ），`--join-dist 0/50/100/150`（ρ），`--leave-dist 30/60/100/150`（δ）．スマホにも同じ値が送られる
6. 実機には，エッジサーバ交差点を3か所以上通る車が割り当てられる（`--min-es-on-route`，既定3）

#### PCだけで試す（スマホなし）

```
python start_servers.py
python sim_bridge.py --phones 0 --virtual --gui --local
```

#### 従来G-LocONとの比較・正式評価（通信なしの計算．スマホ・サーバ不要）

[SimBridge/EVALUATION.md](G-LocON_V2V_Server/SimBridge/EVALUATION.md)（`eval_run.py` で条件を繰り返し実行 → `eval_report.py` で表・グラフ）

#### 通信なしの比較（V2Vなし／理想V2V，交通への影響）

[SimBridge/README.md](G-LocON_V2V_Server/SimBridge/README.md) の「使い方」（`run_scenario.py --mode none / ideal` → `summarize.py`）

---

### 8.4 結果・ログの場所

| 何の記録 | 場所 | 内容 |
|---|---|---|
| サーバ | `SimBridge/out/servers/*.log` | 各サーバの出力（JOIN/LEAVE，STUN の受信など，時刻付き） |
| SimBridge（評価②③） | `SimBridge/out/live_<日時>_<制御>_t<τ>_j<ρ>_d<δ>/` | `events.csv`（JOIN/LEAVE・割り当て等の時系列），`consistency.csv`（グループ一覧の比較），`summary.txt`（集計），`tripinfo.xml`（SUMOの車ごとの走行記録） |
| 通信なしの比較 | `SimBridge/out/<mode>_s<seed>[_t<τ>_d<δ>]/`，`out/summary.csv` | SUMO の出力（急接近・急ブレーキ・走行時間）と比較表 |
| 方式の比較（試行） | `SimBridge/results/20261004_compare/`，`results/20261005_noise/` | `compare_schemes.py` の結果（E4〜E13．方式ごとの指標） |
| 正式評価 | `SimBridge/results/eval/<計画>/`，`…/report/` | 1実行ごとの csv と，集計した表（`summary.csv`・`.xlsx`・`.md`）・グラフ（`fig_*.png`） |
| アプリ | スマホの `Android/data/com.example.test_g_locon/files/` | `join_log.csv`（JOIN/LEAVE の時刻・ETA・距離），`p2p_log.csv`．PCへは Android Studio の Device Explorer か `adb pull /sdcard/Android/data/com.example.test_g_locon/files/` |

---

## 9. トラブルシューティング

### 9.1 Windowsファイアウォールによる通信ブロック

**症状**: MasterServerが起動しているにもかかわらずAndroid側で `MasterServerClient エラー: Poll timed out` が繰り返し出力され，MasterServerコンソールに受信ログが出ない．

**原因**: Windowsファイアウォールが該当ポートへのUDP通信をブロックしている．

**対処**: PowerShell（**管理者として実行**）で以下を実行する．

```powershell
netsh advfirewall firewall add rule name="G-LocON UDP IN" protocol=UDP dir=in localport=55554-55700 action=allow profile=any
```

`OK` と表示されれば設定完了．55554〜55700 には STUN・Signaling・MasterServer・全エッジサーバ（55600〜）・SimBridge（55700）が含まれる．
以前の範囲（55554-55639）で作った場合は，同じ名前で作り直すか，範囲を変更する（`netsh advfirewall firewall set rule name="G-LocON UDP IN" new localport=55554-55700`）．

> **重要**: `profile=any` を必ず付けること．省略するとモバイルホットスポット・テザリング経由の接続（プロファイル: パブリック）でブロックされる．

---

### 9.2 「開始」を押しても地図が現在地に移らない

**症状**: 「開始」後，状態カードが「サーバに接続できません」のまま，地図が初期位置のまま動かない．

**原因**: 位置の取得（GPS）は STUN サーバから返事が来た後に始まる．スマホの通信がPCに届いていないと現在地に移らない．
よくあるのは，**PCのモバイルホットスポットが自動でオフになり**，スマホがモバイル通信や別のWi-Fiに切り替わっている場合
（Windows は端末がつながっていない状態が続くとホットスポットを自動で切る）．

**対処**: PCの「設定 → ネットワークとインターネット → モバイルホットスポット」でオンにし，スマホをつなぎ直す．
同じ画面の「省電力（デバイスが接続されていないときにホットスポットをオフにする）」をオフにしておくと再発しない．
`start_servers.py --stun` で STUN が起動していることも確認する．アプリは20秒ごとに送り直すので，直せば再起動しなくてもつながる．

---

### 9.3 ネットワーク・IPアドレス設定

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

### 9.4 iPhoneテザリングでスマホ→PCの通信が届かない

**症状**: iPhoneのテザリングにPCとスマホをつなぐと，スマホ→PC（サーバ）へのUDP通信が届かないことがあった．

**原因（推定）**: iPhoneのテザリングは端末どうしの通信を遮断しない（既存G-LocONの実験で，iPhoneテザリングにPC＋スマホ3台をつないで端末間のP2Pを確認済み）．
届かなかったのは，Windowsがテザリングのネットワークを「パブリック」と判断し，ファイアウォールの許可ルールが効いていなかった可能性が高い．

**対処**: ファイアウォールのルールに `profile=any` を付ける（9.1）．それでも届かなければ，PCのモバイルホットスポットを使う．

| 設定箇所 | 値 |
|---------|-----|
| `MainActivity.java` の `SERVER_IP` | PCのIP（iPhoneテザリングなら `ipconfig` で確認．172.20.10.x），PCホットスポットなら `192.168.137.1` |
| `AppController.java` の `MASTER_SERVER_IP` | 同上 |
| `edge_servers.csv` の ip フィールド | 同上 |

---

### 9.5 edge_servers.csv の交差点IDが一致しない

**症状**: MasterServerコンソールに `EdgeServerRegistry: 未登録の交差点ID=XXXXX` が出力され，アプリのマップにマーカーが表示されない．

**原因**: OSRMが返す交差点IDとCSVに登録されているIDが一致していない．

**対処**:
1. Logcatで `OsrmRouteClient[N]: id=XXXXX` のIDを確認する
2. `edge_servers.csv` に不足しているIDを追記する（ポート番号は未使用番号を割り当てる）
3. 追加したポートに対してEdgeServerプロセスを起動する
4. MasterServerを再起動する

> **参考**: MasterServerの近傍検索閾値は30m（`EdgeServerRegistry.java` の `PROXIMITY_THRESHOLD_M`）．CSVのIDとOSRMのIDが30m以内であれば自動的に近傍一致する．

---

### 9.6 テスト時のEdgeServer切り替え方法（コメントアウト方式）

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

### 9.7 SignalingServerへのゴーストピア残留

**症状**: アプリを強制終了・再インストールした後，前回起動時のピア情報が別端末として検出され続ける．

**原因**: DELETEが送られずにサーバ側にエントリが残留する（電池切れ・強制終了時はDELETEを送れない）．

**対処**: SignalingServerにTTL（30秒）を実装済み．SEARCHパケット（5秒ごと送信）をハートビートとして使用し，30秒間SEARCHを受信しなかったエントリは自動削除される．通常は再インストール後30〜40秒で消える．

---

### 9.8 仮想位置モード・SIMの使い方

実験予定地点にいない場合でも，仮想位置を使ってSIMを動かしてV2Vロジックをデバッグできる．

**ボタンの意味**:

| ボタン | 内容 |
|---|---|
| 仮想位置 | 自分の位置を実験場所（コード内 `TEST_LATITUDE/LONGITUDE`）に置く．以後 GPS では位置が変わらない |
| 目的地 | 緯度・経度を入れて「設定」を押すと，今の位置から目的地までのルートを取得する（OSRM．**インターネット接続が必要**） |
| SIM | 取得したルートの上を 10 m/s で自動走行する．ルートが無いときに押すと，手順の案内が出る |
| SUMO | PC の SimBridge につなぎ，SUMO の車として走る．もう一度押すと切断する（サーバとの通信は続く） |

**手順**:
1. Peer ID を入れて「開始」で通信を始める
2. **「仮想位置」**を押す → 地図が仮想座標に移動する
3. **「目的地」**で緯度・経度を入れて「設定」を押し，仮想位置からのルートを取得する
4. **「SIM」**を押す → ルート上を10m/sで自動走行する（ボタンは「停止」に変わる）
5. 交差点に近づくとJOIN（六角形と離脱円が緑），離脱円を出て遠ざかるとLEAVE（灰色）に変わる

> PC のホットスポットにはインターネットが無いため，そのままでは 3 のルート取得ができない．
> PC がインターネットにつながっていて共有されている場合，またはルート取得のときだけ別の回線を使う場合に動く．
> SUMOモードはルートを SimBridge から受け取るので，インターネットは要らない．

**参加タイミング τ・参加円 ρ・離脱円 δ の変更**: 状態カードをタップして順に選ぶ（SUMOを使わないとき）．
SUMOモード中は仮想車両と同じ値にそろえるため PC 側で決める（`sim_bridge.py --join-eta 30 --join-dist 50 --leave-dist 100`）．タップすると今の値が出る．

> **注意**: 仮想位置モード中はGPSによる位置更新・速度計算が無効になる．これはGPS位置と仮想位置が離れている場合に生じる異常速度計算（数万km/h）によるETA誤算を防ぐためである．SIM走行はこの制約の影響を受けない．

### 9.9 サーバが起動しない（ポートが使用中）

**症状**: サーバのログに `BindException` / `Address already in use`，または `start_servers.py` で「起動に失敗したもの」に出る．

**原因**: 前に起動したサーバ（IntelliJ・バッチファイル・前回の `start_servers.py`）が残っていて，同じポートを使っている．
`start_servers.py` は起動前にポートを調べ，使われていれば「起動できません: 次のポートが既に使われています」と出して止まる．
**STUNServer だけ起動に失敗していると，他のサーバは動いていてもスマホが「サーバに接続できません」のままになる**ので注意（「起動に失敗したもの」の表示を確認する）．

**対処**: 残っているサーバを閉じる．見つからない場合は PowerShell で java を一覧し，古いものを終了する．

```powershell
Get-Process java | Select-Object Id, StartTime                  # 起動中の java と起動時刻
Get-Process java | Where-Object { $_.StartTime -lt (Get-Date).Date } | Stop-Process   # 今日より前に起動したものを終了
```

IntelliJ など他の java も終了させてよければ `Get-Process java | Stop-Process` でまとめて終了できる．

### 9.10 start_servers.py で起動に失敗する（ビルド・java）

**症状**: 「起動に失敗したもの: ...」と出る．ログに `ClassNotFoundException` や `'java' は認識されていません` が出る．

**対処**: IntelliJ で **ビルド → プロジェクトのビルド** を実行し `G-LocON_V2V_Server/out/production/` を作る（サーバのコードを更新したら毎回）．
`java -version` で JDK 17 が出ることを確認する（出なければ JDK の `bin` を PATH に入れる）．

### 9.11 Android Studio がスマホを認識しない

**症状**: 実行先にスマホが出ない，または `unauthorized` と表示される．

**対処**:
- スマホの画面に出る「USBデバッグを許可しますか？」で「許可」を押す（出ないときはケーブルを挿し直す）
- 開発者向けオプションで「USBデバッグの許可を取り消す」→ ケーブルを挿し直して再度許可する
- 充電専用ケーブルではデータ通信ができないので，データ通信対応のケーブルを使う

### 9.12 SUMOモードで車が割り当てられない

**症状**: 「SUMO」を押しても状態カードが「SimBridge に接続中…」のまま．

**対処**:
- `sim_bridge.py` が動いているか確認する（先に `start_servers.py`，次に `sim_bridge.py`）
- `--phones` の台数を超えたスマホは受け付けない（モードBは `--phones 1`）．台数を増やして起動し直す
- ファイアウォールで UDP 55700 が許可されているか確認する（9.1）
- 車の割り当ては，エッジサーバ交差点を通る車が出発したとき．起動直後は数秒〜十数秒かかる

### 9.13 sumo-gui の表示が標準のまま・エッジサーバの印が見えない

**症状**: 車が小さく色分けが見えない，画面上部の表示方式が「standard」のまま．

**原因**: `SimBridge/gui_settings.xml` が読み込まれていない．このファイルに XML のコメント（`<!-- -->`）があると SUMO が読み込まない．

**対処**: `gui_settings.xml` にコメントを書かない．画面上部の表示方式の欄で「G-LocON」を選ぶ．
エッジサーバの印（半径15mの円）と離脱円は拡大・縮小しても表示される．

### 9.14 sumo-gui で実機が乗っている車が見つからない

**対処**: 実機が乗っている車は紫で，紫の円で囲まれる．`sim_bridge.py` に `--follow-phone` を付けると画面がその車を追いかける（乗り換えても追従）．
車を右クリック →「Show Parameter」の `glocon.phone` に端末名が出る．

### 9.15 スマホがPCのホットスポットから自宅Wi-Fiに戻ってしまう

**症状**: ホットスポットは見えるが，つないでもすぐ切れて別のWi-Fi（自宅・大学）やモバイル通信に切り替わる．サーバ側には接続の記録が出ない（`STUNServer.log` に「スマホが接続」の行が増えない）．

**原因**: PCのホットスポットにはインターネットが無いため，スマホが「使えないWi-Fi」と判断して自動で切り替える．

**対処**（スマホ側，実験中だけ）:
1. 自宅Wi-Fiの設定で「自動接続」をオフにする（最も確実）
2. ホットスポットにつないだとき「インターネットに接続していません．接続を維持しますか」と出たら「はい」（今後表示しない）
3. モバイル通信をオフにする
4. 直らないときはPC側: 「モバイル ホットスポット」の「省電力」をオフ，帯域を 2.4GHz にする

### 9.16 アプリをスマホに入れられない（`device '…' not found`）

**症状**: Android Studio の端末欄に機種名は出るが，▶ を押すと `Error running 'app': device '…' not found` となり，端末欄が「No Devices」に戻る．

**原因**: USBケーブルの不良．短い命令は通るが，アプリ本体（数MB）を送ると接続が切れる（充電はできるが通信が不安定なケーブル）．
古いアプリが入っていることが原因の場合は，このエラーではなく `INSTALL_FAILED_UPDATE_INCOMPATIBLE` になる．

**対処**: ケーブルを替える（他の端末で入れられたケーブルを使う）．PC側の差し込み口も替えてみる．
`adb` は Android Studio の SDK の `platform-tools` フォルダにある（場所はプロジェクトの `local.properties` の `sdk.dir`）．

---

## 10. 評価指標の優先度まとめ

> この表は設計時（2026年7月）のもの．SUMO上の比較で実際に使う指標は 7.5（2026/10/05 見直し）を優先する．
> 下の表のうち，遅延・AoI・PDR・P2P確立までの時間は実機での実測（評価用ログの追加後），NAT関係は今後の課題，交通への影響（TTC など）は補助の扱い．

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

---

## 11. P2P通信とNAT

### 11.1 G-LocONのP2P処理の現状

**① 今の環境（PCのホットスポット）では全部つながるが，NATは越えていない**

```
スマホA ─┐
スマホB ─┼─ PCのホットスポット（192.168.137.x）─ PC（STUN・Master・エッジサーバ）
スマホC ─┘
```

スマホもサーバも**同じネットワークの中で，間にNATが無い**．そのため STUN が返す「グローバルIP」は，実はホットスポット内のIP．

| 処理 | 動いているか | 実際に起きていること |
|---|---|---|
| STUN（自分の外向きアドレスを知る） | ✅ | 返ってくる「外の住所」はローカルIPのまま |
| グループ参加（エッジサーバへのJOIN） | ✅ | 返信がLAN内で直接届く |
| ホールパンチング（お互いに送り合う） | ✅ | 送ってはいるが，壁（NAT）が無いので意味がない |
| P2P（位置を1秒ごとに送る） | ✅ | LAN内で直接届く |

**コードは最後まで通っているが，NATを越えられるかは一度も試されていない**．
同じネットワーク内のP2Pは，既存G-LocONの検証で確認済み．

**② NAT越えが本当に試されるには2つの条件がいる**

| 構成 | サーバがNATの外 | 端末が別々のNATの内側 | NAT越え |
|---|---|---|---|
| A. ホットスポット（今） | ✗ | ✗ | 試されていない |
| B. クラウドのサーバ＋全員同じWi-Fi | ○ | ✗ | 試されていない（同じネットワークと判断し，ローカルIPで直接送る） |
| C. クラウドのサーバ＋端末ごとに別のSIM・回線 | ○ | ○ | **ここで初めて試される** |

サーバとスマホの間は，サーバが「実際に届いたパケットの送信元」へ返信するので，サーバをグローバルIPの場所に置けば，どの通信手段（キャリア・テザリング・学内Wi-Fi）からでもつながる作りになっている
（基盤G-LocONのシグナリングは STUN で得た申告アドレスへ返信していたため，シンメトリックNATでは届かなかった．V2V版で変更）．

**③ まだ残る問題（コードだけでは解決しないもの）**

| 問題 | なぜダメか | 回避策 |
|---|---|---|
| キャリア回線（LTE/5G）は，接続先ごとに外向きのポートが変わるNAT（シンメトリックNAT）であることが多い | STUNで知ったポートと，相手に送るときのポートが違うので，穴あけが失敗する | TURN のような中継サーバ |
| 同じキャリアの端末どうしは，同じグローバルIPに見えることがある | 「同じネットワーク」と判断してローカルIPへ送るが，キャリアの中では端末どうしが直接届かないことが多い | 中継サーバ，または「同じネットワーク」の判定を変える |
| 遅延の計測 | コードはあるが未完成で使っていない | 評価用のログとして別に作る |

※「同じNATの内側ならローカルIPで問題ない」のは**同じWi-Fiルータの中**の場合．**同じキャリア**では当てはまらない可能性が高い．

**④ 研究として気をつけること**

- 評価項目の「P2P確立成功率」「NATホールパンチング所要時間」は，今の環境ではNATが無いため**ほぼ100%・ほぼ0秒**になる
- 方針は次のどちらか
  - (a) 論文で「**同じLAN内での評価**」と明記し，NAT越えは今後の課題とする
  - (b) 加えて**構成C**で小さな確認実験をする（クラウドにサーバを置き，1台をSIMでつないでJOINの返信が届くか → 2台を別SIMでつないで位置が届くか）
- 12月の学会なら (a) が現実的．「NAT越え」を論文でうたうなら (b) もあると安心

### 11.2 通信手段ごとのまとめ

| 通信手段 | 同じネットワーク内の端末どうし | 別のネットワークの端末と | 検証 |
|---|---|---|---|
| モバイルホットスポット（PC） | ○ | － | **確認済み**（既存G-LocON）．今回の実験の標準 |
| Wi-Fi（家庭・研究室のルータ） | ○ | ○（一般的なルータなら） | 同じネットワーク内は**確認済み**（既存G-LocON）．別ネットワークとは未検証 |
| 学内Wi-Fi | △（端末どうしの通信を遮断していることが多い） | △ | 未検証 |
| テザリング | ○（iPhone）／Android は機種による | 親機の回線しだい | iPhoneは**確認済み**（既存G-LocON．PC＋スマホ3台で端末間P2P） |
| キャリア通信（4G/5G） | ×の可能性大（同じキャリアでも端末どうしは直接届かないことが多い） | △（キャリアのNATの種類しだい） | 未検証 |

- 「同じネットワーク内」の列は，同じWi-Fi・同じホットスポット・**同じテザリング**につないだ端末どうし．G-LocON はグローバルIPが同じなのでローカルIPで直接送る．
  そのため**iPhoneテザリングに3台をつなぎ，サーバをグローバルIPに置いた場合も，サーバとの通信・端末どうしとも○**の見込み
  （3台はiPhoneの同じ回線から出るので同じグローバルIPに見え，テザリング内のローカルIPで直接届く．NAT越えは起きない）

### 11.3 用語

| 用語 | 意味 |
|---|---|
| グローバルIP | インターネット上で一意のアドレス．インターネット側から直接届く |
| プライベートIP | 家庭・学内などのネットワークの中だけで使うアドレス（192.168.x.x，10.x.x.x など）．外からは直接届かない |
| NAT | ルータが「中の端末のプライベートIP:ポート」を「ルータのグローバルIP:出口ポート」に置き換えて外へ出す仕組み．対応表を作り，**中から送ったことのある相手からの返事だけ**中へ通す．外から突然来たパケットは捨てる．対応表は使わないと数十秒〜数分で消える |
| STUN | 外にあるサーバに送ると「あなたは外から グローバルIP:出口ポート に見える」と教えてくれる仕組み．自分の外向きのアドレスを知るために使う |
| NATホールパンチング | 2台がSTUNで知ったお互いの外向きアドレスへ**同時に送り合う**．それぞれのNATに「この相手には送った」という記録ができるので，相手からの返事が中へ通るようになる（穴が開く） |

### 11.4 つながる・つながらない理由（比べて理解する）

**① 同じネットワーク内: ホットスポット・同じWi-Fi・iPhoneテザリング（○） と 学内Wi-Fi（×のことが多い）**

- ホットスポットや同じWi-Fiでは，端末どうしがプライベートIPで直接届く．NATを通らないので穴あけも要らない → **つながる**
- iPhoneテザリングも同じ．既存G-LocONの実験（PC＋スマホ3台）で端末どうしのP2Pを確認済み
- 学内Wi-Fi も同じネットワークだが，多くはアクセスポイントが**端末どうしの通信を遮断**している（クライアント分離．盗み見などを防ぐため） → **つながらない**
- つまり×の原因はNATではなく，ネットワークの設定

**② 別のネットワーク: 一般的なルータ（○） と Symmetric NAT（×）**

端末A（プライベート 192.168.1.5:5000）がNATを通って外へ出る例:

```
一般的なルータ（cone型）: 宛先が変わっても出口ポートは同じ
  A → STUN     … 203.0.113.10:40001 として出る   ← STUN が「40001」と教える
  A → 端末B    … 203.0.113.10:40001 として出る   ← STUN で知ったポートと同じ
  B は 40001 へ送る → Aの NAT に「Bへ送った」記録があるので通る → つながる

Symmetric NAT: 宛先ごとに出口ポートが変わる
  A → STUN     … 203.0.113.10:40001 として出る   ← STUN が「40001」と教える
  A → 端末B    … 203.0.113.10:40002 として出る   ← 実際は別のポート
  B は STUN で知った 40001 へ送る → 40001 は「STUN宛て」の穴なので捨てられる → つながらない
```

G-LocON は STUN で知ったポートへ送り，受け取るときもそのポートからのものだけを相手のパケットとして扱う．そのため片方でも Symmetric NAT だとつながらない．

**③ キャリア通信: 家庭のWi-Fi（○） と 同じキャリアの端末どうし（×）**

- キャリアは大量の端末を1つのグローバルIPにまとめる大規模なNAT（CGNAT）を使う．そのため**同じキャリアの端末どうしが同じグローバルIPに見える**ことがある
- G-LocON はグローバルIPが同じなら「同じネットワーク内」と判断してプライベートIPへ送る
  - 家庭のWi-Fi（同じルータの中）: プライベートIPで本当に直接届く → **つながる**
  - キャリア: 同じグローバルIPでも端末どうしは同じネットワークではなく，キャリアのNATも中の端末どうしの折り返しをしないことが多い → **つながらない**
- キャリアのNATが Symmetric 型なら，②の理由で別ネットワークの端末ともつながらない

**④ サーバの置き場所: ホットスポット内（今） と インターネット上**

- 今のサーバ（STUN など）はPCのホットスポット内のプライベートIP（192.168.137.1）にある．ホットスポットにつないだ端末からしか届かない
- キャリア通信の端末からは届かないので，STUN で外向きアドレスを知ることも，グループに入ることもできない → **始まらない**
- 別ネットワークどうしで使うには，サーバをグローバルIPを持つ場所（クラウドなど）に置く

**⑤ つながった後も切れないように（定期送信）**

- NATの対応表は使わないと消えるので，G-LocON は STUN へ20秒ごと，エッジサーバへ15秒ごとに送り，P2Pの相手へは位置を1秒ごとに送っている
- 対応表が消えるまでの時間はNATによって違い，キャリアでは短いことがある（未検証）

**参考: IPv6**

- IPv6 は端末ごとにグローバルなアドレスが付くので，NAT自体が無い（ファイアウォールはある）
- G-LocON は IPv4 だけを使うため，今は使っていない
