# SimBridge（SUMOシミュレーション環境）

G-LocON V2V の評価用シミュレーション環境。設計は上位の README「6. シミュレーション設計（SUMO）」を参照。

**正方形のエリア**を指定し，その中の**全交差点**から**ランダムにエッジサーバ**を選び，
エリア内を**自由に走る車両**が交差点グループを作る。

- **段階1**（シナリオ作成と，V2Vなし／理想V2V のオフライン比較）: `build_net.py` → `select_edge_servers.py` → `make_scenario.py` → `run_scenario.py` → `summarize.py`
- **段階2**（実時間でアプリ・サーバとつなぐ）: `start_servers.py` ＋ `sim_bridge.py`。モードA（実機3台）とモードB（仮想クライアント＋実機1台）

## 準備

```
pip install -r requirements.txt      # eclipse-sumo, traci, sumolib, pyproj
```

`eclipse-sumo` はSUMO本体（sumo, sumo-gui, netconvert など）を含む。公式インストーラで入れたSUMOを使う場合は環境変数 `SUMO_HOME` を設定する。

## 使い方

```
python build_net.py              # エリア（既定: 1km四方）を切り出し，全交差点を洗い出す
python select_edge_servers.py --move 35.95152_139.64821=35.94917_139.64777
                                 # その中からランダムにエッジサーバを選ぶ（既定: 10か所）。
                                 # 今の配置は seed=1 のランダム配置のうち，画面中央上の1か所（水色）を中央の交差点へ移したもの
python make_scenario.py          # エリア内を自由に走る交通流と急停止イベントを作る
python run_scenario.py --mode none  --seed 1
python run_scenario.py --mode ideal --seed 1                     # τ=15秒・δ=60m（既定）→ out/ideal_s1_t15_d60
python run_scenario.py --mode ideal --seed 1 --leave-dist 30     # δ を変えて比較（30 / 60 / 100）
python run_scenario.py --mode ideal --seed 1 --join-eta 30       # τ を変えて比較（15 / 30 / 45）
python summarize.py              # out/summary.csv に比較表
python run_scenario.py --mode none --gui   # 画面で確認
```

主な引数:

| スクリプト | 引数 | 既定値 | 意味 |
|---|---|---|---|
| build_net.py | `--side` / `--center` | 1000 m / 35.9490,139.6485 | 正方形エリアの一辺と中心（`osm/area.osm` の範囲内であること） |
| select_edge_servers.py | `--count` | 10 | エッジサーバの数 |
| | `--seed` | 1 | 選び方の乱数（変えると別の配置になる） |
| | `--min-spacing` | 150 m | エッジサーバどうしの最小距離 |
| | `--move 元=先` | なし | 選んだ交差点を別の交差点に置き換える（数・ポート・sumo-gui の色はそのまま） |
| make_scenario.py | `--period` | 1.5 秒 | 車両の発生間隔（小さいほど交通量が多い） |
| | `--hazards` | 40 | 急停止イベントの数 |

| ファイル | 役割 |
|---|---|
| `osm/area.osm` | 地図（© OpenStreetMap contributors, ODbL）。緯度35.9435〜35.9545，経度139.6380〜139.6590 |
| `osm/service_passenger.typ.xml` | 構内道路（highway=service）を乗用車も通れるようにする設定 |
| `scenario/area_intersections.csv` | エリア内の全交差点（3方向以上に道がつながる地点）。IDはアプリと同じ「緯度5桁_経度5桁」 |
| `scenario/edge_servers.csv` | 選んだエッジサーバ。MasterServer の `edge_servers.csv` と同じ形式（4列目にSUMOの交差点ID） |

## 段階2: アプリ・サーバとつなぐ（sim_bridge.py）

### 準備（初回のみ）

1. `pip install -r requirements.txt`
2. IntelliJ でサーバのプロジェクトをビルドする（`../out/production/` にクラスができる）
3. アプリを Android Studio でビルドし，スマホに入れる（SUMOモード対応版）
4. PCのモバイルホットスポットをオンにし（「省電力」はオフ），スマホをつなぐ。ファイアウォールでUDPの 55554〜55700 を許可する（上位 README 9.1）

### 実行手順

