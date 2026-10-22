# 開発資料

2026-10-18 に、Miharashi Panel の開発成果を新しい Hogo Panel へ引き継ぐために整理しました。

- [routine-ui/prd.md](routine-ui/prd.md): 普段はお世話だけ、2日前から投薬が現れる画面の要求。
- [routine-ui/design.md](routine-ui/design.md): 薬名と短い警告、2日前の判定・実施確認の設計案。
- [routine-ui/prototype.html](routine-ui/prototype.html): 架空データで操作できる画面案（ブラウザで開く。HTML 段階）。
- [routine-ui/design.md](routine-ui/design.md#android-操作モック): Android の操作モック（架空データ、メモリ内のみ）の実装と試し方。
- [routine-ui/verification.md](routine-ui/verification.md): HTML 段階と Android 段階の確認記録。
- [BUILD.md](BUILD.md): ビルド手順と、シリアルを明示した端末導入。
- [KNOWLEDGE_HANDOFF.md](KNOWLEDGE_HANDOFF.md): 端末条件、Android の実装方針、導入・起動・通信・検証の知見。
- [IDLE_CLOCK.md](IDLE_CLOCK.md): 消灯に代わる低輝度フリップ時計の画面・遷移・更新・誤操作防止仕様。
- [HOGO_METER_INTEGRATION.md](HOGO_METER_INTEGRATION.md): 連携の目的、参照先、実装前に決めること。
- [calendar-mock/prd.md](calendar-mock/prd.md): 投薬予定の連携を評価する Mock API とパネルの取得の要求。
- [calendar-mock/design.md](calendar-mock/design.md): Mock API・パネルの取得・画面モックの設計。
- [calendar-mock/api.md](calendar-mock/api.md): 機器向け API（案 v0）と、パネルの検証・失敗の分類。
- [calendar-mock/tasks.md](calendar-mock/tasks.md): 実装のタスクと進み具合。
- [calendar-mock/verification.md](calendar-mock/verification.md): 実機（Wi-Fi ADB の Show 5）とブラウザでの評価の記録（2026-10-22、スクリーンショット付き）。
- [CALENDAR_INTEGRATION.md](CALENDAR_INTEGRATION.md): Google カレンダー連携 PoC の配置検討（hogo-meter に接続と設定を置く案）。

ここでいう「既存アプリで確認済み」は Miharashi Panel の記録を指します。Miharashi Panel で確認済みのことを Hogo Panel で確認済みとはみなさないでください。Hogo Panel の実機での確認は、各版の検証記録（[BUILD.md](BUILD.md) から辿れる）にあります。

hogo-meter の参照先は現時点で非公開です。閲覧権限が必要であり、本リポジトリの公開によって連携元の公開範囲を変更するものではありません。実際の接続先、世帯や端末の識別情報、認証情報、運用画面の画像は保存しません。
