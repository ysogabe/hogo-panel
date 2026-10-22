"""評価状態と、状態から機器向け API の応答を作る純粋な関数。

時計・乱数・ファイルには触れない。「今日」と「いま」(epoch 秒) は呼び出し側が渡す。
仕様は docs/calendar-mock/api.md と design.md の「Mock API の評価用エンドポイントと画面モック」。
"""

import json
import re
import unicodedata
from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo

PRESETS = ("normal", "soon", "today", "overdue", "expired", "two", "three", "longName", "none")
STATUSES = ("ok", "stale", "fetchFailed", "reauthRequired", "notConnected")
CALENDARS = ("mock-cal-1", "mock-cal-2")
FAULTS = ("none", "unauthorized", "serverError", "slow", "malformed", "deepNesting")

STATE_KEYS = (
    "preset", "baseDate", "status", "calendarId", "enabled",
    "leadDays", "pastDays", "fault", "pollSeconds", "timeZone", "garbage",
)

LEAD_DAYS_RANGE = (0, 14)
PAST_DAYS_RANGE = (0, 31)  # パネルが受ける -31 日まで
POLL_SECONDS_RANGE = (1, 86400)  # パネルは 10〜3600 に収める。収める動作を評価できるよう広めに許す
FUTURE_DAYS = 60
SCHEDULE_PAST_DAYS = 7  # ダッシュボードの「直近の投薬予定」の範囲
SCHEDULE_FUTURE_DAYS = 30
MAX_OCCURRENCES = 200  # api.md: occurrences の上限。64 KiB 以内にも収まる (名前 40 文字・key 40 文字ほどで 1 件 250 バイト弱)

# ゴミ収集カレンダー (local/garbage.json。Git の管理外)。dayOnly の回として薬の回に加えて返す。
GARBAGE_FILE_MAX_BYTES = 256 * 1024
GARBAGE_MAX_SERIES = 20
GARBAGE_MAX_DATES = 800  # 1 系列あたり
GARBAGE_ICON = "trash"
BASE_DATE_RANGE = (date(2000, 1, 1), date(2099, 12, 31))

NAME_MAX_CODEPOINTS = 40

# 系列: (薬名, アイコン)。薬名は架空・一般的な例。M は 40 コードポイントちょうどの長い名前。
SERIES = {
    "F": ("フィラリア予防薬", "pill"),
    "L": ("フロントライン", "drop"),
    "M": ("ノミ・マダニ・フィラリア同時予防薬（ねこ用・体重2.5〜5kg・月1回・錠剤型）", "pill"),
}

# preset ごとの回: (系列, 基準日 B からの日数)。設計の「予定の並び」の表のとおり。
# expired の B-8 は表では「範囲外で返さない」。pastDays を 8 以上にすると返る。
PRESET_OCCURRENCES = {
    "normal": (("F", 10), ("F", 41)),
    "soon": (("F", 2), ("F", 33)),
    "today": (("F", 0), ("F", 31)),
    "overdue": (("F", -1), ("F", 30)),
    "expired": (("F", -8), ("F", 22)),
    "two": (("F", 2), ("L", 0)),
    "three": (("F", 2), ("L", 0), ("M", -1)),
    "longName": (("M", 0),),
    "none": (),
}

# status ごとの fetchedAt の「いま」からのずらし秒数 (notConnected は付けない)。
FETCHED_AGO = {"ok": 0, "stale": 2 * 86400, "fetchFailed": 3600, "reauthRequired": 86400}

_DATE_RE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}")
_GARBAGE_SERIES_ID_RE = re.compile(r"[a-z0-9]{1,16}")
_ZONE_RE = re.compile(r"[A-Za-z_]+(?:/[A-Za-z0-9_+-]+)+")

# api.md の White_Space の一覧
_WHITE_SPACE = frozenset(
    [chr(c) for c in range(0x09, 0x0E)]
    + [chr(c) for c in (0x20, 0x85, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000)]
    + [chr(c) for c in range(0x2000, 0x200B)]
)


class StateError(ValueError):
    """評価状態の更新が検証に通らなかった。メッセージに入力値は入れない。"""


def calendar_token(calendar_id):
    """`mock-cal-1` -> `cal1`。"""
    token = calendar_id[5:] if calendar_id.startswith("mock-") else calendar_id
    return token.replace("-", "")


