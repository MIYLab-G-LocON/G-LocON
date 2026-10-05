# SimBridge（SUMOシミュレーション環境）

G-LocON V2V の評価用シミュレーション環境。設計は上位の README「6. シミュレーション設計（SUMO）」を参照。

**長方形のエリア**を指定し，その中の**全交差点**から**交通量の多い順にエッジサーバ**を選び，
エリア内を**自由に走る車両**が交差点グループを作る。

- **段階1**（シナリオ作成と，V2Vなし／理想V2V のオフライン比較）: `build_net.py` → `select_edge_servers.py` → `make_scenario.py` → `run_scenario.py` → `summarize.py`
- **段階2**（実時間でアプリ・サーバとつなぐ）: `start_servers.py` ＋ `sim_bridge.py`。モードA（実機3台）とモードB（仮想クライアント＋実機1台）
- **方式の比較**（従来G-LocONとの比較。通信なしの計算）: `compare_schemes.py`。条件を決めて繰り返す正式評価は `eval_run.py` → `eval_report.py`（手順は [EVALUATION.md](EVALUATION.md)）

## 準備

```
pip install -r requirements.txt      # eclipse-sumo, traci, sumolib, pyproj, matplotlib, openpyxl（最後の2つは表・グラフ用）
```

`eclipse-sumo` はSUMO本体（sumo, sumo-gui, netconvert など）を含む。公式インストーラで入れたSUMOを使う場合は環境変数 `SUMO_HOME` を設定する。

## 今のシナリオ（2026/10/04 に作り直し）

| | 以前 | 今 |
|---|---|---|
| エリア | 1km四方 | 東西1.8km×南北1.15km（`osm/area.osm` のほぼ全体） |
| 1ルートの長さ（中央値） | 約750 m | 約1.3 km |
| エッジサーバ | ランダムに10か所 | 交通量の多い交差点から20か所，互いに100m以上離す（ポート 55600〜55619） |
| 参加の条件 | ETA < τ | ETA < τ，または参加円（半径 ρ=100m）の中 |
| 離脱円 δ | 60m | 100m |
| 実機に割り当てる車 | 1か所以上通る車 | 3か所以上通る車（`--min-es-on-route`） |

以前は1ルートが短く，グループに1回しか入らない（または1回も入らない）車が多かったため作り直した。
通過判定の半径 20m は新しいシナリオでも足りている（エッジサーバ交差点 2,598回の通過で最接近距離の最大 12.8m）。
実験結果は [実験記録（EXPERIMENTS.md）](../../EXPERIMENTS.md) にまとめている（このファイルには使い方だけを書く）。

## 地図の切り替え（GLOCON_SCENARIO）

| 環境変数 `GLOCON_SCENARIO` | 地図 | フォルダ | 用途 |
|---|---|---|---|
| （指定なし） | 大学周辺 1.8×1.15km。生活道路が中心 | `scenario/` | 実機実験，方式の比較 |
| `arterial` | 3.0×2.7km。国道16号・県道5号を含む。信号44か所 | `scenario_arterial/` | 交通量・速度の高い道路での方式の比較（実サーバ・実機では使っていない） |

```
# PowerShell の例
$env:GLOCON_SCENARIO="arterial"; python build_net.py      # 最初に1回（道路網 area.net.xml は大きいのでリポジトリに入れていない）
$env:GLOCON_SCENARIO="arterial"; python compare_schemes.py --tag arterial
Remove-Item Env:GLOCON_SCENARIO      # 元に戻す
```

`arterial` の作り方: `build_net.py` → `make_scenario.py --trips-only --period 0.6 --min-distance 1500 --fringe-factor 10`
→ `select_edge_servers.py --count 40` → `make_scenario.py --period 0.6 --min-distance 1500 --fringe-factor 10`。
地図データは `osm/arterial.osm`（道路だけを OpenStreetMap から取得），速度の既定は `osm/japan_speeds.typ.xml`。

## 使い方

