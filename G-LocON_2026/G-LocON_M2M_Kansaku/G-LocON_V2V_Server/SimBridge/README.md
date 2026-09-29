# SimBridge（SUMOシミュレーション環境）

G-LocON V2V の評価用シミュレーション環境。設計は上位の README「6. シミュレーション設計（SUMO）」を参照。

**正方形のエリア**を指定し，その中の**全交差点**から**ランダムにエッジサーバ**を選び，
エリア内を**自由に走る車両**が交差点グループを作る。

現在は段階1（エリア・エッジサーバ・シナリオ・実行・集計）まで。アプリとつなぐブリッジ（段階2）は未実装。

## 準備

```
pip install -r requirements.txt      # eclipse-sumo, traci, sumolib, pyproj
```

`eclipse-sumo` はSUMO本体（sumo, sumo-gui, netconvert など）を含む。公式インストーラで入れたSUMOを使う場合は環境変数 `SUMO_HOME` を設定する。

## 使い方

```
python build_net.py              # エリア（既定: 1km四方）を切り出し，全交差点を洗い出す
python select_edge_servers.py    # その中からランダムにエッジサーバを選ぶ（既定: 10か所）
python make_scenario.py          # エリア内を自由に走る交通流と急停止イベントを作る
python run_scenario.py --mode none  --seed 1
python run_scenario.py --mode ideal --seed 1
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
| make_scenario.py | `--period` | 1.5 秒 | 車両の発生間隔（小さいほど交通量が多い） |
| | `--hazards` | 40 | 急停止イベントの数 |

| ファイル | 役割 |
|---|---|
| `osm/area.osm` | 地図（© OpenStreetMap contributors, ODbL）。緯度35.9435〜35.9545，経度139.6380〜139.6590 |
| `osm/service_passenger.typ.xml` | 構内道路（highway=service）を乗用車も通れるようにする設定 |
| `scenario/area_intersections.csv` | エリア内の全交差点（3方向以上に道がつながる地点）。IDはアプリと同じ「緯度5桁_経度5桁」 |
| `scenario/edge_servers.csv` | 選んだエッジサーバ。MasterServer の `edge_servers.csv` と同じ形式（4列目にSUMOの交差点ID） |

## 実行モード

| mode | 内容 | 位置づけ |
|---|---|---|
| `none` | V2Vなし。急停止の情報は誰にも届かない | 下限 |
| `ideal` | 本システムと同じ規則（エッジサーバ交差点へのETA<30秒でJOIN，通過して30m離れたらLEAVE）でグループを作り，急停止を同じグループの後続車へ0.3秒後に通知する。通知を受けた車は希望速度を5 m/s まで 2 m/s² で下げ，停止解消後に戻す。通信の損失は無い | 上限（理想通信） |

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
