"""mock_state の単体テスト (python3 -m unittest)。"""

import copy
import json
import re
import unicodedata
import unittest
from datetime import date, timedelta
from pathlib import Path

import mock_state as ms

SAMPLES = Path(__file__).resolve().parents[2] / "app" / "src" / "test" / "resources" / "api" / "medications"
NOW = 1792629000  # 見本の fetchedAt と同じ固定の値

# 設計の「予定の並び」の表: preset -> (返す回 [(系列, B からの日数)], 期待する警告 [(系列, 日数, 段階)])
TABLE = {
    "normal": ([("F", 10), ("F", 41)], []),
    "soon": ([("F", 2), ("F", 33)], [("F", 2, "そろそろ")]),
    "today": ([("F", 0), ("F", 31)], [("F", 0, "今日")]),
    "overdue": ([("F", -1), ("F", 30)], [("F", -1, "未確認")]),
    "expired": ([("F", 22)], []),
    "two": ([("L", 0), ("F", 2)], [("L", 0, "今日"), ("F", 2, "そろそろ")]),
    "three": ([("M", -1), ("L", 0), ("F", 2)], [("M", -1, "未確認"), ("L", 0, "今日"), ("F", 2, "そろそろ")]),
    "longName": ([("M", 0)], [("M", 0, "今日")]),
    "none": ([], []),
}

# 月末・年末・うるう日をまたぐ基準日
BASE_DATES = [
    date(2026, 10, 22), date(2026, 10, 31), date(2026, 12, 31), date(2027, 2, 28),
    date(2028, 2, 28), date(2028, 2, 29), date(2028, 12, 31), date(2000, 1, 1), date(2099, 12, 31),
]


def state_for(base, **overrides):
    state = ms.initial_state(base)
    state.update(overrides)
    return state


def panel_warnings(occurrences, today, lead_days=2):
    """パネルの ReminderPolicy.warnings と同じ規則 (系列ごとに、警告開始日が来た回のうち最新の1回)。
    並びは段階 (未確認 → 今日 → そろそろ)、予定日の順 (カードの優先順位)。"""
    by_series = {}
    for occ in occurrences:
        by_series.setdefault(occ["seriesId"], []).append(occ)
    order = {"未確認": 0, "今日": 1, "そろそろ": 2}
    result = []
    for group in by_series.values():
        started = [o for o in group if date.fromisoformat(o["date"]) - timedelta(days=lead_days) <= today]
        if not started:
            continue
        current = max(started, key=lambda o: (o["date"], o["key"]))
        days = (date.fromisoformat(current["date"]) - today).days
        if days > lead_days:
            continue
        stage = "そろそろ" if days > 0 else "今日" if days == 0 else "未確認"
        result.append((current, stage))
    result.sort(key=lambda pair: (order[pair[1]], pair[0]["date"], pair[0]["key"]))
    return result


def series_letter(occ):
    return occ["seriesId"].rsplit("-", 1)[1]


def json_depth(value):
    """トップの {} / [] を 1 と数える。"""
    if isinstance(value, dict):
        return 1 + max((json_depth(v) for v in value.values()), default=0)
    if isinstance(value, list):
        return 1 + max((json_depth(v) for v in value), default=0)
    return 0


def is_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


def check_api_shape(test, body):
    """api.md の型・省略・範囲の規則を満たすか。"""
    if body["enabled"] is False:
        test.assertEqual(set(body), {"enabled", "pollSeconds"})
        return
    test.assertIs(body["enabled"], True)
    test.assertIn(body["status"], ms.STATUSES)
    test.assertTrue(is_int(body["leadDays"]) and 0 <= body["leadDays"] <= 14)
    test.assertTrue(is_int(body["pollSeconds"]) and 0 < body["pollSeconds"] < 2**31)
    if body["status"] == "notConnected":
        test.assertNotIn("timeZone", body)
        test.assertNotIn("fetchedAt", body)
        test.assertEqual(body["occurrences"], [])
    else:
        test.assertTrue(ms.valid_time_zone(body["timeZone"]))
        test.assertTrue(is_int(body["fetchedAt"]) and 0 <= body["fetchedAt"] < 2**53)
    occs = body["occurrences"]
    test.assertLessEqual(len(occs), 200)
    test.assertEqual(occs, sorted(occs, key=lambda o: (o["date"], o["key"])))
    keys = [o["key"] for o in occs]
    test.assertEqual(len(keys), len(set(keys)))
    for occ in occs:
        test.assertEqual(set(occ), {"key", "seriesId", "name", "date", "icon"})
        for field in ("key", "seriesId"):
            test.assertRegex(occ[field], r"[A-Za-z0-9._:-]{1,128}")
        test.assertTrue(1 <= len(occ["name"]) <= 40)
        test.assertNotEqual(occ["name"].strip(), "")
        test.assertFalse(any(unicodedata.category(c) in ("Cc", "Cf") for c in occ["name"]))
        test.assertRegex(occ["date"], r"[0-9]{4}-[0-9]{2}-[0-9]{2}")
        date.fromisoformat(occ["date"])
        test.assertIn(occ["icon"], ("pill", "drop"))
    test.assertLessEqual(len(ms.encode_json(body)), 64 * 1024)
    test.assertLessEqual(json_depth(body), 8)


