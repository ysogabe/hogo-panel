# 投薬予定の Mock API と画面評価 タスク

- PRD: [prd.md](prd.md) / 設計: [design.md](design.md)
- ブランチ: t3/calendar-integration-poc
- 実機: Hogo Panel の Show 5 を Wi-Fi ADB で使う（`-s <アドレス>:5555` を明示。USB で繋がっている別の Show 5 は使わない）。`adb reverse` も TCP の接続で行う。

| ID | 内容 | AC | 依存 | 種類 | 状態 | 開始 SHA | コミット | メモ |
|---|---|---|---|---|---|---|---|---|
| T1 | API の文書 `api.md` と見本 JSON（`app/src/test/resources/api/medications/*.json`） | AC1 | - | quick | 完了 | 8fa9a9b | 47de72f..44ded84 | Opus レビュー: AC1 満たす、必須なし。推奨の仕様の空白（key の形、日付・名前・整数・深さ・重複キー・timeZone・判定順・421）を管理側で決めて反映。T3 注意: Android の org.json は重複キーを上書き、テスト用の Maven 版は例外 → 自前で検出。整数はリテラルの形で判定 |
| T2 | Mock API（`tools/mock-hogo-meter/` の `mock_state.py`・`server.py`・`push-config.sh`・unittest・README） | AC2 | T1 | full | 実装中 | 55580f5 | | |
| T3 | パネルの判定と読み取り（`ReminderPolicy` の警告日数の引数化、`MedicationsJson`、`ApiConfig`、`PanelModel` の API モードと `applySchedule`、確認の回の保持、カード 2 枚と「ほか N 件」、単体テスト） | AC3, AC4, AC5 | T1 | full | 未着手 | | | |
| T4 | パネルの通信と配線（`HttpScheduleSource`、`ScheduleFetcher`、`PanelController.onSchedule` と `DATE`、`updateClockBadges`、状態の表示、`MainActivity`、debug のマニフェストと network security config、BUILD.md の release 検査） | AC5, AC6, AC7, AC10 | T3 | full | 未着手 | | | |
| T5 | 画面モック（`static/index.html`・`mock.css`・`mock.js`、1ページ3タブ、評価用の切り替え欄、要求の一覧） | AC8 | T2 | quick | 未着手 | | | |
| T6 | 実機（Wi-Fi ADB）とブラウザでの評価、`verification.md`、README・BUILD・バージョン 0.3.0、`CALENDAR_INTEGRATION.md` への引き継ぎの注意 | AC8, AC9 | T2, T4, T5 | quick | 未着手 | | | 端末操作は管理側が行い、文書を子に書かせる |
