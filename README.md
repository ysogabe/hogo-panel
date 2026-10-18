# Hogo Panel

[hogo-meter](https://github.com/ysogabe/hogo-meter) と連携する、Android 化した Echo Show 5 向けのお世話パネル。

お世話の最終実施時刻・経過時間の確認と記録を、常設のタッチ画面から行うためのアプリを開発します。無操作時には低輝度のフリップ時計を表示する方針です。

## 現在の状態

開発開始用のリポジトリです。既存の Miharashi Panel で得た知見と待機時計の引き継ぎ仕様を保存しています。Android アプリ本体、hogo-meter との通信、APK、ビルド環境はまだ含みません。

## 対象端末

Android 化した Echo Show 5 を想定しています。既存アプリで確認した環境は LineageOS 18.1 / Android 11 / 960×480 です。新しい端末の世代・OS・表示領域・ADB 接続は開発開始時に確認します。標準の Echo Show OS へそのまま APK を導入できるという意味ではありません。

## ドキュメント

- [開発資料の入口](docs/README.md)
- [Echo Show 5 アプリ開発の知見](docs/KNOWLEDGE_HANDOFF.md)
- [消灯時・無操作時の時計表示仕様](docs/IDLE_CLOCK.md)
- [hogo-meter 連携の引き継ぎ](docs/HOGO_METER_INTEGRATION.md)

## 名前

リポジトリ名とフォルダ名は `hogo-panel`。連携元の `hogo-meter` と共通の `hogo` に、常設の確認・操作画面を表す `panel` を組み合わせています。`hogo-meter-show5` は端末名に用途を固定し、`hogo-meter-android` は常設パネルという役割が伝わりにくいため、こちらを採用しました。

## ライセンス

[MIT License](LICENSE)