```
# ターミナル1: サーバ一式（MasterServer＋scenario/edge_servers.csv の全エッジサーバ。実機を使うときは --stun も）
python start_servers.py --stun

# ターミナル2: ブリッジ
python sim_bridge.py --phones 3                     # モードA: 実機3台
python sim_bridge.py --phones 1 --virtual --gui     # モードB: 仮想クライアント＋実機1台，PCで色分け表示
python sim_bridge.py --phones 0 --virtual --gui --local   # 実機なし・PCだけでモードBを試す
```

スマホ側: アプリで「開始」→「SUMO」を押す。SimBridge が車を割り当てると状態カードに「SUMO車両 v123 に乗車」と表示され，
その車のルート上の交差点が地図に出て，車の位置で走り始める。車が目的地に着くと次の車に乗り換える。
下の操作パネルで他車両の表示を「P2P / 全車両 / 実機 / なし」から選ぶ。
評価①②③ごとの詳しい手順・確認すること・トラブルシューティングは上位 README の「8.3 検証の手順」「9. トラブルシューティング」を参照。
「P2P」はP2Pでつながった車のピン（実機は赤・緑，仮想車両は灰色），「全車両」はそれに加えてつながっていない車を小さな矢印（灰色=SUMOの車，青=実機）で出す。

### sumo-gui の色（--gui）

車両を右クリック →「Show Parameter」で，その車のルート上のエッジサーバ交差点（`glocon.es_on_route`），
JOIN中の交差点（`glocon.joined`），LEAVE済みの交差点（`glocon.left`）を確認できる。

表示設定は `gui_settings.xml`（画面上部の表示方式の欄で「G-LocON」が選ばれる）。このファイルには XML のコメントを書かないこと（書くと SUMO が設定を読み込まず標準の表示になる）。

| 色 | 意味 |
|---|---|
| 紫（紫の円で囲む） | 実機が乗っている車（右クリック→Show Parameter の `glocon.phone` に端末名） |
| 交差点ごとの色（10色） | その交差点のグループにJOIN中の仮想クライアント（色は `scenario/edge_servers.csv` の行の順。交差点の印と離脱円も同じ色） |
| 灰色 | どのグループにも入っていない車 |

JOIN/LEAVE の判定・アプリへ送る位置は車の中心を使う（SUMOの車両位置は車の先端）。
sumo-gui は見やすさのため車を `vehicle_exaggeration`（8倍，4.5mの車が36m）に拡大して先端から後ろへ描くので，
LEAVE の後の色替えは，**描かれた車の後端が離脱円を出るまで待つ**（表示だけ。LEAVE の送信・events.csv の時刻は変えない）。
そのため画面では，LEAVE から数秒遅れて色が替わる。

### 主な引数

| 引数 | 既定値 | 意味 |
|---|---|---|
| `--phones` | 3 | 受け付ける実機の台数 |
| `--virtual` | なし | 全車両を仮想クライアントとしてエッジサーバに参加させる |
| `--virtual-max` | 300 | 同時に動かす仮想クライアントの上限（WindowsではソケットはPython全体で約500まで） |
| `--join-eta` | 15 | 参加タイミング τ [秒]: 交差点までのETAがこれを下回ったらJOIN（評価では 15 / 30 / 45）。実機にも `SIM_ROUTE` で同じ値を送る |
| `--leave-dist` | 60 | 離脱円の半径 δ [m]（評価では 30 / 60 / 100）。実機にも `SIM_ROUTE` で同じ値を送る。出力フォルダ名の末尾に `_t<τ>_d<δ>` が付く |
| `--others-radius` | 400 | 実機へ1秒ごとに送る「周りの全車両」の範囲 [m]（アプリの「表示:全車両」用。0で送らない） |
| `--follow-phone` | なし | sumo-gui の画面を実機が乗っている車に追従させる（乗り換えても追従） |
| `--vloc-all` | なし | 仮想クライアントどうしにも位置を送る（既定は実機にだけ送り，負荷を抑える） |
| `--speed` | 1.0 | 実時間に対する進み方（実機を使うときは1.0のまま） |
| `--duration` | 0 | シミュレーション時間の上限[秒]（0=最後の車が着くまで） |
| `--local` | なし | PCだけで試す（通信をすべて 127.0.0.1 で行う。ホットスポット不要） |
| `--advertise-ip` | 192.168.137.1 | 仮想クライアントが名乗るIP（スマホから届くPCのIP） |