class SampleShapeTest(unittest.TestCase):
    """見本 JSON と同じキー・型になる。"""

    def load(self, name):
        return json.loads((SAMPLES / (name + ".json")).read_text(encoding="utf-8"))

    def assertSameShape(self, built, sample):
        self.assertEqual(list(built), list(sample), "キーの並び")
        for key in sample:
            if key != "occurrences":
                self.assertIs(type(built[key]), type(sample[key]), key)
        self.assertIs(type(built.get("occurrences")), type(sample.get("occurrences")))
        for occ in sample.get("occurrences", []):
            for built_occ in built["occurrences"]:
                self.assertEqual(list(built_occ), list(occ))
                for key in occ:
                    self.assertIs(type(built_occ[key]), type(occ[key]), key)

    def test_samples_themselves_pass_the_shape_rules(self):
        for name in ("ok", "stale", "fetchFailed", "reauthRequired", "notConnected", "disabled"):
            with self.subTest(name):
                check_api_shape(self, self.load(name))

    def test_each_status_matches_its_sample(self):
        today = date(2026, 10, 22)
        for name in ("ok", "stale", "fetchFailed", "reauthRequired", "notConnected"):
            with self.subTest(name):
                state = state_for(today, preset="three", status=name)
                built = ms.build_medications(state, today, NOW)
                self.assertEqual(built["status"], name)
                self.assertSameShape(built, self.load(name))

    def test_disabled_matches_sample(self):
        built = ms.build_medications(state_for(date(2026, 10, 22), enabled=False), date(2026, 10, 22), NOW)
        self.assertSameShape(built, self.load("disabled"))
        self.assertEqual(set(built), {"enabled", "pollSeconds"})

    def test_every_combination_satisfies_api_rules(self):
        today = date(2026, 10, 22)
        for preset in ms.PRESETS:
            for status in ms.STATUSES:
                for enabled in (True, False):
                    for cal in ms.CALENDARS:
                        with self.subTest(preset=preset, status=status, enabled=enabled, cal=cal):
                            state = state_for(today, preset=preset, status=status, enabled=enabled, calendarId=cal)
                            check_api_shape(self, ms.build_medications(state, today, NOW))

    def test_fetched_at_is_never_negative_and_stale_is_older(self):
        today = date(2026, 10, 22)
        ok = ms.build_medications(state_for(today), today, NOW)["fetchedAt"]
        stale = ms.build_medications(state_for(today, status="stale"), today, NOW)["fetchedAt"]
        self.assertLess(stale, ok)
        self.assertEqual(ms.build_medications(state_for(today, status="stale"), today, 5)["fetchedAt"], 0)


