# SimBridge（SUMOシミュレーション環境）

G-LocON V2V の評価用シミュレーション環境。設計は上位の README「6. シミュレーション設計（SUMO）」を参照。

現在は**段階1（地図・シナリオ・実行・集計）**まで。アプリとつなぐブリッジ（段階2）は未実装。

## 準備

```
pip install -r requirements.txt      # eclipse-sumo, traci, sumolib, pyproj
```

`eclipse-sumo` はSUMO本体（sumo, sumo-gui, netconvert など）を含む。公式インストーラで入れたSUMOを使う場合は環境変数 `SUMO_HOME` を設定する。

## 使い方

```
python build_net.py                      # osm/area.osm → scenario/area.net.xml, intersection_map.csv
python make_scenario.py                  # 交通流・急停止イベント・sumocfg を scenario/ に作成
python run_scenario.py --mode none  --seed 1
python run_scenario.py --mode ideal --seed 1
python summarize.py                      # out/summary.csv に比較表
python run_scenario.py --mode none --gui # 画面で確認
```

| ファイル | 役割 |
|---|---|
| `osm/area.osm` | 実験エリアの地図（© OpenStreetMap contributors, ODbL）。3つのエッジサーバ交差点を含む約2km×1.2km |
| `build_net.py` | SUMO道路網へ変換（左側通行）し，`MasterServer/edge_servers.csv` の交差点IDとSUMOの交差点を対応付ける |
| `make_scenario.py` | 実験ルート（アプリと同じ始点→終点）の車列・逆方向・背景交通，急停止イベントを作る |
| `run_scenario.py` | シナリオを実行し，交差点グループの正解ログと評価用の出力を保存する |
| `summarize.py` | サービス指標（ヒヤリハット・急制動・所要時間・燃料など）を集計する |

## 実行モード

| mode | 内容 | 位置づけ |
|---|---|---|
| `none` | V2Vなし。急停止の情報は誰にも届かない | 下限 |
| `ideal` | 本システムと同じ規則（ETA<30秒でJOIN，通過して30m離れたらLEAVE）でグループを作り，急停止を同じグループの後続車へ0.3秒後に通知して減速させる。通信の損失は無い | 上限（理想通信） |

段階3以降は，ここに「実機・アプリを通した実際の通信」によるモードを加え，none・ideal と比較する。

## 出力（out/&lt;mode&gt;_s&lt;seed&gt;/）

| ファイル | 内容 | 対応する評価指標 |
|---|---|---|
| `ssm.xml` | TTC・PET・DRAC | ヒヤリハット率，最小TTC |
| `fcd.xml` | 全車両の位置・速度・加速度（0.5秒ごと） | 急制動回数，急停止連鎖台数，渋滞 |
| `tripinfo.xml` | 所要時間・停止時間・燃料 | 旅行時間，停止時間，燃料消費 |
| `collisions.xml` | 衝突 | 衝突件数 |
| `group_log.csv` | 交差点グループの参加・離脱（シミュレータ上の正解） | グループ適合率・再現率の正解データ |
| `hazard_log.csv` | 急停止と通知・減速・復帰 | 警告リードタイム |

## 車両モデルについて

SUMOの標準モデルは衝突しないよう安全側に動くため，運転のばらつき（`sigma=0.5`）と反応の遅れ（`actionStepLength=1.0`）を持たせ，
エッジサーバ交差点の手前で先行車が急停止するイベント（`scenario/hazards.json`）を入れている。値は `make_scenario.py` の `VTYPES` で変更できる。
