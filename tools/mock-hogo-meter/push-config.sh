#!/bin/sh
# Mock API が書き出した接続設定を、Hogo Panel のアプリ専用領域へ run-as で書く。
# 使い方: push-config.sh <serial> <設定ファイル>
#   <serial> は `adb devices -l` に出るもの (Wi-Fi ADB なら <アドレス>:5555 の形)。
# 中身 (秘密を含む) は画面にもコマンド行にも出さない。標準入力で渡す。
# adb reverse・アプリの再起動・解除の手順は README.md を見る。
set -eu

PACKAGE=io.github.ysogabe.hogo.panel

if [ $# -ne 2 ]; then
    echo "usage: $0 <serial> <config-file>" >&2
    exit 2
fi
serial=$1
config=$2

case $serial in
    -* | *[!A-Za-z0-9._:-]* | "")
        echo "error: serial must be like <アドレス>:5555 or the serial from 'adb devices -l'" >&2
        exit 2
        ;;
esac
if [ ! -f "$config" ] || [ ! -r "$config" ]; then
    echo "error: config file is not readable: $config" >&2
    exit 2
fi

# 端末側のコマンド全体を引用で囲む。`adb shell -t` は使わない (入力が画面に出る)。
adb -s "$serial" shell "run-as $PACKAGE sh -c 'umask 077; mkdir -p files && cat > files/api-config.json.tmp && mv files/api-config.json.tmp files/api-config.json'" < "$config"

# 確認は ls -l だけ。中身は表示しない。
listing=$(adb -s "$serial" shell "run-as $PACKAGE ls -l files/" < /dev/null | tr -d '\r')
printf '%s\n' "$listing"
if ! printf '%s\n' "$listing" | grep -q '^-rw------- .* api-config\.json$'; then
    echo "error: files/api-config.json was not written with mode -rw-------" >&2
    exit 1
fi
echo "ok: files/api-config.json written (contents not shown). Next: restart the app (see README.md)."