class IdTest(unittest.TestCase):
    def test_calendar_token(self):
        self.assertEqual(ms.calendar_token("mock-cal-1"), "cal1")
        self.assertEqual(ms.calendar_token("mock-cal-2"), "cal2")

    def test_series_and_occurrence_ids(self):
        self.assertEqual(ms.series_id("mock-cal-1", "F"), "ser-cal1-F")
        self.assertEqual(ms.occurrence_key("mock-cal-1", "F", date(2026, 10, 24)), "occ-cal1-F-20261024")
        self.assertEqual(ms.occurrence_ids("mock-cal-1", "F", date(2026, 10, 24)),
                         ("occ-cal1-F-20261024", "ser-cal1-F"))

    def test_one_off_event_series_id_equals_key(self):
        key, sid = ms.occurrence_ids("mock-cal-1", "once", date(2026, 10, 30))
        self.assertEqual(key, "occ-cal1-once-20261030")
        self.assertEqual(sid, key)

    def test_built_ids_follow_the_rule(self):
        today = date(2026, 10, 22)
        state = state_for(today, preset="three")
        for occ in ms.build_medications(state, today, NOW)["occurrences"]:
            letter = series_letter(occ)
            self.assertEqual(occ["seriesId"], "ser-cal1-" + letter)
            self.assertEqual(occ["key"], "occ-cal1-%s-%s" % (letter, occ["date"].replace("-", "")))

    def test_switching_calendar_changes_every_key_and_series(self):
        today = date(2026, 10, 22)
        one = ms.build_medications(state_for(today, preset="three", calendarId="mock-cal-1"), today, NOW)
        two = ms.build_medications(state_for(today, preset="three", calendarId="mock-cal-2"), today, NOW)
        keys1 = {o["key"] for o in one["occurrences"]}
        keys2 = {o["key"] for o in two["occurrences"]}
        self.assertEqual(len(keys1), 3)
        self.assertFalse(keys1 & keys2)
        self.assertTrue(all("cal2" in k for k in keys2))
        self.assertFalse({o["seriesId"] for o in one["occurrences"]} & {o["seriesId"] for o in two["occurrences"]})
        self.assertEqual([o["date"] for o in one["occurrences"]], [o["date"] for o in two["occurrences"]])

    def test_same_occurrence_keeps_its_key_across_rebuilds_and_name_changes(self):
        today = date(2026, 10, 22)
        state = state_for(today, preset="soon")
        first = ms.build_medications(state, today, NOW)["occurrences"][0]["key"]
        later = ms.build_medications(state, today, NOW + 3600)["occurrences"][0]["key"]
        self.assertEqual(first, later)


class PresetTableTest(unittest.TestCase):
    def occurrences(self, preset, base, today=None, **overrides):
        today = today or base
        state = state_for(base, preset=preset, **overrides)
        return ms.build_medications(state, today, NOW)["occurrences"]

    def test_returned_occurrences_match_design_table_for_every_base_date(self):
        for base in BASE_DATES:
            for preset, (expected, _) in TABLE.items():
                with self.subTest(base=base.isoformat(), preset=preset):
                    got = [(series_letter(o), (date.fromisoformat(o["date"]) - base).days)
                           for o in self.occurrences(preset, base)]
                    self.assertEqual(got, expected)

    def test_warnings_match_design_table_for_every_base_date(self):
        for base in BASE_DATES:
            for preset, (_, expected) in TABLE.items():
                with self.subTest(base=base.isoformat(), preset=preset):
                    got = [(series_letter(o), (date.fromisoformat(o["date"]) - base).days, stage)
                           for o, stage in panel_warnings(self.occurrences(preset, base), base)]
                    self.assertEqual(got, expected)

    def test_three_shows_two_cards_and_one_more(self):
        warnings = panel_warnings(self.occurrences("three", date(2026, 10, 22)), date(2026, 10, 22))
        self.assertEqual(len(warnings), 3)
        self.assertEqual([series_letter(o) for o, _ in warnings[:2]], ["M", "L"])
        self.assertEqual(len(warnings) - 2, 1)

    def test_dates_are_real_calendar_days_across_boundaries(self):
        base = date(2028, 2, 28)
        got = [o["date"] for o in self.occurrences("normal", base)]
        self.assertEqual(got, ["2028-03-09", "2028-04-09"])  # うるう日 2/29 をはさんで +10, +41
        base = date(2026, 12, 31)
        self.assertEqual([o["date"] for o in self.occurrences("today", base)], ["2026-12-31", "2027-01-31"])
        base = date(2028, 2, 29)
        self.assertEqual([o["date"] for o in self.occurrences("overdue", base)], ["2028-02-28", "2028-03-30"])

    def test_long_name_is_forty_code_points(self):
        occ = self.occurrences("longName", date(2026, 10, 22))[0]
        self.assertEqual(len(occ["name"]), 40)
        self.assertEqual(occ["icon"], "pill")

    def test_icons_follow_series(self):
        occs = {series_letter(o): o["icon"] for o in self.occurrences("three", date(2026, 10, 22))}
        self.assertEqual(occs, {"F": "pill", "L": "drop", "M": "pill"})

    def test_names_pass_sender_rules(self):
        for name, _ in ms.SERIES.values():
            self.assertEqual(ms.sanitize_name(name), name)
            self.assertLessEqual(len(name), 40)

    def test_stage_advances_with_the_calendar(self):
        base = date(2026, 10, 22)
        occs = self.occurrences("soon", base)
        expectations = [(0, "そろそろ"), (1, "そろそろ"), (2, "今日"), (3, "未確認")]
        for days, stage in expectations:
            with self.subTest(days=days):
                warnings = panel_warnings(occs, base + timedelta(days=days))
                self.assertEqual([(series_letter(o), s) for o, s in warnings], [("F", stage)])

    def test_old_unconfirmed_is_not_promoted_when_next_warning_starts(self):
        # normal: B+10 と B+41。B+39 で次の回 (B+41) の警告が始まり、B+10 の未確認は出ない。
        base = date(2026, 10, 22)
        occs = self.occurrences("normal", base, today=base + timedelta(days=39), pastDays=31)
        warnings = panel_warnings(occs, base + timedelta(days=39))
        self.assertEqual([(o["date"], s) for o, s in warnings], [((base + timedelta(days=41)).isoformat(), "そろそろ")])


