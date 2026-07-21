# G-LocON 2026

2026年度のG-LocON関連研究・基盤改修をまとめたフォルダ。

---

## フォルダ構成

```
G-LocON_2026/
├── G-LocON_Client_2026/     G-LocON基盤クライアント 2026年改修版
├── G-LocON_Server_2026/     G-LocON基盤サーバ 2026年改修版
└── G-LocON_M2M_Kansaku/    V2V応用研究（神作，修士2年，M教授）
```

---

## 基盤改修（2026年）

### G-LocON_Client_2026 / G-LocON_Server_2026

旧実装からの主な変更点：

| 変更内容 | 旧 | 新 |
|---------|----|----|
| 地図ライブラリ | Google Maps SDK | osmdroid（OSS） |
| 非同期処理 | AsyncTask（非推奨） | ExecutorService |
| Java バージョン | Java 8 | Java 17 |

---

## 研究者別研究

### G-LocON_M2M_Kansaku

- **研究者**: 神作（修士2年・M教授）
- **テーマ**: G-LocONのV2V（車車間通信）応用
- **概要**: 交差点を中心としたETAベースのP2Pグループ形成により，交差点通過前に周辺車両との通信を事前確立する
- **詳細**: [G-LocON_M2M_Kansaku/README.md](G-LocON_M2M_Kansaku/README.md)
