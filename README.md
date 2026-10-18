# Hogo Panel

[hogo-meter](https://hogo-meter-intro.akebi.dam-lab.work) と連携する、Android 化した Echo Show 5 向けのお世話パネル。

お世話の最終実施時刻・経過時間の確認と記録を、常設のタッチ画面から行うためのアプリを開発します。無操作時には低輝度のフリップ時計を表示する方針です。

## 現在の状態

最初の段階として、起動直後から低輝度のフリップ時計だけを表示する Android アプリ（PoC）があります。操作画面、待機設定、タッチ復帰、hogo-meter との通信、HOME 指定・自動起動はまだありません。APK は配布せず、[ビルドと端末導入](docs/BUILD.md) の手順で作ります。

この PoC はビルド・単体テスト16件・lintを確認し、Android 11 の Echo Show 5 へ Wi-Fi ADB 経由で導入済みです。時計の全画面表示と JDWP のデバッガ通信を確認しました。検証の範囲は [ビルドと端末導入](docs/BUILD.md#実機検証結果2026-10-18) を参照してください。

## 対象端末

Android 化した Echo Show 5 を対象にしています。今回の実機は LineageOS 18.1 / Android 11 / 960×480、32ビット ARM です。標準の Echo Show OS へそのまま APK を導入できるという意味ではありません。

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
