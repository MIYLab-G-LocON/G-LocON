# G-LocON Server 2024

2024年度，TechnicalSeminar（技術セミナー）で作成されたG-LocONサーバの改修前バージョン．

## 構成

| モジュール | 役割 |
|---|---|
| SignalingServer | 位置ベースの周辺端末検索（旧G-LocONのシグナリング） |
| STUNServer | グローバルIP/Port取得 |
| VirtualClient | 動作確認用のシミュレーションクライアント |

Eclipseプロジェクト形式（`.classpath` / `.project`）のまま保存されている．IntelliJ IDEAでインポートする場合は各モジュールの`.iml`を使用．

> 注: 元の`README.txt`に「Eclipseにインポートするから動かさないほうがいい」とある通り，元々はEclipseプロジェクトとして相対パス参照される想定で作られている．

2026年度の改修版は[`G-LocON_2026/G-LocON_Server_2026`](../../G-LocON_2026/G-LocON_Server_2026)を参照．

## 起動方法

各モジュールの`StartUp.java`を実行する．依存ライブラリ（`java-json.jar`）は各モジュールの`lib/`に同梱済み．
