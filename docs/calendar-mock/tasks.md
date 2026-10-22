# 投薬予定の Mock API と画面評価 タスク

- PRD: [prd.md](prd.md) / 設計: [design.md](design.md)
- ブランチ: t3/calendar-integration-poc
- 実機: Hogo Panel の Show 5 を Wi-Fi ADB で使う（`-s <アドレス>:5555` を明示。USB で繋がっている別の Show 5 は使わない）。`adb reverse` も TCP の接続で行う。

| ID | 内容 | AC | 依存 | 種類 | 状態 | 開始 SHA | コミット | メモ |
|---|---|---|---|---|---|---|---|---|
| T1 | API の文書 `api.md` と見本 JSON（`app/src/test/resources/api/medications/*.json`） | AC1 | - | quick | 完了 | 8fa9a9b | 47de72f..44ded84 | Opus レビュー: AC1 満たす、必須なし。推奨の仕様の空白（key の形、日付・名前・整数・深さ・重複キー・timeZone・判定順・421）を管理側で決めて反映。T3 注意: Android の org.json は重複キーを上書き、テスト用の Maven 版は例外 → 自前で検出。整数はリテラルの形で判定 |
| T2 | Mock API（`tools/mock-hogo-meter/` の `mock_state.py`・`server.py`・`push-config.sh`・unittest・README） | AC2 | T1 | full | 完了 | 55580f5 | 6107b9a..0089196 | Sol レビュー 2 回。必須 1（要求一覧のパスに秘密が残る）と推奨 3 を採用・解消。見送りなし。Mock が自分で決めた点（範囲、411/413/415/405、`/mock/preview`、`/mock/state` の形 `{state, choices, today, serverTime}`）は Sol が妥当と判断。push-config.sh は実機未確認（T6） |
| T3 | パネルの判定と読み取り（`ReminderPolicy` の警告日数の引数化、`MedicationsJson`、`ApiConfig`、`PanelModel` の API モードと `applySchedule`、確認の回の保持、カード 2 枚と「ほか N 件」、単体テスト） | AC3, AC4, AC5 | T1 | full | 完了 | cf51745 | a2f88a7 | Sol レビュー: AC3・AC4・AC5（モデル側）満たす、必須なし。設計からの変更（org.json をやめ自前の StrictJson、secret は 0x21〜0x7E、baseUrl 末尾 / 不可など）は妥当と判断し design.md に反映。推奨 1（PanelModelTest の日付順のテストが日付順を確かめていない）は T4 で直す |
| T4 | パネルの通信と配線（`HttpScheduleSource`、`ScheduleFetcher`、`PanelController.onSchedule` と `DATE`、`updateClockBadges`、状態の表示、`MainActivity`、debug のマニフェストと network security config、BUILD.md の release 検査） | AC5, AC6, AC7, AC10 | T3 | full | 完了 | fe02e26 | 21a7602..fefa7f0 | Sol レビュー 2 回。必須 2（取得が重なる、押している間の失敗が成功の応答を消す）を採用・解消。見送りなし。283 件通過。release APK に INTERNET・networkSecurityConfig なし。実機未確認: 状態の行の見え方（terracotta のコントラスト約 3.5:1）、0 時またぎ、adb reverse 切断からの回復、fault=slow は 10 秒の読み取りタイムアウトで TIMEOUT |
| T5 | 画面モック（`static/index.html`・`mock.css`・`mock.js`、1ページ3タブ、評価用の切り替え欄、要求の一覧） | AC8 | T2 | quick | 完了 | 8ff7729 | 5b489bf..5c4d4b3 | Opus レビュー: AC8（画面モック側）満たす、必須なし。採用: ダッシュボードの予定を機器の表示設定から切り離す `/mock/schedule`、最後の取得の deviceId の照合、要求の時刻のゾーン、外部読み込みの検査。設計の評価欄の位置は実装（右側）に合わせて設計を直した。120 件通過 |
| T6 | 実機（Wi-Fi ADB）とブラウザでの評価、`verification.md`、README・BUILD・バージョン 0.3.0、`CALENDAR_INTEGRATION.md` への引き継ぎの注意 | AC8, AC9 | T2, T4, T5 | quick | 実装中 | fc47b9a | | 端末操作は管理側が行い、文書を子に書かせる |
| T7 | 全体レビュー（Sol）の対応: 初回の取得失敗で「未取得」と分かる文言（必須、AC5）、api.md・design.md のエラー分類・保留・過去の日数の記述、画面モックの表示オフの説明と期待表示の前提 | AC5, AC1 | T5 | quick | 完了 | 8ac5fee | 8fd259b | 全体の security-review（Opus）は PREFLIGHT OK・High/Medium なし。Sol 再レビューで必須解消（AC5 満たす）。残りの推奨 2 件（api.md の 200 以外の 2xx、「未取得」の条件を「成功結果を受けていない」に）は T6 で直す |