def series_id(calendar_id, series):
    return "ser-%s-%s" % (calendar_token(calendar_id), series)


def occurrence_key(calendar_id, series, original_date):
    """`occ-<カレンダー>-<系列>-<元の予定日 YYYYMMDD>`。"""
    return "occ-%s-%s-%s" % (calendar_token(calendar_id), series, original_date.strftime("%Y%m%d"))


def occurrence_ids(calendar_id, series, original_date):
    """(key, seriesId)。単発 (`once`) の seriesId は key と同じ値。"""
    key = occurrence_key(calendar_id, series, original_date)
    if series == "once":
        return key, key
    return key, series_id(calendar_id, series)


def sanitize_name(name):
    """送り手の規則: Cc・Cf の文字と前後の空白を除き、40 コードポイントまでに切る。"""
    kept = "".join(ch for ch in name if unicodedata.category(ch) not in ("Cc", "Cf"))
    return _strip_white_space(_strip_white_space(kept)[:NAME_MAX_CODEPOINTS])


def _strip_white_space(text):
    start, end = 0, len(text)
    while start < end and text[start] in _WHITE_SPACE:
        start += 1
    while end > start and text[end - 1] in _WHITE_SPACE:
        end -= 1
    return text[start:end]


def parse_date(text):
    """`YYYY-MM-DD` (ASCII の数字) で実在する日付だけを受ける。"""
    if not isinstance(text, str) or not _DATE_RE.fullmatch(text):
        raise StateError("invalid date")
    try:
        return date.fromisoformat(text)
    except ValueError:
        raise StateError("invalid date") from None


def valid_time_zone(text):
    """`UTC` か `Area/City` の形の地域 ID で、tz データベースにあるものだけ。"""
    if not isinstance(text, str):
        return False
    if text == "UTC":
        return True
    if not _ZONE_RE.fullmatch(text):
        return False
    try:
        ZoneInfo(text)
    except (ValueError, OSError, KeyError):
        return False
    return True


def today_in_zone(time_zone, now_epoch):
    """予定のタイムゾーンでの今日。"""
    return datetime.fromtimestamp(now_epoch, tz=timezone.utc).astimezone(ZoneInfo(time_zone)).date()


def initial_state(today):
    return {
        "preset": "normal",
        "baseDate": today.isoformat(),
        "status": "ok",
        "calendarId": "mock-cal-1",
        "enabled": True,
        "leadDays": 2,
        "pastDays": 7,
        "fault": "none",
        "pollSeconds": 15,
        "timeZone": "Asia/Tokyo",
        "garbage": True,
    }


def _is_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


def _check_enum(value, choices):
    if not isinstance(value, str) or value not in choices:
        raise StateError("invalid value")
    return value


def _check_int(value, bounds):
    if not _is_int(value) or not bounds[0] <= value <= bounds[1]:
        raise StateError("invalid value")
    return value


def apply_update(state, update, today_for_zone):
    """部分更新を検証し、新しい状態を返す (state は変えない)。

    `today_for_zone(timeZone)` は、新しい timeZone での今日を返す関数。
    `preset` を含む更新は baseDate をその今日にする。同じ更新に baseDate もあればそちらが優先する。
    1つでも検証に通らなければ StateError で、何も変えない。
    """
    if not isinstance(update, dict):
        raise StateError("body must be an object")
    for key in update:
        if key not in STATE_KEYS:
            raise StateError("unknown key")
    new = dict(state)
    if "timeZone" in update:
        if not valid_time_zone(update["timeZone"]):
            raise StateError("invalid value")
        new["timeZone"] = update["timeZone"]
    if "preset" in update:
        new["preset"] = _check_enum(update["preset"], PRESETS)
        new["baseDate"] = today_for_zone(new["timeZone"]).isoformat()
    if "baseDate" in update:
        base = parse_date(update["baseDate"])
        if not BASE_DATE_RANGE[0] <= base <= BASE_DATE_RANGE[1]:
            raise StateError("invalid value")
        new["baseDate"] = base.isoformat()
    if "status" in update:
        new["status"] = _check_enum(update["status"], STATUSES)
    if "calendarId" in update:
        new["calendarId"] = _check_enum(update["calendarId"], CALENDARS)
    if "fault" in update:
        new["fault"] = _check_enum(update["fault"], FAULTS)
    for flag in ("enabled", "garbage"):
        if flag in update:
            if not isinstance(update[flag], bool):
                raise StateError("invalid value")
            new[flag] = update[flag]
    if "leadDays" in update:
        new["leadDays"] = _check_int(update["leadDays"], LEAD_DAYS_RANGE)
    if "pastDays" in update:
        new["pastDays"] = _check_int(update["pastDays"], PAST_DAYS_RANGE)
    if "pollSeconds" in update:
        new["pollSeconds"] = _check_int(update["pollSeconds"], POLL_SECONDS_RANGE)
    return new