```
python build_net.py              # エリア（既定: 東西1.8km×南北1.15km）を切り出し，全交差点を洗い出す
python make_scenario.py --trips-only   # 交通流だけ作る（エッジサーバを交通量で選ぶため）
python select_edge_servers.py    # 交通量の多い交差点から順にエッジサーバを置く（既定: 20か所，100m以上離す）
python make_scenario.py          # エリア内を自由に走る交通流と急停止イベントを作る
python run_scenario.py --mode none  --seed 1
python run_scenario.py --mode ideal --seed 1                     # τ=15秒・ρ=100m・δ=100m（既定）→ out/ideal_s1_t15_j100_d100
python run_scenario.py --mode ideal --seed 1 --leave-dist 30     # δ を変えて比較（30 / 60 / 100）
python run_scenario.py --mode ideal --seed 1 --join-eta 30       # τ を変えて比較（15 / 30 / 45）
python summarize.py              # out/summary.csv に比較表
python run_scenario.py --mode none  --hazard-rule follower       # 後続車がいるときだけ急停止させる → out/none_s1_hf
python run_scenario.py --mode ideal --hazard-rule follower       # → out/ideal_s1_t15_j100_d100_hf
python run_scenario.py --mode none --gui   # 画面で確認
```

主な引数:

| スクリプト | 引数 | 既定値 | 意味 |
|---|---|---|---|
| build_net.py | `--width` / `--height` / `--center` | 1800 m / 1150 m / 35.9490,139.6485 | エリアの東西・南北の長さと中心（`osm/area.osm` の範囲内であること） |
| select_edge_servers.py | `--count` | 20 | エッジサーバの数 |
| | `--by` | traffic | 選び方．`traffic`=通る車の多い交差点から順（`trips.rou.xml` を数える），`random`=ランダム |
| | `--seed` | 1 | 選び方の乱数（`random` のとき．変えると別の配置になる） |
| | `--min-spacing` | 100 m | エッジサーバどうしの最小距離 |
| | `--move 元=先` | なし | 選んだ交差点を別の交差点に置き換える（数・ポート・sumo-gui の色はそのまま） |
| make_scenario.py | `--period` | 1.5 秒 | 車両の発生間隔（小さいほど交通量が多い） |
| | `--min-distance` | 800 m | 出発地と目的地の最小距離（1ルートの長さ） |
| | `--trips-only` | なし | 交通流だけ作る（急停止イベントは作らない） |
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
| 黒（赤い円で囲む） | 急停止中の車（`--control` を付けたとき。止まっている間） |
| 茶色（減速を始めたときにオレンジの円） | 危険情報を受けて減速中の車（解消すると元の色に戻る） |

離脱円の中にいても灰色の車は，ほとんどが**その交差点を通らない車**（円の中を通る別の道を走っているだけ）。
JOIN は「ルート上のエッジサーバ交差点までのETA < τ」で決まり，円（離脱円 δ）は LEAVE の判定にだけ使う。
（試験: 円の中にいた車のうち，JOIN中 73%，その交差点を通らない 26%，ETA待ち 1%）

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
| `--join-dist` | 100 | 参加円の半径 ρ [m]: 交差点までの直線距離がこれ未満なら ETA に関係なくJOIN（0=なし。評価では 0 / 50 / 100 / 150）。実機にも `SIM_ROUTE` で同じ値を送る |
| `--leave-dist` | 100 | 離脱円の半径 δ [m]（評価では 30 / 60 / 100 / 150）。実機にも `SIM_ROUTE` で同じ値を送る。出力フォルダ名の末尾に `_t<τ>_j<ρ>_d<δ>` が付く |
| `--control` | off | 車両制御．`off`=急停止なし，`none`=急停止あり・通知なし（V2Vなし），`system`=急停止の情報をグループ経由(P2P)で送り，接近中の車だけ減速させる |
| `--hazard-rule` | fixed | 急停止の起こし方．`fixed`=決めた40台が必ず急停止，`follower`=後ろ20〜150mに後続車がいるときだけ急停止（通知の効果を見る設定。出力フォルダ名の末尾に `_hf` が付く）．`run_scenario.py` にも同じ引数がある |
| `--min-es-on-route` | 3 | 実機に割り当てる車の条件（ルートが通るエッジサーバ交差点の数がこれ以上） |
| `--warn-speed` | 5.0 | 減速指示を受けた車の目標速度 [m/s]（2 m/s² で下げる） |
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
| `hazard_log.csv` | （`--control none / system`）急停止（SUDDEN_STOP），危険情報の送信可否（HAZARD_SEND / HAZARD_NOT_IN_GROUP），受信（NOTIFIED: 遅延・接近中か），減速（DECELERATE）と復帰（RESUME） |
| `followers.csv` | （`--control none / system`）急停止した車に後ろから近づいた車（通信とは無関係に SUMO の正解から拾う）と，その車に減速指示が届いたか・届いた時点の距離・最大減速度・30m手前での速度 |
| `ssm.xml`・`fcd.xml`・`collisions.xml` | （`--control none / system`）`run_scenario.py` と同じ SUMO 出力。`python summarize.py none_s1 ideal_s1_t15_d60 live_...` で同じ指標で比べられる |

