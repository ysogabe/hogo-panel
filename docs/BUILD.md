# ビルドと端末導入

時計だけの PoC（applicationId `io.github.ysogabe.hogo.panel`）のビルドと導入の手順です。

## 必要なもの

- JDK 17
- Android SDK（compileSdk 35）。`ANDROID_HOME` を設定するか、リポジトリ直下の `local.properties` に `sdk.dir=<SDK のパス>` を書く（`local.properties` は Git 管理外）。
- 導入時のみ `adb`（platform-tools）。
- 採用バージョン: Gradle 8.11.1（wrapper）、Android Gradle Plugin 8.7.3、Kotlin 2.1.0、minSdk 30。

## ビルドと検証

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
```

- 時刻境界（`09:59 → 10:00`、`23:59 → 00:00`）、時刻・タイムゾーン補正、フリップする桁の判定、移動範囲などの単体テストを実行します。
- debug APK は `app/build/outputs/apk/debug/app-debug.apk` に出力されます。APK とビルド出力はコミットしません。
- lint は警告のみで失敗しません（ランチャーアイコン未設定など）。

## 端末への導入

```sh
adb devices -l                       # 対象端末のシリアルを確認する
scripts/install.sh <serial>          # 上書きインストールして起動する
```

- シリアルは必須です。省略すると何もせず終了します。複数台が接続されていても、指定した 1 台にしか操作しません。
- `scripts/install.sh` は `adb -s <serial> install -r` と `am start` だけを行います。HOME 指定、権限の付与、設定の変更はしません。
- 手動で行う場合は `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk` の後に `adb -s <serial> shell am start -n io.github.ysogabe.hogo.panel/.MainActivity`。

## Wi-Fi デバッグ（ワイヤレス ADB）

Android 11 以降の端末では、開発者向けオプションの「ワイヤレス デバッグ」から接続できます。`adb pair` でペアリングした後に `adb connect <端末のアドレス>:<ポート>` で接続し、`adb devices -l` に出るシリアル（`<アドレス>:<ポート>` 形式）を `scripts/install.sh` へ渡します。アドレスやペアリングコードは端末固有の情報なので、リポジトリ・ログ・Issue に残さないでください。

## 表示の確認項目

- 起動直後に黒背景で横画面全体に `HH:mm`（24 時間）が出ること。秒・日付・ロゴ・案内は出ません。
- 分が変わったとき、変わった桁だけがフリップすること。
- 時計が時間をかけて画面内を動き、端から欠けないこと。
- 画面が点灯し続け、輝度がウィンドウ指定で下がること（実際のバックライト輝度は目視で確認する）。
- 背面にしたとき描画が止まり、前面復帰時に現在時刻へ即時に合うこと。

これらは未検証です。結果は確認後に記録します。
