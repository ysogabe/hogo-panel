# 投薬予定の機器向け API v0

2026-10-22。[PRD](prd.md) / [設計](design.md)。設計の「[機器向け API（案 v0）](design.md#機器向け-api案-v0)」「[パネルの検証と状態遷移](design.md#パネルの検証と状態遷移)」「[通信](design.md#通信httpschedulesource)」を、受け手（パネル）と送り手（Mock API、将来は hogo-meter）が読む形にまとめた文書。食い違いがあれば設計を正とする。

これは hogo-meter に足す機器向け API の案で、評価のために Mock API とパネルだけで使う。本番のヘッダー名・認証方式・URL は hogo-meter 側で決める。

## 要点

- パネルが読むだけの API。実施の記録は送らない。
- 形式は camelCase、時刻は UTC の epoch 秒、予定日だけ暦日の文字列、エラーは JSON の `error` と HTTP ステータス。
- 知らないキーは無視する。`null` は省略と同じ。数値は整数だけ（小数・文字列の数字は型違い）。
- 応答は 64 KiB まで、入れ子の深さは 8 まで。

## 要求

```
GET /api/agent/{deviceId}/medications
X-Mock-Device-Secret: <評価用の値>
```

- `deviceId` は `[A-Za-z0-9_-]{1,64}`。パネルは URL エンコードして入れる。
- `X-Mock-Device-Secret` は Mock API が起動ごとに作る評価用の値。パネルは接続設定から読んで付ける。この値をログ・応答・状態に出さない。
- リダイレクトは追わない。

## 成功の応答

`200`、`Content-Type: application/json; charset=utf-8`、UTF-8 のバイト長の `Content-Length`。パネルは `Content-Type` の種類が `application/json` でなければ（大文字・小文字は区別しない、`; charset=` などは除く）取得失敗にする。

| キー | 型 | 意味 | 範囲 | 省略 |
|---|---|---|---|---|
| `enabled` | boolean | この機器に投薬予定を出すか（デバイス詳細の設定） | | 省略しない |
| `status` | string | 接続状態。[下の表](#status-の値) | 5 つの値。知らない値は `fetchFailed` と同じ | `enabled:false` のとき省く |
| `leadDays` | integer | 警告を始める日数（世帯の設定値。初期値 2） | 0〜14 | `enabled:false` のとき省く |
| `timeZone` | string | 予定のタイムゾーン（IANA 名。選んだカレンダーのもの） | IANA 名として読めること | `notConnected` と `enabled:false` のとき省く |
| `fetchedAt` | integer | 最後に Google から取れた時刻（epoch 秒） | | 一度も取れていないとき省く |
| `pollSeconds` | integer | 次の取得までの推奨間隔（秒） | 10〜3600（範囲外はパネルが収める） | 省いてよい（パネルは成功後 300 秒、失敗後は最後に分かった値、無ければ 30 秒） |
| `occurrences` | array | 回の一覧。予定日の昇順 | 最大 200 件。`notConnected` では空 | `enabled:false` のとき省く |

`enabled:false` の応答は `status`・`leadDays`・`timeZone`・`occurrences` を省く。パネルは薬カード・時計の札・状態を出さない。

### occurrences の要素

| キー | 型 | 意味 | 範囲・規則 | 省略 |
|---|---|---|---|---|
| `key` | string | 回の識別子（occurrenceKey）。実施の記録先 | 1〜128 文字の `[A-Za-z0-9._:-]`。一覧の中で重複しない | しない |
| `seriesId` | string | 系列（繰り返し予定の元）。単発の予定は `key` と同じ値 | `key` と同じ文字の規則 | しない |
| `name` | string | 薬名（予定の件名） | 1〜40 文字（Unicode のコードポイントで数える）。サーバーが前後の空白と制御文字を除き、40 文字までに切って渡す。パネルは整形せず、空白だけ・制御文字を含む・40 文字を超える名前の回を除く | しない |
| `date` | string | 予定日。`timeZone` での暦日。単日の終日予定を対象とする | `YYYY-MM-DD` | しない |
| `icon` | string | 薬のアイコン | `pill` / `drop`。省略時と知らない値は `pill`。決め方は未決（PRD） | してよい |

- `date` だけ epoch 秒にしない。終日の予定は時刻を持たない暦日で、epoch 秒にするとタイムゾーンの解釈がずれる余地が増える。複数日の予定・時刻付きの予定は、サーバーが開始日の単日の予定として渡す。
- `key` の不変条件: 同じ回なら、再取得・件名の変更・予定日の移動・系列の一部の回だけの変更をしても変わらない（Google の回の ID が元の開始日から作られることを前提にする。本番の作り方は hogo-meter で確かめる）。別のカレンダーの同名の予定とは別の key になる。カレンダー ID をそのまま入れない（本番ではハッシュなどで伏せる）。
- Mock の `key` は `occ-<カレンダー>-<系列>-<元の予定日>`（例 `occ-cal1-F-20261024`）の架空の値。カレンダーを切り替えると別の key になる。単発の予定の例は `mock-once-20261030`（`seriesId` も同じ値）。

## 返す回の範囲

予定日が「今日の 7 日前」から「今日の 60 日後」までの回（両端を含む。「今日」は `timeZone` での今日）。

- 7 は hogo-meter の世帯の設定値の想定。今回は Mock の設定値（`pastDays`）で、パネルには配らない。
- 今回は実施を送らないので、サーバーは過去の回が済んだか分からない。7 日を過ぎた回は返さず、パネルの未確認の警告もそこで消える（PRD の「決まっていないこと」）。
- パネルは、これより広い今日の −31〜+400 日の回を受け付ける。範囲外の回だけを除く（[パネルの検証](#パネルの検証)）。

## status の値

`enabled:true` のとき必ず付く。意味はサーバーが決める。「古い」とみなす時間もサーバーが決める（Mock は評価用の切り替えで直接選ぶ）。

| status | 意味 | occurrences | パネルの状態表示 | パネルの扱い |
|---|---|---|---|---|
| `ok` | 最近 Google から取れている | 最新 | 出さない | 一覧を置き換える。`leadDays`・`timeZone` を使う |
| `stale` | 取得は成功していたが古い | サーバーが最後に取れたもの | 予定が古い | 同上。警告は続ける |
| `fetchFailed` | 直近の Google からの取得に失敗 | サーバーが最後に取れたもの | 予定を更新できません | 同上。警告は続ける |
| `reauthRequired` | Google の認可が切れた | サーバーが最後に取れたもの | カレンダーの再接続が必要 | 同上。警告は続ける |
| `notConnected` | 未接続、またはカレンダー未選択 | 空（切断・未選択で消す） | カレンダー未接続 | 一覧を空にする。ゾーンは端末のもの |
| 知らない値 | | | `fetchFailed` と同じ | `fetchFailed` と同じ |

`enabled:false` は `status` を持たない別の形で、パネルの一覧を空にして何も出さない。

## エラーの応答

本文は `{"error": "<短い文字列>"}`。

| ステータス | 本文 | 意味 |
|---|---|---|
| `401` | `{"error":"unauthorized"}` | ヘッダーが無い・値が違う |
| `404` | `{"error":"device not found"}` | `deviceId` が違う、またはパスが違う |
| `500` | `{"error":"internal error"}` | サーバーの内部エラー |

パネルは 2xx 以外の本文を読まない。ステータスだけで `HTTP_401`・`HTTP_404`・`HTTP_OTHER`（その他の 4xx・5xx）に分け、3xx は `REDIRECT` にする。どれも「予定を取得できません」で、前に取れた予定があれば残して警告を続ける。認証の失敗でリダイレクトやログイン画面の HTML が返る場合も、同じ取得失敗になる。

## パネルの検証

読み取りは `MedicationsJson.parse(text, today)`。通信の段階で、64 KiB を超える本文は `TOO_LARGE`（読んだバイト数で数え、`Content-Length` を信じない）、15 秒で終わらなければ `TIMEOUT`、読めなければ `NETWORK` にする。

### 全体の失敗（`Failed(INVALID)`）

次のどれかなら、応答全体を使わない。前に取れた予定があれば残す。

- JSON として読めない。
- 入れ子の深さが 8 を超える（解析の前に数える。JSON の文字列の中の括弧とエスケープは数えない）。
- 64 KiB を超える。
- `enabled` が無い、または boolean でない。
- `enabled:true` なのに `status`・`leadDays`・`occurrences` が無いか型が違う。
- `leadDays` が 0〜14 の外、または整数でない。
- `timeZone` が IANA 名として読めない。
- `occurrences` が 200 件を超える。
- `key` が重複する。

### 回ごとの除外

次のどれかなら、その回だけを除き、他の回は使う（1 件のおかしな件名で全部を使えなくしない）。除いた件数は数えるが画面には出さない（ログにだけ件数を出す）。

- `key`・`seriesId`・`name`・`date` が欠ける。
- それぞれが型や規則に合わない（文字種、長さ、整数でない数値など）。
- `date` が `^\d{4}-\d{2}-\d{2}$` でない、または実在しない日付。
- `name` が空白だけ、制御文字を含む、40 コードポイントを超える。
- `date` が今日の −31〜+400 日の外。

### 知らないキー・値

| 場合 | 扱い |
|---|---|
| 知らないキー（トップ・回の両方） | 無視する |
| 値が `null` | 省略と同じ |
| 知らない `status` | `fetchFailed` と同じ |
| 知らない `icon`、`icon` なし | `pill` |
| 小数・文字列の数字を整数のキーに入れた | 型違い。`leadDays` なら全体の失敗、回のキーなら回の除外。`pollSeconds`・`fetchedAt` の型違いは、そのキーを省いたものとして扱う（全体の失敗にしない） |

## 見本

置き場所: [`app/src/test/resources/api/medications/`](../../app/src/test/resources/api/medications/)。Mock API の unittest とパネルの単体テストの両方が同じファイルを読む。日付は 2026-10-22（`Asia/Tokyo`）を今日とした固定の例で、`fetchedAt` も固定の値。薬名・ID はすべて架空。

| ファイル | 内容 |
|---|---|
| [`ok.json`](../../app/src/test/resources/api/medications/ok.json) | `ok`。3 系列（フィラリア F・フロントライン L・40 文字の長い薬名 M）の毎月の回と、単発の回。未確認（前日）・今日・そろそろ（2 日後）を含む。`leadDays` 2 |
| [`stale.json`](../../app/src/test/resources/api/medications/stale.json) | `stale`。最後に取れてから日が経った例 |
| [`fetchFailed.json`](../../app/src/test/resources/api/medications/fetchFailed.json) | `fetchFailed`。前回取れた一覧を返す |
| [`reauthRequired.json`](../../app/src/test/resources/api/medications/reauthRequired.json) | `reauthRequired`。前回取れた一覧を返す |
| [`notConnected.json`](../../app/src/test/resources/api/medications/notConnected.json) | `notConnected`。`occurrences` は空、`timeZone`・`fetchedAt` なし |
| [`disabled.json`](../../app/src/test/resources/api/medications/disabled.json) | `enabled:false`。`enabled` と `pollSeconds` だけ |
| [`error-401.json`](../../app/src/test/resources/api/medications/error-401.json) | `401` の本文 |
| [`error-404.json`](../../app/src/test/resources/api/medications/error-404.json) | `404` の本文 |
| [`error-500.json`](../../app/src/test/resources/api/medications/error-500.json) | `500` の本文 |

`error-*.json` は本文だけで、ステータスはファイル名に対応する。成功の見本は、上の表の制約（件数・文字種・範囲・昇順）をすべて満たす。
