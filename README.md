# Hogo Panel

[hogo-meter](https://hogo-meter-intro.akebi.dam-lab.work) と連携する、Android 化した Echo Show 5 向けのお世話パネル。

お世話の最終実施時刻・経過時間の確認と記録を、常設のタッチ画面から行うためのアプリを開発します。無操作時には低輝度のフリップ時計を表示する方針です。

## 現在の状態

2段階あります。

- **時計だけの PoC（0.1.0）:** 起動直後から低輝度のフリップ時計だけを表示します。ビルド・単体テスト16件・lint を確認し、Android 11 の Echo Show 5 へ Wi-Fi ADB 経由で導入して全画面表示と JDWP を確認しました。範囲は [ビルドと端末導入](docs/BUILD.md#実機検証結果2026-10-18) を参照してください。
- **操作モック（0.2.1、アプリ表示名 Hogo Panel Mock）:** 承認済みの [HTML 画面案](docs/routine-ui/prototype.html) を Android のネイティブ画面にした、**架空データだけ**の操作モックです。普段はお世話3項目、薬の予定日の2暦日前から薬カード、確認と8秒の取消、3分無操作か時計ボタンで既存のフリップ時計、画面外のシナリオ選択を備えます。0.2.1 から、当日・未確認の薬カードだけが約2.4秒周期でゆっくり明滅します（2日前と時計は静止）。メモリ内のみで、通信・保存・新しい権限・HOME 指定・自動起動はありません。操作と試し方は [操作モックの試用](docs/routine-ui/design.md#android-操作モック) を参照してください。

操作モックは、ビルド・単体テスト92件（時計の既存16件を含む）・lint（エラー0件、既存の警告3件）を確認しています。Show 5実機でも、ADBのタッチ入力とスクリーンショットで主要表示・遷移、3分の待機、時計の輝度指定と復帰を確認しました。人の指による連打・複数指、肉眼の低輝度の見やすさ・長時間の残像は未確認です。[検証記録](docs/routine-ui/verification.md)に範囲をまとめています。実際の投薬予定・Google・hogo-meter との接続、設定画面はありません。APK は配布せず、[ビルドと端末導入](docs/BUILD.md) の手順で作ります。

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
