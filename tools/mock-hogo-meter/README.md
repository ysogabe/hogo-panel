# mock-hogo-meter

Hogo Panel の投薬予定を評価するための **Mock API**。PC 上で動き、hogo-meter に足す予定の「機器向けの投薬予定 API」と、ダッシュボードの画面モックの裏側（評価状態の切り替え）を1つのプロセスで出す。データはすべて架空。

- 設計: [docs/calendar-mock/design.md](../../docs/calendar-mock/design.md) / API: [docs/calendar-mock/api.md](../../docs/calendar-mock/api.md)
- Python 3 の標準ライブラリだけ。追加のインストールは要らない。
- 評価用の機能。本番の認証・機器登録の実装ではない。

## 待ち受け

| 待ち受け | 中身 | 端末へ |
|---|---|---|
| `127.0.0.1:8787` | 機器向け API（`GET /api/agent/{deviceId}/medications`）だけ | `adb reverse` で転送する |
| `127.0.0.1:8788` | 画面モック（`/`、`/static/*`）と評価用の `/mock/*` | **転送しない**（端末内のアプリから評価状態を変えられないようにする） |

どちらも 127.0.0.1 だけ。待ち受けのアドレスを変える引数は無い。

## 使い方

### 1. 起動

```
python3 tools/mock-hogo-meter/server.py
```

起動すると、接続設定ファイルのパスと次の手順が表示される（秘密の値は表示されない）。接続設定は起動のたびに新しい秘密を作り、一時ディレクトリ（`mkdtemp`、0700）の中に 0600 で書く。Ctrl-C・SIGTERM・正常終了で消える。

### 2. 評価の前に日付を確かめる

予定日は PC の日付（基準日）から作られ、パネルは**端末の日付**で判定する。ずれていると期待の表示にならない。

```
date                                  # PC
adb -s <アドレス>:5555 shell date     # 端末
```

### 3. 端末へつなぐ（Wi-Fi ADB の端末の例）

`-s` には `adb devices -l` に出るシリアル（Wi-Fi ADB なら `<アドレス>:5555`）を必ず付ける。USB で別の端末がつながっていても、対象を取り違えない。

```
adb -s <アドレス>:5555 reverse tcp:8787 tcp:8787
tools/mock-hogo-meter/push-config.sh <アドレス>:5555 <起動時に表示された設定ファイル>
adb -s <アドレス>:5555 shell am force-stop io.github.ysogabe.hogo.panel
adb -s <アドレス>:5555 shell am start -n io.github.ysogabe.hogo.panel/.MainActivity
```

- `push-config.sh` は `run-as`（debug ビルドのアプリだけ）で端末のアプリ専用領域 `files/api-config.json` に書き、`ls -l` で確かめる。中身は表示しない。`adb shell -t` は使わない。
- 設定を変えたら、`am force-stop` の後に起動し直して反映する。

### 4. ブラウザで操作する

ブラウザで `http://127.0.0.1:8788/` を開く。hogo-meter のダッシュボードに寄せた画面モック（本物ではない）が出る。

- **タブ（1 ページで切り替え。`#top` / `#calendar` / `#device` で直接開ける）**
  - トップ: 「直近の投薬予定」（世帯の予定 `/mock/schedule` から。今日の −7〜+30 日、最大 5 件。実施は送らないので、パネルで実施済みにしても残る）と、機器のカード（「投薬表示: オン / オフ」。オフは赤い枠）。未接続のときは「カレンダー未接続」と設定への導線。
  - カレンダー連携: 接続状態（正常・古い・未接続・再接続が必要・取得失敗）、「Google と接続」「切断」（見せかけ。Mock の `status` が変わるだけ）、架空のカレンダー（「ねこの投薬」= `mock-cal-1`、「家族」= `mock-cal-2`）の選択、警告を始める日数（表示のみ）、最後に取れた時刻。
  - デバイス詳細: 「投薬の予定を表示する」のスイッチ（Mock の `enabled`）。
- **評価用（本番には無い）の切り替え欄（右側。幅が狭いときは下）**: 予定の並び（preset 9 種）・接続状態・表示のオン・オフ・警告を始める日数・過去の日数・取得間隔・通信の失敗（fault）・基準日・タイムゾーン。基準日・今日・サーバー時刻も出る。
- **パネルからの最近の要求**（時刻・メソッド・パス・ステータス・fault）を 2 秒ごとに読み直して出す。

画面の変更は `POST /mock/state` で Mock の状態を変え、パネルの次の取得（`pollSeconds`、既定 15 秒）に反映される。表示は `/mock/state`・`/mock/schedule`・`/mock/requests` から作り、予定の計算も範囲の絞り込みもブラウザでは持たない（最大 5 件に切るだけ）。ダッシュボードの予定とカレンダー連携の「最後に取れた時刻」は世帯の予定から作るので、機器の表示のオン・オフや過去の日数に左右されない。表示が変わらないときの注意:

- 表示のオン・オフ（`enabled`）と過去の日数（`pastDays`）は機器向けの応答（`/mock/preview`）だけに効く。トップの「直近の投薬予定」は変わらない。
- `fault` はパネルへの応答にだけ効く。画面モックの表示は変わらない。

ファイルは `static/index.html`・`mock.css`・`mock.js` だけ（外部の CDN・フォント・ライブラリは読まない。ビルド不要）。API の値は `textContent` だけで入れる。

## 評価用エンドポイント（8788）