グループ一覧の比較では，直近2秒以内にJOIN/LEAVEした車は通知が届く途中の可能性があるため除いている。

### 試験結果

PC上での試験結果は [実験記録（EXPERIMENTS.md）](../../EXPERIMENTS.md) の E0・E3 にまとめている。

## 段階1の実行モード（run_scenario.py）

| mode | 内容 | 位置づけ |
|---|---|---|
| `none` | V2Vなし。急停止の情報は誰にも届かない | 下限 |
| `ideal` | 本システムと同じ規則（エッジサーバ交差点へのETA<τ（既定15秒）でJOIN，通過して δ（既定100m）離れたらLEAVE）でグループを作り，急停止を同じグループの後続車へ0.3秒後に通知する。通知を受けた車は希望速度を5 m/s まで 2 m/s² で下げ，停止解消後に戻す。通信の損失は無い | 上限（理想通信） |

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
| `followers.csv` | 急停止した車に後ろから近づいた車（正解）ごとに，減速指示が届いたか・届いた時点の距離と速度・最大減速度・30m手前での速度 | 届いた割合（coverage），届いた時点の余裕，後続車の急制動 |

## 接続相手の決め方の比較（compare_schemes.py）

本システム（進行先の交差点・ETA）と従来G-LocON（自車の周りの距離）で，「誰とつなぐか」の決め方だけを比べる。
同じ SUMO の走行（V2Vなし・急停止あり）の上で全方式を同時に計算するので，車の動きは完全に同じ。
通信の損失・遅延は無いものとし，実際のサーバは使わない（方式そのものの比較）。

```
python compare_schemes.py --etas 15:100:100 --radii 100,150,200      # 本システム（τ:ρ:δ）と従来 100/150/200m → out/compare_s1/schemes.csv
python compare_schemes.py --etas 15:100:100,30:100:100 --tag tau     # 本システムの条件を並べる
python compare_schemes.py --seed 2 --trip-seed 2 --period 1.5        # 乱数を変える（--period を付けると交通流をその場で作る）
python compare_schemes.py --period 0.75 --tag p0.75                  # 交通量を変える（車の発生間隔 [秒]）
python compare_schemes.py --gps-noise 5 --gps-corr 10 --tag gps5     # 測位誤差 5m（10秒ほどかけて変わる）を入れる
python compare_schemes.py --gui                                      # sumo-gui で走行を表示する（説明用）
```

- 従来G-LocON: 各車が約2秒ごと（`--search-period`）にサーバへ問い合わせ，半径 R 以内の車とつながる。
  元のプログラム（`G-LocON_2024`）の既定は R=100m・位置の更新2回ごと。比較では 100/150/200m を並べる。
- 本システム: ETA（直線距離÷速度）< τ，または参加円（半径 ρ）の中で参加。通過済み（20mまで近づいた）・δ 以上離れた・遠ざかっている，で離脱。
  アプリ・`sim_bridge.py` と同じ条件（2026/10/05 にそろえた。それ以前の結果は ETA を道のりで計算，`--eta-by route` で再現できる）。