### 出力（out/live_&lt;日時&gt;/）

| ファイル | 内容 |
|---|---|
| `summary.txt` | JOINの返信率・応答時間，離脱通知の数，グループ一覧の一致率 |
| `events.csv` | 仮想クライアントのJOIN/LEAVE・メンバー受信，実機の割り当て・乗り換え |
| `consistency.csv` | 毎秒，各仮想クライアントのグループ一覧（エッジサーバから受け取ったもの）と正解（その交差点にJOIN中の車）の比較 |
| `tripinfo.xml` | 全車両の所要時間など |

グループ一覧の比較では，直近2秒以内にJOIN/LEAVEした車は通知が届く途中の可能性があるため除いている。

### PC上での試験結果（2026/09/29）

エッジサーバ10台・MasterServerを実際に起動し，仮想クライアント（同時最大約50台）と模擬端末1台で確認した。

- JOIN 152件すべてに返信あり（応答時間 中央値14 ms，95% 26 ms）
- グループ一覧の一致率 100%（3,986回の比較で欠け・余分なし）
- 模擬端末: ルート受信 → MasterServer問い合わせ → JOIN → メンバー一覧（仮想車両を含む）受信 → 仮想車両10台から位置を受信，目的地到着で次の車へ乗り換え
- アプリの SimBridgeClient（Java）も同じブリッジと通信できることを確認（ルート・位置約370回・乗り換え3回）

## 段階1の実行モード（run_scenario.py）

| mode | 内容 | 位置づけ |
|---|---|---|
| `none` | V2Vなし。急停止の情報は誰にも届かない | 下限 |
| `ideal` | 本システムと同じ規則（エッジサーバ交差点へのETA<τ（既定15秒）でJOIN，通過して δ（既定60m）離れたらLEAVE）でグループを作り，急停止を同じグループの後続車へ0.3秒後に通知する。通知を受けた車は希望速度を5 m/s まで 2 m/s² で下げ，停止解消後に戻す。通信の損失は無い | 上限（理想通信） |

各車両は自分のルートが通るエッジサーバ交差点についてだけJOINする（複数の交差点に同時に参加することもある）。
段階3以降は，ここに「実機・アプリを通した実際の通信」によるモードを加え，none・ideal と比較する。

## 出力（out/&lt;mode&gt;_s&lt;seed&gt;/）

| ファイル | 内容 | 対応する評価指標 |
|---|---|---|
| `ssm.xml` | TTC・PET・DRAC | ヒヤリハット率，最小TTC |
| `fcd.xml` | 全車両の位置・速度・加速度（0.5秒ごと） | 急制動回数，急停止連鎖台数，渋滞 |
| `tripinfo.xml` | 所要時間・停止時間・燃料 | 旅行時間，停止時間，燃料消費 |
| `collisions.xml` | 衝突（追突／交差点内） | 衝突件数 |
| `group_log.csv` | 交差点グループの参加・離脱（シミュレータ上の正解） | グループ適合率・再現率の正解データ |
| `hazard_log.csv` | 急停止と通知・減速・復帰 | 警告リードタイム |

## 車両モデルと急停止イベント

- 車両モデルは IDM（快適な減速度 3.0 m/s²）。反応の遅れ（`actionStepLength=1.0`秒）と速度のばらつき（`speedFactor`）を持たせている。
  Krauss モデルは普段から最大減速度でブレーキをかけるため「急制動回数」が意味を持たず，採用していない。
- 急停止は，エッジサーバ交差点を通る車からランダムに選び，`scenario/hazards.json` に**車両IDと場所を固定**して書く
  （例: `v120` が交差点の60m手前で 8 m/s² で停止し12秒止まる）。V2Vなし／ありで全く同じ急停止が起きるため，同じシードどうしで対応のある比較ができる。
- V2Vの通知による減速は TraCI で速度を直接指定せず，希望速度を段階的に下げている。
  速度を直接指定すると車両モデルの安全距離の制御が働かず，かえって追突が起きるため。