| | | |
|---|---|---|
| `GET /mock/state` | 評価状態（`state`）、選べる値（`choices`）、サーバーの今日（`today`）、`serverTime`、機器 ID（`deviceId`。画面の「最後の取得」の照合用） | |
| `POST /mock/state` | 部分更新。`Content-Type: application/json`、本文 16 KiB まで、`Origin` が同じ（`http://127.0.0.1:8788` か `http://localhost:8788`） | 成功時は `GET` と同じ内容 |
| `GET /mock/requests` | 機器向け API への直近 50 件（時刻・メソッド・パス・ステータス・バイト数・fault）。秘密・ヘッダー・クエリは記録しない。パスは機器向け API の正しい形のときだけ残し（`deviceId` が設定と違えば `<other>`）、それ以外は `<other path>` に置き換える | |
| `GET /mock/preview` | いまパネルが 200 で受ける本文（fault を除く）。画面モックは使わない（パネルの受け取る内容を見るため） | |
| `GET /mock/schedule` | 世帯の予定（ダッシュボード用）: `status`・`calendarId`・`timeZone`・`fetchedAt`・`occurrences`。機器の `enabled`・`pastDays`・`fault` に関係せず、範囲は今日の −7〜+30 日（両端を含む）で固定。未接続は `occurrences: []`（`fetchedAt` なし） | |

評価状態のキー: `preset`（`normal` / `soon` / `today` / `overdue` / `expired` / `two` / `three` / `longName` / `none`）、`baseDate`（`YYYY-MM-DD`）、`status`（`ok` / `stale` / `fetchFailed` / `reauthRequired` / `notConnected`）、`calendarId`（`mock-cal-1` / `mock-cal-2`）、`enabled`、`leadDays`（0〜14）、`pastDays`（0〜31）、`fault`（`none` / `unauthorized` / `serverError` / `slow` / `malformed` / `deepNesting`）、`pollSeconds`（1〜86400）、`timeZone`（`Asia/Tokyo` のような地域 ID か `UTC`）。

- `preset` を選んだときだけ `baseDate` をその時点の今日にする。同じ更新に `baseDate` があればそちらを使う。
- 型違い（`bool` を整数として受けない、小数、文字列の数字）・知らないキー・範囲外・NaN は 400。1つでも外れると何も変えない。
- `slow` は 12 秒待ってから通常の応答を返す。`fault` は、Host・パス・秘密・`deviceId` の確認に通った要求だけに効く。
- `fault` は表示オフ（`enabled:false`）より先に効く。表示オフでも `fault` が `none` 以外なら、パネルはその失敗を受け取る。
- `pollSeconds` は 1〜86400 で受けるが、パネルは 10〜3600 秒に収めて使う。変更は、パネルが既に予約した次の取得の待ちを短くしない（次の取得が済んでから効く）。

### `fault` ごとのパネルの結果

| `fault` | パネルの結果 |
|---|---|
| `none` | `Loaded`（表示オフなら `Disabled`） |
| `unauthorized` | `HTTP_401` |
| `serverError` | `HTTP_OTHER` |
| `slow` | `TIMEOUT`（パネルの読み取りは 10 秒で切れるので、12 秒の待ちより先に失敗する） |
| `malformed` | `INVALID` |
| `deepNesting` | `INVALID` |

失敗の結果は、前に予定を取れていれば「予定を取得できません」、一度も取れていなければ「予定を未取得（通信できません）」として出る。

「パネルからの最近の要求」（`GET /mock/requests`）のステータス 200 は、Mock が 200 を返したという意味で、パネルが採用したという意味ではない。壊れた JSON（`malformed`）や深い入れ子（`deepNesting`）も 200 で返すので、パネルが `INVALID` にしても要求の一覧は 200 のまま。

## 後片付け

```
# Mock API を止める（Ctrl-C）。接続設定ファイルは自動で消える。
adb -s <アドレス>:5555 reverse --remove tcp:8787
adb -s <アドレス>:5555 shell "run-as io.github.ysogabe.hogo.panel rm files/api-config.json"
adb -s <アドレス>:5555 shell am force-stop io.github.ysogabe.hogo.panel   # 設定なし（シナリオの操作モック）に戻る
```

異常終了で接続設定ファイルが残っても、秘密は起動ごとに変わるので使えない。

## adb reverse が切れたとき

端末の再接続や `adb` の再起動でポート転送は消える。パネルは「予定を取得できません」（一度も取れていないときは「予定を未取得（通信できません）」）を出し、前回の予定で動き続ける。転送を張り直せば、次の取得で戻る。

```
adb -s <アドレス>:5555 reverse --list
adb -s <アドレス>:5555 reverse tcp:8787 tcp:8787
```

`adb reverse` が切れている間は、端末内の他のアプリが端末の 127.0.0.1:8787 を待ち受けられる。評価用の値は起動ごとに変わり、データも架空なので許容している（[設計の安全面](../../docs/calendar-mock/design.md#安全面)）。

## テスト

```
cd tools/mock-hogo-meter
python3 -m unittest -v
```

テストは空いているポートで起動する（本番の起動は 8787/8788 固定）。`app/src/test/resources/api/medications/` の見本 JSON と同じキー・型になることも確かめる。

## 公開してよい記録だけを残す

端末のシリアル・アドレス・ポート・PC のユーザー名やパス・評価用の秘密の値は、文書・スクリーンショット・Issue に書かない（`<アドレス>` などに置き換える）。