- 測位誤差（`--gps-noise`）: 接続の判断にだけ誤差つきの位置を使い，評価は正しい位置で行う。新旧どちらにも同じ誤差をかける。
- 「交差点で出会った2台」: 同じ交差点を5秒以内に続けて通った2台。「判断の余裕」より前からつながっていた割合を見る（定義は上位 README 7.5）。
- 制御メッセージの数え方（`compare_schemes.py` の `ctrl`）: 本システムは MasterServer への問い合わせ2通，JOIN 2通＋既存メンバーへの通知，
  LEAVE 1通＋残りのメンバーへの通知，参加中は接続維持（KEEPALIVE）を15秒ごとに1通（2026/10/05 から数える。E13 までは含まない）。
  従来G-LocONは問い合わせ1回2通＋新しい相手1台につき2通。STUN への定期送信（20秒ごと）は両方式に共通なので数えない。
- 出力の列の意味は `compare_schemes.py` の先頭の説明。

結果と考察は [実験記録（EXPERIMENTS.md）](../../EXPERIMENTS.md) の E4〜E13 にまとめている。

## 急停止の通知の評価（followers.csv）

エリア全体の指標（near_miss・hard_brake など）は，急停止と関係のない交差点での減速が大半を占めるため，
通知の有無でほとんど変わらない。そこで，**急停止した車に実際に後ろから近づいた車**だけを取り出して見る。

- 対象の車: 急停止している間に，その車の後ろ 150m 以内（自分のルートに沿った道のり）に入った車。
  通信の結果ではなく SUMO の位置とルートから決めるので，V2Vなし／理想V2V／本システムで同じ基準になる。
  急停止が終わる 3秒前より後に入った車は，反応する必要がないので集計から除く。
- `summarize.py` の列: `followers`（対象の車の数），`foll_warned`・`coverage`（減速指示が届いた数と割合），
  `warn_gap`・`warn_lead`（届いた時点の距離と「距離÷速度」の中央値），
  `foll_decel`・`foll_hard`（最大減速度の平均と，4.5 m/s² を超えた数），`foll_v_near`（30m手前での速度の平均）。
- 急停止は `--hazard-rule follower`（後続車がいるときだけ急停止）で起こす。`fixed` の40件は後続車がいない場合が多く，
  対象の車が20台ほどしか出ない。
- 理想V2V（`--mode ideal`）は，本システムと同じ規則で作った交差点グループを使い，急停止した車と同じグループにいる
  後続車（400m以内）へ，損失なし・一定の遅延で通知し続ける。本システム（`sim_bridge.py --control system`）の比較対象。

結果と考察は [実験記録（EXPERIMENTS.md）](../../EXPERIMENTS.md) の E2・E3 にまとめている。

## 車両モデルと急停止イベント

- 車両モデルは IDM（快適な減速度 3.0 m/s²）。反応の遅れ（`actionStepLength=1.0`秒）と速度のばらつき（`speedFactor`）を持たせている。
  Krauss モデルは普段から最大減速度でブレーキをかけるため「急制動回数」が意味を持たず，採用していない。
- 急停止は，エッジサーバ交差点を通る車からランダムに選び，`scenario/hazards.json` に**車両IDと場所を固定**して書く
  （例: `v120` が交差点の60m手前で 8 m/s² で停止し12秒止まる）。V2Vなし／ありで全く同じ急停止が起きるため，同じシードどうしで対応のある比較ができる。
- `--hazard-rule follower` では，エッジサーバ交差点を通る車すべて（`scenario/hazard_candidates.json`）を候補にし，
  交差点の手前に来たときに後ろ 20〜150m を 5 m/s 以上で走る後続車がいる場合だけ急停止させる
  （同じ交差点では40秒，エリア全体では15秒あける）。
- 減速指示を受けた車は，速度の上限を「今の速度」から 2 m/s² ずつ下げる（`hazard_eval.slow_down_step`）。
  車間は車両モデルが保つ。時刻だけで上限を下げると SUMO が一度に速度を落として急ブレーキになるため，この方法にしている。
- V2Vの通知による減速は TraCI で速度を直接指定せず，希望速度を段階的に下げている。
  速度を直接指定すると車両モデルの安全距離の制御が働かず，かえって追突が起きるため。
