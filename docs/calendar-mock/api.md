# 投薬予定の機器向け API v0

2026-10-22。[PRD](prd.md) / [設計](design.md)。設計の「[機器向け API（案 v0）](design.md#機器向け-api案-v0)」「[パネルの検証と状態遷移](design.md#パネルの検証と状態遷移)」「[通信](design.md#通信httpschedulesource)」を、受け手（パネル）と送り手（Mock API、将来は hogo-meter）が読む形にまとめた文書。食い違いがあれば設計を正とする。

これは hogo-meter に足す機器向け API の案で、評価のために Mock API とパネルだけで使う。本番のヘッダー名・認証方式・URL は hogo-meter 側で決める。

## 要点

- パネルが読むだけの API。実施の記録は送らない。
- 形式は camelCase、時刻は UTC の epoch 秒、予定日だけ暦日の文字列、エラーは JSON の `error` と HTTP ステータス。
- 知らないキーは無視する。`null` は省略と同じ。数値は整数だけ（小数・指数・文字列の数字は型違い）。
- 応答は 64 KiB まで、入れ子の深さは 8 まで。同じオブジェクトに同じキーを 2 回出さない。

## 要求

```
GET /api/agent/{deviceId}/medications
X-Mock-Device-Secret: <評価用の値>
```

- `deviceId` は `[A-Za-z0-9_-]{1,64}`。パネルは URL エンコードして入れる。
- `X-Mock-Device-Secret` は Mock API が起動ごとに作る評価用の値。パネルは接続設定から読んで付ける。この値をログ・応答・状態に出さない。
- リダイレクトは追わない。

## 成功の応答

`200`、`Content-Type: application/json; charset=utf-8`、UTF-8 のバイト長の `Content-Length`。パネルは `Content-Type` の種類が `application/json` でなければ（大文字・小文字は区別しない、`; charset=` などは除く）`NOT_JSON` の取得失敗にする。

| キー | 型 | 意味 | 範囲 | 省略 |
|---|---|---|---|---|
| `enabled` | boolean | この機器に投薬予定を出すか（デバイス詳細の設定） | | 省略しない |
| `status` | string | 接続状態。[下の表](#status-の値) | 5 つの値。知らない値は `fetchFailed` と同じ | `enabled:false` のとき省く |
| `leadDays` | integer | 警告を始める日数（世帯の設定値。初期値 2） | 0〜14（32 ビット符号付きの範囲を外れたら型違い） | `enabled:false` のとき省く |
| `timeZone` | string | 予定のタイムゾーン。選んだカレンダーのもの | 地域の ID（`Area/City` の形。例 `Asia/Tokyo`）か `UTC` だけ。オフセット形式は不可 | `notConnected` と `enabled:false` のとき省く。`enabled:true` で無いとき、パネルは端末のタイムゾーンで今日を決める（失敗にしない） |
| `fetchedAt` | integer | 最後に Google から取れた時刻（epoch 秒） | 0〜2^53−1（外れたら型違い） | 一度も取れていないとき省く |
| `pollSeconds` | integer | 次の取得までの推奨間隔（秒） | 32 ビット符号付きの範囲（外れたら型違い）。10〜3600 にパネルが収める | 省いてよい（パネルは成功後 300 秒、失敗後は最後に分かった値、無ければ 30 秒） |
| `occurrences` | array | 回の一覧。予定日の昇順 | 最大 200 件。`notConnected` では空 | `enabled:false` のとき省く |

`enabled:false` の応答は `status`・`leadDays`・`timeZone`・`occurrences` を省く。パネルは薬カード・時計の札・状態を出さず、`pollSeconds` 以外は（`fetchedAt` も、付いていても）読まない。読まないので、それらが型違いでも失敗にしない。JSON としての検査（読めること・大きさ・入れ子の深さ・同じキーの重複）と `enabled` の型は `enabled:false` でも行う。

共通の規則:

- **整数:** JSON の数値で、小数点も指数も無いものだけを整数とする。`2.0`・`1e2`・`"2"`・`true` は型違い。範囲は上の表のとおりで、外れたら型違い（型違いの扱いは[下の表](#知らないキー値)）。
- **大きさ:** 送り手は応答を 64 KiB（65536 バイト）以内に収める。Mock は `ensure_ascii=False` の UTF-8 で書き出す。件数と長さの上限を全部使うと 64 KiB を超えうるが、それは送り手の責任で、パネルは 64 KiB を超えた応答を全体の失敗にする。
- **入れ子の深さ:** トップの `{}` / `[]` を 1 と数え、8 までは受ける。9 以上は全体の失敗。
- **同じキー:** 同じオブジェクトの中で同じキーが 2 回出たら、全体の失敗（`INVALID`）。Mock は出さない。
- **`timeZone`:** `Asia/Tokyo` のように `/` を含む地域の ID と `UTC` だけを受ける。`+09:00`・`UTC+9`・`Z` などのオフセット形式、`JST` のような略称は全体の失敗。パネルは `ZoneId.of` に渡す前に形を確かめる（`ZoneId.of` はオフセット形式も受けてしまう）。

### occurrences の要素

| キー | 型 | 意味 | 範囲・規則 | 省略 |
|---|---|---|---|---|
| `key` | string | 回の識別子（occurrenceKey）。実施の記録先 | 1〜128 文字の `[A-Za-z0-9._:-]`。一覧の中で重複しない（重複は全体の失敗） | しない |
| `seriesId` | string | 系列（繰り返し予定の元）。単発の予定は `key` と同じ値 | `key` と同じ文字の規則 | しない |
| `name` | string | 薬名（予定の件名） | 1〜40 文字。長さは Unicode のコードポイントで数える（UTF-16 の単位ではない）。下の規則に外れる名前の回はパネルが除く | しない |
| `date` | string | 予定日。`timeZone` での暦日。単日の終日予定を対象とする | `[0-9]{4}-[0-9]{2}-[0-9]{2}` に文字列全体が一致し、実在する日付 | しない |
| `icon` | string | 薬のアイコン | `pill` / `drop`。省略時・知らない値・文字列でない値は `pill`。決め方は未決（PRD） | してよい |

- `name` の規則:
  - 次のどれかなら、パネルはその回だけを除く: 一般カテゴリ Cc・Cf の文字を含む、対になっていないサロゲートを含む、すべての文字が Unicode の White_Space（空白だけ。U+0009〜000D・0020・0085・00A0・1680・2000〜200A・2028・2029・202F・205F・3000）、40 コードポイントを超える。
  - パネルは名前を整形しない（trim しない）。
  - 送り手（Mock・本番）は、前後の空白と Cc・Cf の文字を除き、40 コードポイントまでに切って送る。
- `date` の判定: 正規表現に文字列全体が一致すること（Python は `re.fullmatch`、Kotlin は `matches()`。数字は ASCII の 0〜9 だけ）。そのうえで実在する日付であること（`2026-02-30` は不可）。

- `date` だけ epoch 秒にしない。終日の予定は時刻を持たない暦日で、epoch 秒にするとタイムゾーンの解釈がずれる余地が増える。複数日の予定・時刻付きの予定は、サーバーが開始日の単日の予定として渡す。
- `key` の不変条件: 同じ回なら、再取得・件名の変更・予定日の移動・系列の一部の回だけの変更をしても変わらない（Google の回の ID が元の開始日から作られることを前提にする。本番の作り方は hogo-meter で確かめる）。別のカレンダーの同名の予定とは別の key になる。カレンダー ID をそのまま入れない（本番ではハッシュなどで伏せる）。
- Mock の ID の作り方（架空の値）:
  - `key` は `occ-<カレンダー>-<系列>-<元の予定日 YYYYMMDD>`（例 `occ-cal1-F-20261024`）。
  - `seriesId` は `ser-<カレンダー>-<系列>`（例 `ser-cal1-F`）。
  - `<カレンダー>` は Mock の calendarId から作る。`mock-` を外してハイフンを除く（`mock-cal-1` → `cal1`、`mock-cal-2` → `cal2`）。カレンダーを切り替えると別の key になる。
  - 単発の予定も同じ形で、カレンダーを含める（例 `occ-cal1-once-20261030`、系列は `once`）。単発の `seriesId` は `key` と同じ値。

## 返す回の範囲

予定日が「今日の 7 日前」から「今日の 60 日後」までの回（両端を含む。「今日」は `timeZone` での今日）。

- 過去側の 7 日は hogo-meter の世帯の設定値の想定で、既定は 7 日。今回は Mock の評価設定 `pastDays` で 0〜31 日に変えられ、パネルには配らない。
- 今回は実施を送らないので、サーバーは過去の回が済んだか分からない。この日数を過ぎた回は返さず、パネルの未確認の警告もそこで消える（PRD の「決まっていないこと」）。
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
| `421` | `{"error":"misdirected request"}` | Mock の 8787 で `Host` が `127.0.0.1:8787` でも `localhost:8787` でもない |
| `500` | `{"error":"internal error"}` | サーバーの内部エラー |

Mock の確かめる順番: `Host`（421）→ パスの形（合わなければ 404）→ 秘密の値（401）→ `deviceId`（404）。秘密の値を先に確かめるので、秘密を持たない相手には `deviceId` の有無が分からない。

パネルは 2xx 以外の本文を読まない。ステータスだけで `HTTP_401`・`HTTP_404`・`HTTP_OTHER`（`421` を含むその他の 4xx・5xx）に分け、3xx は `REDIRECT` にする。どれも取得失敗で、前に取れた予定があれば残して警告を続け、状態は「予定を取得できません」。一度も応答を受け取れていない（未取得の）ときの失敗は「予定を未取得（通信できません）」を出す。認証の失敗でリダイレクトやログイン画面の HTML が返る場合も、同じ取得失敗になる。

## パネルの検証

読み取りは `MedicationsJson.parse(text, today)`。通信の段階で、64 KiB を超える本文は `INVALID` ではなく `TOO_LARGE`（読んだバイト数で数え、`Content-Length` を信じない。`parse` には渡らない）、15 秒で終わらなければ `TIMEOUT`、読めなければ `NETWORK` にする。

### 全体の失敗（`Failed(INVALID)`）

次のどれかなら、応答全体を使わない。前に取れた予定があれば残す。

- JSON として読めない。
- 入れ子の深さが 9 以上（トップの `{}` / `[]` を 1 と数える。解析の前に数える。JSON の文字列の中の括弧とエスケープは数えない）。
- 同じオブジェクトの中で同じキーが 2 回出る（トップ・回・入れ子のどこでも。`enabled:false` でも）。
- `enabled` が無い、または boolean でない。
- `enabled:true` なのに `status`・`leadDays`・`occurrences` が無いか型が違う。
- `leadDays` が 0〜14 の外、または整数でない（32 ビット符号付きの範囲外を含む）。
- `timeZone` が（無いのではなく）文字列でない、または地域の ID・`UTC` として読めない（オフセット形式を含む）。無いときは失敗にせず、端末のタイムゾーンを使う。
- `occurrences` が配列でない、または 200 件を超える。
- `key` が重複する（下の「判定の順番」）。

### 回ごとの除外

次のどれかなら、その回だけを除き、他の回は使う（1 件のおかしな件名で全部を使えなくしない）。除いた件数は数えるが画面には出さない（ログにだけ件数を出す）。

- 要素がオブジェクトでない。
- `key`・`seriesId`・`name`・`date` が欠ける。
- それぞれが型や規則に合わない（文字種、長さなど）。
- `date` が `[0-9]{4}-[0-9]{2}-[0-9]{2}` に文字列全体で一致しない（`matches()`）、または実在しない日付。
- `name` が [上の規則](#occurrences-の要素)の除外に当たる（Cc・Cf を含む、対になっていないサロゲートを含む、空白だけ、40 コードポイントを超える）。
- `date` が今日の −31〜+400 日の外。

### 判定の順番

1. 本文の大きさ、JSON としての読み取り、入れ子の深さ、同じキーの重複。
2. `enabled`。`false` ならここで終わる（`pollSeconds` だけ読む）。
3. `status`・`leadDays`・`timeZone`・`occurrences`（配列・200 件）。
4. `key` の重複。`key` が文字列である要素どうしで判定し、同じ値があれば全体の失敗。回ごとの除外より前に行うので、あとで除かれる回どうしでも重複は失敗になる。`key` が文字列でない要素は、この判定に入れず、次で除く。
5. 回ごとの除外。
6. 残った回を日付、同じ日は `key` の順に並べ直す。パネルは一覧の並びを確かめず、信じない。

### 知らないキー・値

| 場合 | 扱い |
|---|---|
| 知らないキー（トップ・回の両方） | 無視する |
| 値が `null` | 省略と同じ |
| 知らない `status` | `fetchFailed` と同じ |
| 知らない `icon`、`icon` なし、文字列でない `icon` | `pill` |
| 整数のキーに、小数・指数・文字列の数字・範囲外の整数（`leadDays`・`pollSeconds` は 32 ビット符号付き、`fetchedAt` は 0〜2^53−1 の外）を入れた | 型違い。`leadDays` なら全体の失敗。`pollSeconds`・`fetchedAt` の型違いは、そのキーを省いたものとして扱う（全体の失敗にしない）。範囲内で 10〜3600 の外の `pollSeconds` は型違いではなく、パネルが収める |

## 見本

置き場所: [`app/src/test/resources/api/medications/`](../../app/src/test/resources/api/medications/)。Mock API の unittest とパネルの単体テストの両方が同じファイルを読む。日付は 2026-10-22（`Asia/Tokyo`）を今日とした固定の例で、`fetchedAt` も固定の値。ID は架空。薬名は一般的な例。

| ファイル | 内容 |
|---|---|
| [`ok.json`](../../app/src/test/resources/api/medications/ok.json) | `ok`。3 系列（フィラリア F・フロントライン L・40 文字の長い薬名 M）の毎月の回と、単発の回（`occ-cal1-once-20261030`）。未確認（前日）・今日・そろそろ（2 日後）を含む。`leadDays` 2 |
| [`stale.json`](../../app/src/test/resources/api/medications/stale.json) | `stale`。最後に取れてから日が経った例 |
| [`fetchFailed.json`](../../app/src/test/resources/api/medications/fetchFailed.json) | `fetchFailed`。前回取れた一覧を返す |
| [`reauthRequired.json`](../../app/src/test/resources/api/medications/reauthRequired.json) | `reauthRequired`。前回取れた一覧を返す |
| [`notConnected.json`](../../app/src/test/resources/api/medications/notConnected.json) | `notConnected`。`occurrences` は空、`timeZone`・`fetchedAt` なし |
| [`disabled.json`](../../app/src/test/resources/api/medications/disabled.json) | `enabled:false`。`enabled` と `pollSeconds` だけ |
| [`error-401.json`](../../app/src/test/resources/api/medications/error-401.json) | `401` の本文 |
| [`error-404.json`](../../app/src/test/resources/api/medications/error-404.json) | `404` の本文 |
| [`error-500.json`](../../app/src/test/resources/api/medications/error-500.json) | `500` の本文 |

`error-*.json` は本文だけで、ステータスはファイル名に対応する。成功の見本は、上の表の制約（件数・文字種・範囲・昇順）と Mock の ID の作り方をすべて満たす。見本の薬名は BMP の文字だけ（サロゲートペアの名前は Kotlin の単体テストで別に作る）。