class RangeTest(unittest.TestCase):
    def dates(self, preset, base, today, **overrides):
        state = state_for(base, preset=preset, **overrides)
        return [o["date"] for o in ms.build_medications(state, today, NOW)["occurrences"]]

    def test_past_days_boundary(self):
        base = today = date(2026, 10, 22)
        yesterday = (base - timedelta(days=1)).isoformat()
        self.assertEqual(self.dates("overdue", base, today, pastDays=1)[0], yesterday)
        self.assertNotIn(yesterday, self.dates("overdue", base, today, pastDays=0))
        # expired: B-8 は pastDays=7 では返さず、8 なら返す
        eight = (base - timedelta(days=8)).isoformat()
        self.assertNotIn(eight, self.dates("expired", base, today, pastDays=7))
        self.assertIn(eight, self.dates("expired", base, today, pastDays=8))
        self.assertEqual(self.dates("expired", base, today)[0], (base + timedelta(days=22)).isoformat())

    def test_past_boundary_is_measured_from_today_not_base_date(self):
        base = date(2026, 10, 22)
        today = base + timedelta(days=5)  # B-1 は today-6、B+0 の回は today-5
        self.assertEqual(self.dates("overdue", base, today, pastDays=6)[0], (base - timedelta(days=1)).isoformat())
        self.assertEqual(self.dates("overdue", base, today, pastDays=5)[0], (base + timedelta(days=30)).isoformat())

    def test_future_boundary_is_today_plus_60_inclusive(self):
        today = date(2026, 10, 22)
        # normal の2回目は B+41。B = today+19 なら today+60 (含む)、today+20 なら today+61 (外)。
        inside = self.dates("normal", today + timedelta(days=19), today)
        self.assertEqual(inside[-1], (today + timedelta(days=60)).isoformat())
        outside = self.dates("normal", today + timedelta(days=20), today)
        self.assertEqual(outside, [(today + timedelta(days=30)).isoformat()])

    def test_range_is_across_leap_day(self):
        today = date(2028, 1, 1)
        got = self.dates("normal", date(2028, 1, 20), today)
        self.assertEqual(got, ["2028-01-30", "2028-03-01"])


