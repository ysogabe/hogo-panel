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

ブラウザで `http://127.0.0.1:8788/` を開く。画面モックの評価用の切り替え欄で、予定の並び・接続状態・表示のオン・オフ・警告日数・通信の失敗などを切り替える。パネルは次の取得（`pollSeconds`、既定 15 秒）で反映する。パネルからの要求は `http://127.0.0.1:8788/mock/requests` で見られる。

画面モックのファイル（`static/index.html`・`mock.css`・`mock.js`）が無いうちは `/` と `/static/*` は 404 になる。

## 評価用エンドポイント（8788）

| | | |
|---|---|---|
| `GET /mock/state` | 評価状態（`state`）、選べる値（`choices`）、サーバーの今日（`today`）、`serverTime` | |
| `POST /mock/state` | 部分更新。`Content-Type: application/json`、本文 16 KiB まで、`Origin` が同じ（`http://127.0.0.1:8788` か `http://localhost:8788`） | 成功時は `GET` と同じ内容 |
| `GET /mock/requests` | 機器向け API への直近 50 件（時刻・メソッド・パス・ステータス・バイト数・fault）。秘密・ヘッダー・クエリは記録しない。パスは機器向け API の正しい形のときだけ残し（`deviceId` が設定と違えば `<other>`）、それ以外は `<other path>` に置き換える | |
| `GET /mock/preview` | いまパネルが 200 で受ける本文（fault を除く）。画面モックの表示用 | |

評価状態のキー: `preset`（`normal` / `soon` / `today` / `overdue` / `expired` / `two` / `three` / `longName` / `none`）、`baseDate`（`YYYY-MM-DD`）、`status`（`ok` / `stale` / `fetchFailed` / `reauthRequired` / `notConnected`）、`calendarId`（`mock-cal-1` / `mock-cal-2`）、`enabled`、`leadDays`（0〜14）、`pastDays`（0〜31）、`fault`（`none` / `unauthorized` / `serverError` / `slow` / `malformed` / `deepNesting`）、`pollSeconds`（1〜86400）、`timeZone`（`Asia/Tokyo` のような地域 ID か `UTC`）。

- `preset` を選んだときだけ `baseDate` をその時点の今日にする。同じ更新に `baseDate` があればそちらを使う。
- 型違い（`bool` を整数として受けない、小数、文字列の数字）・知らないキー・範囲外・NaN は 400。1つでも外れると何も変えない。
- `slow` は 12 秒待ってから通常の応答を返す。`fault` は、Host・パス・秘密・`deviceId` の確認に通った要求だけに効く。

## 後片付け

```
# Mock API を止める（Ctrl-C）。接続設定ファイルは自動で消える。
adb -s <アドレス>:5555 reverse --remove tcp:8787
adb -s <アドレス>:5555 shell "run-as io.github.ysogabe.hogo.panel rm files/api-config.json"
adb -s <アドレス>:5555 shell am force-stop io.github.ysogabe.hogo.panel   # 設定なし（シナリオの操作モック）に戻る
```

異常終了で接続設定ファイルが残っても、秘密は起動ごとに変わるので使えない。

## adb reverse が切れたとき

端末の再接続や `adb` の再起動でポート転送は消える。パネルは「予定を取得できません」を出し、前回の予定で動き続ける。転送を張り直せば、次の取得で戻る。

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
