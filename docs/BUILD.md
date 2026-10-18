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

今回の端末は既に TCP ADB が有効で、`adb connect <端末のアドレス>:5555` による接続を確認しました。`adb devices -l` に出るシリアル（`<アドレス>:<ポート>` 形式）を `scripts/install.sh` へ渡せます。TCP ADB の設定変更は行っていません。再起動後の継続性は未検証です。

Android 11 のペアリング式「ワイヤレス デバッグ」は今回無効で、検証していません。これを使用する別の端末では、端末側で表示されるペアリング用アドレスに `adb pair` し、接続用アドレスに `adb connect` します。アドレスやペアリングコードは端末固有の情報なので、公開リポジトリ・ログ・Issue に残さないでください。

## 表示の確認項目

- 起動直後に黒背景で横画面全体に `HH:mm`（24 時間）が出ること。秒・日付・ロゴ・案内は出ません。
- 分が変わったとき、変わった桁だけがフリップすること。
- 時計が時間をかけて画面内を動き、端から欠けないこと。
- 画面が点灯し続け、輝度がウィンドウ指定で下がること（実際のバックライト輝度は目視で確認する）。
- 背面にしたとき描画が止まり、前面復帰時に現在時刻へ即時に合うこと。

## 実機検証結果（2026-10-18）

- 対象: Android 化した Echo Show 5、LineageOS 18.1 / Android 11（API 30）、960×480、32ビット ARM。
- Wi-Fi の TCP ADB 経由で debug APK（0.1.0 / versionCode 1）をインストールし、起動に成功しました。USB と同じ端末であることも確認しています。
- スクリーンショットで時計のみの `HH:mm` 表示、黒背景・カード・コロン、画面内に収まる表示を確認しました。初回の Android 全画面案内は閉じています。
- WindowManager で画面領域960×480、システムバー非表示、`KEEP_SCREEN_ON`、輝度指定0.05を確認しました。これはバックライトの実測値ではありません。
- 65秒の画面録画で `11:20 → 11:21` の分更新、変わった最後の桁の約450msのフリップ、時計全体の画面内移動と連続点灯を確認しました。
- HOMEへ戻るとウィンドウ輝度の指定が解除され、アプリに復帰すると現在時刻の時計と輝度0.05に戻ることを確認しました。アプリのクラッシュログはありませんでした。
- Wi-Fi 経由のアプリログ取得、JDWP のハンドシェイクと `VirtualMachine.Version` の応答を確認し、検証用ポート転送は削除しました。IDE のブレークポイント操作は未検証です。
- ビルド・単体テスト16件は成功、lintはエラー0件・警告3件です。警告はアイコン未設定、Android 12以降のバックアップ設定、向き固定に関するものです。

肉眼での低輝度の読みやすさ、長時間の残像・消費電力、再起動後のWi-Fi接続は未検証です。USBを物理的に外す試験は行っていませんが、導入・起動・ログ・JDWPは全てIPアドレスで選択したTCP接続を使用しました。端末の識別子・IP・スクリーンショットはローカルのGit管理外に保存しています。
