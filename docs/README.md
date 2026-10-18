# 開発資料

2026-10-18 に、Miharashi Panel の開発成果を新しい Hogo Panel へ引き継ぐために整理しました。

- [KNOWLEDGE_HANDOFF.md](KNOWLEDGE_HANDOFF.md): 端末条件、Android の実装方針、導入・起動・通信・検証の知見。
- [IDLE_CLOCK.md](IDLE_CLOCK.md): 消灯に代わる低輝度フリップ時計の画面・遷移・更新・誤操作防止仕様。
- [HOGO_METER_INTEGRATION.md](HOGO_METER_INTEGRATION.md): 連携の目的、参照先、実装前に決めること。

ここでいう「既存アプリで確認済み」は Miharashi Panel の記録を指します。Hogo Panel と新しい Echo Show 5 での動作確認は、まだ行っていません。待機時計の仕様を引き継ぐことと、その実装が本リポジトリに存在することは区別してください。

hogo-meter の参照先は現時点で非公開です。閲覧権限が必要であり、本リポジトリの公開によって連携元の公開範囲を変更するものではありません。実際の接続先、世帯や端末の識別情報、認証情報、運用画面の画像は保存しません。