def _reject_constant(_name):
    raise StateError("invalid json")


def _no_duplicate_keys(pairs):
    obj = {}
    for key, value in pairs:
        if key in obj:
            raise StateError("duplicate key")
        obj[key] = value
    return obj


def parse_update_json(text):
    """POST 本文の JSON を読む。NaN・Infinity・同じキーの重複・読めない JSON は StateError。"""
    try:
        return json.loads(text, parse_constant=_reject_constant, object_pairs_hook=_no_duplicate_keys)
    except StateError:
        raise
    except (ValueError, RecursionError):
        raise StateError("invalid json") from None


def public_state(state):
    """画面・/mock/state に出す状態。秘密は状態に持たない。"""
    return {key: state[key] for key in STATE_KEYS}


def _is_utf8_encodable(text):
    """孤立サロゲートを含む文字列は UTF-8 にできない (応答の組み立てで落ちる)。"""
    try:
        text.encode("utf-8")
    except UnicodeEncodeError:
        return False
    return True


class GarbageError(ValueError):
    """ゴミ収集の定義が読めない。メッセージに中身は入れない。"""


def parse_garbage(text):
    """local/garbage.json の本文を検証し、系列の一覧 [(id, name, [date, ...])] を返す。

    形: {"calendarName": ..., "dayOnly": true, "series": [{"id", "name", "dates": ["YYYY-MM-DD", ...]}]}。
    全体が読めない (JSON でない・オブジェクトでない・series が配列でない) なら GarbageError。
    外れた系列 (id が `[a-z0-9]{1,16}` でない・重複・名前が空か UTF-8 にできない・dates が配列でない) と
    外れた日付は捨てる。名前は sanitize_name の規則 (送り手の規則) で整える。dates は昇順・重複なしにする。
    """
    try:
        doc = json.loads(text, parse_constant=_reject_constant, object_pairs_hook=_no_duplicate_keys)
    except (StateError, ValueError, RecursionError):
        raise GarbageError("unreadable") from None
    if not isinstance(doc, dict) or not isinstance(doc.get("series"), list):
        raise GarbageError("unreadable")
    result = []
    seen = set()
    for entry in doc["series"][:GARBAGE_MAX_SERIES]:
        if not isinstance(entry, dict):
            continue
        sid, name, dates = entry.get("id"), entry.get("name"), entry.get("dates")
        if not isinstance(sid, str) or not _GARBAGE_SERIES_ID_RE.fullmatch(sid) or sid in seen:
            continue
        if not isinstance(name, str) or not isinstance(dates, list):
            continue
        name = sanitize_name(name)
        if not name or not _is_utf8_encodable(name):
            continue
        good = set()
        for text_date in dates[:GARBAGE_MAX_DATES]:
            try:
                good.add(parse_date(text_date))
            except StateError:
                continue
        seen.add(sid)
        result.append((sid, name, sorted(good)))
    return result


def garbage_occurrences(series, low, high):
    """ゴミ収集の回 (範囲 low .. high、両端を含む)。基準日・カレンダーの選択に左右されない絶対の日付。"""
    result = []
    for sid, name, dates in series:
        for when in dates:
            if low <= when <= high:
                result.append({
                    "key": "occ-gomi-%s-%s" % (sid, when.strftime("%Y%m%d")),
                    "seriesId": "ser-gomi-%s" % sid,
                    "name": name,
                    "date": when.isoformat(),
                    "icon": GARBAGE_ICON,
                    "dayOnly": True,
                })
    return result


