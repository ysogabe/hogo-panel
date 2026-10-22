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
    "leadDays", "pastDays", "fault", "pollSeconds", "timeZone",
)

LEAD_DAYS_RANGE = (0, 14)
PAST_DAYS_RANGE = (0, 31)  # パネルが受ける -31 日まで
POLL_SECONDS_RANGE = (1, 86400)  # パネルは 10〜3600 に収める。収める動作を評価できるよう広めに許す
FUTURE_DAYS = 60
SCHEDULE_PAST_DAYS = 7  # ダッシュボードの「直近の投薬予定」の範囲
SCHEDULE_FUTURE_DAYS = 30
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
    if "enabled" in update:
        if not isinstance(update["enabled"], bool):
            raise StateError("invalid value")
        new["enabled"] = update["enabled"]
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


def occurrences_between(state, low, high):
    """状態から返す回の一覧 (範囲 low .. high、両端を含む。日付・key の昇順)。"""
    base = date.fromisoformat(state["baseDate"])
    result = []
    for series, offset in PRESET_OCCURRENCES[state["preset"]]:
        when = base + timedelta(days=offset)
        if not low <= when <= high:
            continue
        name, icon = SERIES[series]
        key, sid = occurrence_ids(state["calendarId"], series, when)
        result.append({
            "key": key,
            "seriesId": sid,
            "name": sanitize_name(name),
            "date": when.isoformat(),
            "icon": icon,
        })
    result.sort(key=lambda occ: (occ["date"], occ["key"]))
    return result


def occurrences_for(state, today):
    """機器向け API の回の一覧 (範囲 today-pastDays .. today+60、両端を含む)。"""
    return occurrences_between(state, today - timedelta(days=state["pastDays"]),
                               today + timedelta(days=FUTURE_DAYS))


def build_medications(state, today, now_epoch):
    """機器向け API の 200 の応答 (dict)。fault は扱わない。"""
    if not state["enabled"]:
        return {"enabled": False, "pollSeconds": state["pollSeconds"]}
    status = state["status"]
    body = {"enabled": True, "status": status, "leadDays": state["leadDays"]}
    if status != "notConnected":
        body["timeZone"] = state["timeZone"]
        body["fetchedAt"] = max(0, int(now_epoch) - FETCHED_AGO[status])
    body["pollSeconds"] = state["pollSeconds"]
    body["occurrences"] = [] if status == "notConnected" else occurrences_for(state, today)
    return body


def build_schedule(state, today, now_epoch):
    """画面モックのダッシュボード用の世帯の予定 (dict)。機器の enabled・pastDays・fault に左右されない。
    範囲は今日の SCHEDULE_PAST_DAYS 日前から SCHEDULE_FUTURE_DAYS 日後 (両端を含む)。未接続は回を持たない。"""
    status = state["status"]
    body = {"status": status, "calendarId": state["calendarId"], "timeZone": state["timeZone"]}
    if status == "notConnected":
        body["occurrences"] = []
        return body
    body["fetchedAt"] = max(0, int(now_epoch) - FETCHED_AGO[status])
    body["occurrences"] = occurrences_between(state, today - timedelta(days=SCHEDULE_PAST_DAYS),
                                              today + timedelta(days=SCHEDULE_FUTURE_DAYS))
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
