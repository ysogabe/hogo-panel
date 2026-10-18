# Hogo Panel

[hogo-meter](https://hogo-meter-intro.akebi.dam-lab.work) と連携する、Android 化した Echo Show 5 向けのお世話パネル。

お世話の最終実施時刻・経過時間の確認と記録を、常設のタッチ画面から行うためのアプリを開発します。無操作時には低輝度のフリップ時計を表示する方針です。

## 現在の状態

最初の段階として、起動直後から低輝度のフリップ時計だけを表示する Android アプリ（PoC）があります。操作画面、待機設定、タッチ復帰、hogo-meter との通信、HOME 指定・自動起動はまだありません。APK は配布せず、[ビルドと端末導入](docs/BUILD.md) の手順で作ります。

この PoC のビルドは確認済みです。実機での表示・Wi-Fi デバッグでの導入は、確認後に別途記録します。

## 対象端末

Android 化した Echo Show 5 を想定しています。既存アプリで確認した環境は LineageOS 18.1 / Android 11 / 960×480 です。新しい端末の世代・OS・表示領域・ADB 接続は開発開始時に確認します。標準の Echo Show OS へそのまま APK を導入できるという意味ではありません。

## ドキュメント

- [開発資料の入口](docs/README.md)
- [ビルドと端末導入](docs/BUILD.md)
- [Echo Show 5 アプリ開発の知見](docs/KNOWLEDGE_HANDOFF.md)
- [消灯時・無操作時の時計表示仕様](docs/IDLE_CLOCK.md)
- [hogo-meter 連携の引き継ぎ](docs/HOGO_METER_INTEGRATION.md)

## 名前

リポジトリ名とフォルダ名は `hogo-panel`。連携元の `hogo-meter` と共通の `hogo` に、常設の確認・操作画面を表す `panel` を組み合わせています。`hogo-meter-show5` は端末名に用途を固定し、`hogo-meter-android` は常設パネルという役割が伝わりにくいため、こちらを採用しました。

## ライセンス

[MIT License](LICENSE)