def occurrences_between(state, low, high, garbage=(), today=None):
    """状態から返す回の一覧 (範囲 low .. high、両端を含む。日付・key の昇順。最大 MAX_OCCURRENCES 件)。
    `garbage` は parse_garbage の結果で、状態の garbage が true のときだけ薬の回に加える。
    上限を超えるときは薬の回を先に入れ、残りの枠に今日 (`today`) 以降のゴミの回を日付の近い順、
    それでも余れば過去のゴミの回を今日に近い順に入れる (`today` 省略時は low を今日とみなす)。"""
    base = date.fromisoformat(state["baseDate"])
    medicines = []
    for series, offset in PRESET_OCCURRENCES[state["preset"]]:
        when = base + timedelta(days=offset)
        if not low <= when <= high:
            continue
        name, icon = SERIES[series]
        key, sid = occurrence_ids(state["calendarId"], series, when)
        medicines.append({
            "key": key,
            "seriesId": sid,
            "name": sanitize_name(name),
            "date": when.isoformat(),
            "icon": icon,
        })
    order = lambda occ: (occ["date"], occ["key"])
    medicines.sort(key=order)
    if len(medicines) >= MAX_OCCURRENCES:
        return medicines[:MAX_OCCURRENCES]
    result = medicines
    if state["garbage"]:
        pivot = (low if today is None else today).isoformat()
        trash = sorted(garbage_occurrences(garbage, low, high), key=order)
        upcoming = [occ for occ in trash if occ["date"] >= pivot]
        past = [occ for occ in trash if occ["date"] < pivot]
        room = MAX_OCCURRENCES - len(medicines)
        result = medicines + upcoming[:room]
        room = MAX_OCCURRENCES - len(result)
        if room > 0:
            result += past[::-1][:room]
    result.sort(key=order)
    return result


def occurrences_for(state, today, garbage=()):
    """機器向け API の回の一覧 (範囲 today-pastDays .. today+60、両端を含む)。"""
    return occurrences_between(state, today - timedelta(days=state["pastDays"]),
                               today + timedelta(days=FUTURE_DAYS), garbage, today)


def build_medications(state, today, now_epoch, garbage=()):
    """機器向け API の 200 の応答 (dict)。fault は扱わない。"""
    if not state["enabled"]:
        return {"enabled": False, "pollSeconds": state["pollSeconds"]}
    status = state["status"]
    body = {"enabled": True, "status": status, "leadDays": state["leadDays"]}
    if status != "notConnected":
        body["timeZone"] = state["timeZone"]
        body["fetchedAt"] = max(0, int(now_epoch) - FETCHED_AGO[status])
    body["pollSeconds"] = state["pollSeconds"]
    body["occurrences"] = [] if status == "notConnected" else occurrences_for(state, today, garbage)
    return body


def build_schedule(state, today, now_epoch, garbage=()):
    """画面モックのダッシュボード用の世帯の予定 (dict)。機器の enabled・pastDays・fault に左右されない。
    範囲は今日の SCHEDULE_PAST_DAYS 日前から SCHEDULE_FUTURE_DAYS 日後 (両端を含む)。未接続は回を持たない。"""
    status = state["status"]
    body = {"status": status, "calendarId": state["calendarId"], "timeZone": state["timeZone"]}
    if status == "notConnected":
        body["occurrences"] = []
        return body
    body["fetchedAt"] = max(0, int(now_epoch) - FETCHED_AGO[status])
    body["occurrences"] = occurrences_between(state, today - timedelta(days=SCHEDULE_PAST_DAYS),
                                              today + timedelta(days=SCHEDULE_FUTURE_DAYS), garbage, today)
    return body


def encode_json(obj):
    """応答の本文: ensure_ascii=False の UTF-8。Content-Length はこのバイト長。"""
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


# 入れ子の深さ: トップの {} を 1 と数え、パネルは 8 まで受ける。DEEP_NESTING_DEPTH はそれを超える深さ。
DEEP_NESTING_DEPTH = 10


def malformed_body():
    """途中で切れた JSON (JSON として読めない)。"""
    return '{"enabled":true,"status":"ok","leadDays":2,"occurrences":[{"key":"occ-cal1-F-2'.encode("utf-8")


def deep_nesting_body(body):
    """正しい応答に、深さ DEEP_NESTING_DEPTH の入れ子になった知らないキーを足した本文。"""
    nested = 0
    for _ in range(DEEP_NESTING_DEPTH - 1):
        nested = [nested]
    return encode_json(dict(body, extra=nested))