class UpdateTest(unittest.TestCase):
    TODAY = date(2026, 10, 22)

    def update(self, state, patch):
        return ms.apply_update(state, patch, lambda zone: self.TODAY)

    def test_partial_update_changes_only_given_keys(self):
        state = ms.initial_state(self.TODAY)
        new = self.update(state, {"status": "stale", "leadDays": 3, "enabled": False})
        expected = dict(state, status="stale", leadDays=3, enabled=False)
        self.assertEqual(new, expected)
        self.assertEqual(state, ms.initial_state(self.TODAY), "元の状態は変えない")

    def test_all_keys_are_accepted(self):
        new = self.update(ms.initial_state(self.TODAY), {
            "preset": "three", "baseDate": "2026-11-01", "status": "stale", "calendarId": "mock-cal-2",
            "enabled": False, "leadDays": 14, "pastDays": 31, "fault": "slow", "pollSeconds": 86400,
            "timeZone": "UTC"})
        self.assertEqual(set(new), set(ms.STATE_KEYS))
        self.assertEqual(new["baseDate"], "2026-11-01")

    def test_preset_updates_base_date_only_when_chosen(self):
        state = ms.initial_state(date(2026, 1, 1))
        self.assertEqual(self.update(state, {"status": "stale"})["baseDate"], "2026-01-01")
        self.assertEqual(self.update(state, {"leadDays": 3})["baseDate"], "2026-01-01")
        self.assertEqual(self.update(state, {"preset": "soon"})["baseDate"], self.TODAY.isoformat())
        self.assertEqual(self.update(state, {"preset": "normal"})["baseDate"], self.TODAY.isoformat())

    def test_explicit_base_date_wins_over_preset(self):
        new = self.update(ms.initial_state(self.TODAY), {"preset": "soon", "baseDate": "2028-02-29"})
        self.assertEqual((new["preset"], new["baseDate"]), ("soon", "2028-02-29"))

    def test_preset_uses_today_in_the_new_time_zone(self):
        zones = []
        ms.apply_update(ms.initial_state(self.TODAY), {"preset": "soon", "timeZone": "UTC"},
                        lambda zone: zones.append(zone) or self.TODAY)
        self.assertEqual(zones, ["UTC"])

    def test_rejects_unknown_keys_and_non_objects(self):
        state = ms.initial_state(self.TODAY)
        for patch in ({"secret": "x"}, {"status": "ok", "extra": 1}, [], "text", 3, None):
            with self.subTest(patch=patch):
                with self.assertRaises(ms.StateError):
                    self.update(state, patch)

    def test_rejects_wrong_types_and_ranges(self):
        state = ms.initial_state(self.TODAY)
        bad = [
            {"enabled": 1}, {"enabled": "true"}, {"enabled": None},
            {"leadDays": True}, {"leadDays": 2.0}, {"leadDays": "2"}, {"leadDays": -1}, {"leadDays": 15},
            {"leadDays": None}, {"leadDays": 1e2},
            {"pastDays": True}, {"pastDays": 7.5}, {"pastDays": -1}, {"pastDays": 32},
            {"pollSeconds": False}, {"pollSeconds": 0}, {"pollSeconds": 86401}, {"pollSeconds": 2**40},
            {"preset": "unknown"}, {"preset": 1}, {"preset": None}, {"preset": ["normal"]},
            {"status": "OK"}, {"status": "unknown"}, {"fault": "boom"}, {"fault": True},
            {"calendarId": "mock-cal-3"}, {"calendarId": "cal1"}, {"calendarId": 1},
            {"baseDate": "2026-02-30"}, {"baseDate": "2026-1-1"}, {"baseDate": "20261022"},
            {"baseDate": "２０２６-１０-２２"}, {"baseDate": "2026-10-22T00:00"}, {"baseDate": "1999-12-31"},
            {"baseDate": "2100-01-01"}, {"baseDate": 20261022}, {"baseDate": None},
            {"timeZone": "+09:00"}, {"timeZone": "UTC+9"}, {"timeZone": "Z"}, {"timeZone": "JST"},
            {"timeZone": "Asia/Nowhere"}, {"timeZone": "../etc/passwd"}, {"timeZone": ""}, {"timeZone": 9},
            {"timeZone": "Asia/Tokyo\n"},
        ]
        for patch in bad:
            with self.subTest(patch=patch):
                with self.assertRaises(ms.StateError):
                    self.update(state, patch)

    def test_one_bad_value_rejects_the_whole_update(self):
        state = ms.initial_state(self.TODAY)
        before = copy.deepcopy(state)
        with self.assertRaises(ms.StateError):
            self.update(state, {"status": "stale", "leadDays": 99})
        self.assertEqual(state, before)

    def test_time_zone_forms(self):
        for good in ("Asia/Tokyo", "UTC", "America/New_York", "America/Argentina/Buenos_Aires", "Etc/GMT+9"):
            self.assertTrue(ms.valid_time_zone(good), good)
        for bad in ("+09:00", "UTC+9", "Z", "JST", "utc", "Asia", "Asia/", "/Tokyo", "Asia/Tokyo/..", "Asia/Tôkyo"):
            self.assertFalse(ms.valid_time_zone(bad), bad)

    def test_json_parsing_rejects_nan_duplicates_and_garbage(self):
        self.assertEqual(ms.parse_update_json('{"leadDays": 3}'), {"leadDays": 3})
        for text in ('{"leadDays": NaN}', '{"leadDays": Infinity}', '{"leadDays": -Infinity}',
                     '{"leadDays": 1, "leadDays": 2}', '{', '', 'nope', '{"a": }'):
            with self.subTest(text=text):
                with self.assertRaises(ms.StateError):
                    ms.parse_update_json(text)

    def test_json_parsing_survives_deep_nesting(self):
        with self.assertRaises(ms.StateError):
            ms.parse_update_json("[" * 200000)
        with self.assertRaises(ms.StateError):
            ms.apply_update(ms.initial_state(self.TODAY), ms.parse_update_json('{"a":' * 50000), lambda z: self.TODAY)

    def test_json_floats_are_not_integers(self):
        state = ms.initial_state(self.TODAY)
        for text in ('{"leadDays": 2.0}', '{"leadDays": 1e0}', '{"pastDays": 7.0}'):
            with self.subTest(text=text):
                with self.assertRaises(ms.StateError):
                    self.update(state, ms.parse_update_json(text))

    def test_public_state_has_exactly_the_documented_keys(self):
        state = ms.initial_state(self.TODAY)
        self.assertEqual(list(ms.public_state(state)), list(ms.STATE_KEYS))
        self.assertEqual(state["preset"], "normal")
        self.assertEqual((state["status"], state["calendarId"], state["enabled"], state["leadDays"],
                          state["pastDays"], state["fault"], state["pollSeconds"], state["timeZone"]),
                         ("ok", "mock-cal-1", True, 2, 7, "none", 15, "Asia/Tokyo"))


class NameAndFaultTest(unittest.TestCase):
    def test_sanitize_name(self):
        self.assertEqual(ms.sanitize_name("  フィラリア 　"), "フィラリア")
        self.assertEqual(ms.sanitize_name("\u200bフィラ\u0000リア\u202e"), "フィラリア")
        self.assertEqual(ms.sanitize_name("a" * 41), "a" * 40)
        self.assertEqual(ms.sanitize_name("\U0001F48A" * 41), "\U0001F48A" * 40)  # コードポイントで数える
        self.assertEqual(ms.sanitize_name(" \t　"), "")

    def test_today_in_zone(self):
        # 2026-10-22 16:00 UTC は東京では翌日
        epoch = 1792684800  # 2026-10-22T16:00:00Z
        self.assertEqual(ms.today_in_zone("UTC", epoch), date(2026, 10, 22))
        self.assertEqual(ms.today_in_zone("Asia/Tokyo", epoch), date(2026, 10, 23))

    def test_malformed_body_is_not_json(self):
        with self.assertRaises(ValueError):
            json.loads(ms.malformed_body().decode("utf-8"))

    def test_deep_nesting_body_is_valid_json_nested_past_the_limit(self):
        today = date(2026, 10, 22)
        body = ms.build_medications(state_for(today), today, NOW)
        parsed = json.loads(ms.deep_nesting_body(body).decode("utf-8"))
        self.assertGreaterEqual(json_depth(parsed), 9)
        self.assertEqual({k: v for k, v in parsed.items() if k != "extra"}, body)
        self.assertLessEqual(json_depth(body), 8)

    def test_encode_json_is_utf8_without_escapes(self):
        raw = ms.encode_json({"name": "フロントライン"})
        self.assertIn("フロントライン".encode("utf-8"), raw)
        self.assertNotIn(b"\\u", raw)
        self.assertGreater(len(raw), len(raw.decode("utf-8")))

    def test_response_never_mentions_a_secret_field(self):
        today = date(2026, 10, 22)
        raw = ms.encode_json(ms.build_medications(state_for(today, preset="three"), today, NOW)).decode()
        self.assertIsNone(re.search(r"secret", raw, re.I))


if __name__ == "__main__":
    unittest.main()
