// 画面モックの動き。表示は /mock/state と /mock/preview から作り、予定の計算はしない。
// API の値は textContent だけで画面に入れる (HTML の文字列として組み立てない)。
"use strict";
(function () {
  const POLL_MS = 2000;
  const UPCOMING_PAST_DAYS = 7;
  const UPCOMING_FUTURE_DAYS = 30;
  const UPCOMING_MAX = 5;
  const DEVICE_PATH = /^\/api\/agent\/[^/]+\/medications$/;

  // 表示用の名前 (Mock の値 -> 画面の文字)。知らない値はそのまま出す。
  const CALENDAR_NAMES = { "mock-cal-1": "ねこの投薬", "mock-cal-2": "家族" };
  const STATUS = {
    ok: { label: "正常", tone: "sage", text: "接続済みです。予定を取得できています。" },
    stale: { label: "古い", tone: "amber", text: "接続済みですが、予定がしばらく更新されていません。" },
    fetchFailed: { label: "取得失敗", tone: "terracotta", text: "予定を取得できませんでした。前回の予定を表示しています。" },
    reauthRequired: { label: "再接続が必要", tone: "terracotta", text: "Google の認可が切れています。再接続してください。" },
    notConnected: { label: "未接続", tone: "muted", text: "Google カレンダーに接続されていません。" },
  };
  const PRESETS = {
    normal: ["通常", "警告なし"],
    soon: ["そろそろ", "フィラリア予防薬が「そろそろ」"],
    today: ["今日", "フィラリア予防薬が「今日」"],
    overdue: ["未確認", "フィラリア予防薬が「未確認」"],
    expired: ["期限切れ", "警告なし（8 日前の回は範囲外）"],
    two: ["2 件", "フロントラインが「今日」、フィラリア予防薬が「そろそろ」"],
    three: ["3 件", "長い薬名が「未確認」、フロントラインが「今日」のカード 2 枚＋「ほか 1 件」"],
    longName: ["長い薬名", "長い薬名が「今日」（末尾を「…」で切る）"],
    none: ["予定なし", "警告なし"],
  };
  const FAULTS = {
    none: "なし",
    unauthorized: "401（認証エラー）",
    serverError: "500（サーバーエラー）",
    slow: "遅い（12 秒待つ）",
    malformed: "壊れた JSON",
    deepNesting: "深い入れ子",
  };

  const $ = (id) => document.getElementById(id);
  const WEEKDAYS = ["日", "月", "火", "水", "木", "金", "土"];

  let doc = null; // GET /mock/state
  let preview = null; // GET /mock/preview
  let requests = [];
  let epoch = 0; // POST のたびに進め、古い読み込みの結果で上書きしない
  let posting = 0;
  const controls = {}; // 評価用の入力欄 (キー -> 要素)

  // --- 小さな道具 ---
  function text(node, value) {
    node.textContent = value;
  }

  function make(tag, className, content) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (content !== undefined) node.textContent = content;
    return node;
  }

  function setHidden(node, hidden) {
    node.hidden = hidden;
  }

  function label(map, key) {
    return Object.prototype.hasOwnProperty.call(map, key) ? map[key] : key;
  }

  function dayNumber(iso) {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
    if (!m) return NaN;
    return Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])) / 86400000;
  }

  function dateLabel(iso) {
    const n = dayNumber(iso);
    if (Number.isNaN(n)) return iso;
    const d = new Date(n * 86400000);
    return (d.getUTCMonth() + 1) + "月" + d.getUTCDate() + "日（" + WEEKDAYS[d.getUTCDay()] + "）";
  }

  function formatEpoch(epochSeconds, timeZone) {
    const date = new Date(epochSeconds * 1000);
    try {
      return new Intl.DateTimeFormat("ja-JP", { timeZone: timeZone, dateStyle: "medium", timeStyle: "medium" }).format(date);
    } catch (e) {
      return date.toLocaleString("ja-JP");
    }
  }

  function formatClock(epochSeconds) {
    return new Date(epochSeconds * 1000).toLocaleTimeString("ja-JP", { hour12: false });
  }

  function ago(seconds) {
    if (seconds < 60) return "いま";
    if (seconds < 3600) return Math.floor(seconds / 60) + " 分前";
    if (seconds < 86400) return Math.floor(seconds / 3600) + " 時間前";
    return Math.floor(seconds / 86400) + " 日前";
  }

  function setBadge(node, info) {
    node.className = "badge tone-" + info.tone;
    text(node, info.label);
  }

  // kind: "connection" (読み込みの失敗。次に読めたら消す) / "rejected" (設定の拒否。次の操作まで残す)
  let bannerKind = "";
  function banner(message, kind) {
    const node = $("banner");
    if (message) text(node, message);
    setHidden(node, !message);
    bannerKind = message ? kind : "";
  }

  // --- 通信 ---
  async function getJson(path) {
    const res = await fetch(path, { cache: "no-store" });
    if (!res.ok) throw new Error(path + " " + res.status);
    return res.json();
  }

  async function refresh(force) {
    const mine = epoch;
    try {
      const [s, p, r] = await Promise.all([getJson("/mock/state"), getJson("/mock/preview"), getJson("/mock/requests")]);
      if (mine !== epoch || posting > 0) return;
      doc = s;
      preview = p;
      requests = Array.isArray(r.requests) ? r.requests : [];
      if (bannerKind === "connection") banner("");
      render(force === true);
    } catch (e) {
      banner("Mock API に接続できません。server.py が動いているか確かめてください。", "connection");
    }
  }

  async function post(patch) {
    epoch += 1;
    posting += 1;
    let failed = false;
    try {
      const res = await fetch("/mock/state", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(patch),
      });
      const body = await res.json().catch(() => ({}));
      if (!res.ok) {
        failed = true;
        banner("設定を受け付けられませんでした（" + res.status + (body && body.error ? " " + body.error : "") + "）。", "rejected");
      } else {
        banner("");
      }
    } catch (e) {
      failed = true;
      banner("Mock API に接続できません。server.py が動いているか確かめてください。", "connection");
    } finally {
      posting -= 1;
      epoch += 1;
    }
    await refresh(failed); // 失敗したときは入力欄を本当の状態に戻す
  }

  // --- 表示 ---
  function render(force) {
    if (!doc || !preview) return;
    renderTop();
    renderCalendar(force);
    renderDevice();
    renderEval(force);
    renderRequests();
  }

  function renderTop() {
    const state = doc.state;
    const list = $("upcoming");
    const note = $("upcoming-note");
    const statusNote = $("status-note");
    const items = [];

    if (state.enabled && state.status !== "notConnected" && Array.isArray(preview.occurrences)) {
      const today = dayNumber(doc.today);
      const inRange = preview.occurrences.filter((occ) => {
        const diff = dayNumber(occ.date) - today;
        return diff >= -UPCOMING_PAST_DAYS && diff <= UPCOMING_FUTURE_DAYS;
      });
      inRange.sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : a.key < b.key ? -1 : 1));
      items.push(...inRange.slice(0, UPCOMING_MAX));
    }

    const rows = items.map((occ) => {
      const diff = dayNumber(occ.date) - dayNumber(doc.today);
      const li = make("li", diff < 0 ? "occ-past" : "");
      const icon = make("span", "occ-icon");
      const shape = make("span", occ.icon === "drop" ? "icon-drop" : "icon-pill");
      shape.title = occ.icon === "drop" ? "滴下薬" : "錠剤";
      icon.appendChild(shape);
      const when = make("div", "occ-date", dateLabel(occ.date));
      when.appendChild(make("small", "", diff === 0 ? "今日" : diff > 0 ? diff + " 日後" : (-diff) + " 日前"));
      li.append(icon, when, make("div", "occ-name", occ.name));
      if (diff < 0) {
        const badge = make("span", "badge tone-terracotta", "過ぎています");
        li.appendChild(badge);
      }
      return li;
    });
    list.replaceChildren(...rows);

    let message = "";
    let showLink = false;
    if (!state.enabled) {
      message = "この機器は投薬表示がオフです。Mock はオフの機器へ予定を返さないため、この一覧も空になります。";
    } else if (state.status === "notConnected") {
      message = "カレンダー未接続です。";
      showLink = true;
    } else if (items.length === 0) {
      message = "直近の投薬予定はありません。";
    }
    text(note, message);
    setHidden(note, !message);
    setHidden($("goto-calendar-wrap"), !showLink);

    const info = label(STATUS, state.status);
    if (state.status === "stale" || state.status === "fetchFailed" || state.status === "reauthRequired") {
      text(statusNote, "カレンダー連携: " + info.label + "。" + info.text);
      statusNote.className = "note-box" + (info.tone === "terracotta" ? " is-error" : "");
      setHidden(statusNote, false);
    } else {
      setHidden(statusNote, true);
    }

    // 機器のカード
    const card = $("device-card");
    card.classList.toggle("is-off", !state.enabled);
    setBadge($("device-badge"), state.enabled
      ? { label: "投薬表示: オン", tone: "sage" }
      : { label: "投薬表示: オフ", tone: "terracotta" });
    setHidden($("device-off-note"), state.enabled);
    const last = requests.filter((r) => DEVICE_PATH.test(String(r.path))).pop();
    text($("device-last-fetch"), last
      ? formatClock(last.time) + "（" + ago(Math.max(0, doc.serverTime - last.time)) + "・ステータス " + last.status + "）"
      : "まだありません");
  }

  function renderCalendar(force) {
    const state = doc.state;
    const info = label(STATUS, state.status);
    setBadge($("conn-badge"), info);
    text($("conn-text"), info.text);
    const zone = preview.timeZone || state.timeZone;
    text($("conn-fetched"), typeof preview.fetchedAt === "number"
      ? formatEpoch(preview.fetchedAt, zone) + "（" + ago(Math.max(0, doc.serverTime - preview.fetchedAt)) + "）"
      : state.enabled ? "—（未接続）" : "—（この機器は表示オフのため Mock は取得時刻を返しません）");

    const connect = $("btn-connect");
    text(connect, state.status === "reauthRequired" ? "Google と再接続" : "Google と接続");
    connect.disabled = !(state.status === "notConnected" || state.status === "reauthRequired");
    $("btn-disconnect").disabled = state.status === "notConnected";

    const connected = state.status !== "notConnected";
    for (const input of document.querySelectorAll("#calendar-choices input")) {
      input.disabled = !connected;
      if (force || document.activeElement !== input) input.checked = input.value === state.calendarId;
      input.closest(".radio").classList.toggle("is-checked", input.checked);
      input.closest(".radio").classList.toggle("is-disabled", !connected);
    }
    text($("calendar-note"), connected
      ? "選んだカレンダーの予定を読みます（Mock の calendarId が変わり、予定の key も変わります）。"
      : "接続するとカレンダーを選べます。");
    text($("lead-days"), String(state.leadDays));
  }

  function renderDevice() {
    const state = doc.state;
    const sw = $("switch-enabled");
    sw.setAttribute("aria-checked", state.enabled ? "true" : "false");
    text($("switch-text"), state.enabled ? "オン" : "オフ");
    text($("switch-hint"), state.enabled
      ? "オン: パネルは予定を取得し、警告を出します。"
      : "オフ: パネルは予定を取得せず、投薬の警告を出しません。");
    text($("device-poll"), "パネルは " + state.pollSeconds + " 秒ごとに取得します（パネル側は 10〜3600 秒に収めます）");
  }

  function renderEval(force) {
    const state = doc.state;
    text($("st-base"), state.baseDate);
    text($("st-today"), doc.today + "（" + state.timeZone + "）");
    text($("st-time"), formatEpoch(doc.serverTime, state.timeZone));
    text($("st-cal"), label(CALENDAR_NAMES, state.calendarId) + "（" + state.calendarId + "）");

    const skew = $("st-skew");
    if (state.baseDate !== doc.today) {
      text(skew, "基準日が今日と違います。予定は基準日から作られるので、警告の段階が期待表で想定する今日とずれます。「予定の並び」を選び直すと、基準日が今日になります。");
      setHidden(skew, false);
    } else {
      setHidden(skew, true);
    }

    for (const key of Object.keys(controls)) syncControl(key, state[key], force);

    const expect = label(PRESETS, state.preset);
    text($("preset-expect"), "期待するパネルの表示（今日 = 基準日・警告日数 2 のとき）: " + (Array.isArray(expect) ? expect[1] : expect));

    const fault = $("fault-note");
    if (state.fault !== "none") {
      text(fault, "通信の失敗「" + label(FAULTS, state.fault) + "」が有効です。パネルへの応答だけに効き、この画面の表示は変わりません。");
      fault.className = "note-box is-error";
      setHidden(fault, false);
    } else {
      setHidden(fault, true);
    }
  }

  function syncControl(key, value, force) {
    const input = controls[key];
    if (!input) return;
    if (!force && document.activeElement === input) return; // 入力中は上書きしない
    if (input.type === "checkbox") {
      input.checked = value === true;
    } else if (String(input.value) !== String(value)) {
      input.value = String(value);
    }
  }

  function renderRequests() {
    const body = $("requests-body");
    const rows = requests.slice().reverse().map((r) => {
      const tr = document.createElement("tr");
      const bad = r.status !== 200;
      tr.append(
        make("td", "", formatClock(r.time)),
        make("td", "", String(r.method)),
        make("td", "path", String(r.path)),
        make("td", bad ? "status-bad" : "status-ok", String(r.status)),
        make("td", "", String(r.fault)),
      );
      return tr;
    });
    body.replaceChildren(...rows);
    setHidden($("requests-empty"), rows.length > 0);
  }

  // --- 部品の組み立て (一度だけ) ---
  function addField(parent, key, labelText, input, unit) {
    const row = make("div", "field");
    const lab = make("label", "", labelText);
    input.id = "ctl-" + key;
    lab.htmlFor = input.id;
    row.appendChild(lab);
    if (unit) {
      const wrap = make("div", "field-inline");
      wrap.append(input, make("span", "unit", unit));
      row.appendChild(wrap);
    } else {
      row.appendChild(input);
    }
    parent.appendChild(row);
    controls[key] = input;
  }

  function selectFor(key, values, labelOf) {
    const select = document.createElement("select");
    for (const value of values) select.appendChild(new Option(labelOf(value), value));
    select.addEventListener("change", () => post({ [key]: select.value }));
    return select;
  }

  function numberFor(key, min, max) {
    const input = document.createElement("input");
    input.type = "number";
    input.min = String(min);
    input.max = String(max);
    input.step = "1";
    input.addEventListener("change", () => {
      if (input.value === "") return;
      post({ [key]: Number(input.value) });
    });
    return input;
  }

  function buildControls(choices) {
    const fields = $("eval-fields");
    const presetLabel = (v) => {
      const entry = label(PRESETS, v);
      return v + "（" + (Array.isArray(entry) ? entry[0] : entry) + "）";
    };
    addField(fields, "preset", "予定の並び", selectFor("preset", choices.preset, presetLabel));
    addField(fields, "status", "接続状態", selectFor("status", choices.status, (v) => v + "（" + label(STATUS, v).label + "）"));

    const enabled = document.createElement("input");
    enabled.type = "checkbox";
    enabled.addEventListener("change", () => post({ enabled: enabled.checked }));
    addField(fields, "enabled", "表示のオン・オフ", enabled);

    addField(fields, "leadDays", "警告を始める日数", numberFor("leadDays", 0, 14), "日（0〜14）");
    addField(fields, "pastDays", "過去の日数", numberFor("pastDays", 0, 31), "日（0〜31）");
    addField(fields, "pollSeconds", "取得間隔", numberFor("pollSeconds", 1, 86400), "秒（1〜86400）");
    addField(fields, "fault", "通信の失敗", selectFor("fault", choices.fault, (v) => v + "（" + label(FAULTS, v) + "）"));

    const base = document.createElement("input");
    base.type = "date";
    base.addEventListener("change", () => {
      if (base.value) post({ baseDate: base.value });
    });
    addField(fields, "baseDate", "基準日", base);

    const zone = document.createElement("input");
    zone.type = "text";
    zone.maxLength = 64;
    zone.spellcheck = false;
    zone.addEventListener("change", () => {
      if (zone.value.trim()) post({ timeZone: zone.value.trim() });
    });
    addField(fields, "timeZone", "タイムゾーン", zone);

    // カレンダー連携タブの選択肢 (Mock の calendarId に対応)
    const group = $("calendar-choices");
    for (const id of choices.calendarId) {
      const row = make("label", "radio");
      const radio = document.createElement("input");
      radio.type = "radio";
      radio.name = "calendar";
      radio.value = id;
      radio.addEventListener("change", () => {
        if (radio.checked) post({ calendarId: id });
      });
      const name = make("span", "", label(CALENDAR_NAMES, id));
      row.append(radio, name, make("small", "", id));
      group.appendChild(row);
    }
  }

  // --- タブ ---
  function showTab(name, focus) {
    for (const tab of document.querySelectorAll(".tab")) {
      const on = tab.dataset.tab === name;
      tab.setAttribute("aria-selected", on ? "true" : "false");
      tab.tabIndex = on ? 0 : -1;
      if (on && focus) tab.focus();
    }
    for (const panel of document.querySelectorAll(".tabpanel")) {
      panel.hidden = panel.id !== "tab-" + name;
    }
    history.replaceState(null, "", "#" + name);
  }

  function setupTabs() {
    const names = Array.from(document.querySelectorAll(".tab")).map((tab) => tab.dataset.tab);
    for (const tab of document.querySelectorAll(".tab")) {
      tab.addEventListener("click", () => showTab(tab.dataset.tab, false));
      tab.addEventListener("keydown", (event) => {
        const i = names.indexOf(tab.dataset.tab);
        if (event.key === "ArrowRight") showTab(names[(i + 1) % names.length], true);
        else if (event.key === "ArrowLeft") showTab(names[(i + names.length - 1) % names.length], true);
        else return;
        event.preventDefault();
      });
    }
    $("goto-calendar").addEventListener("click", () => showTab("calendar", true));
    const initial = location.hash.slice(1);
    showTab(names.indexOf(initial) >= 0 ? initial : "top", false);
  }

  // --- 起動 ---
  async function start() {
    setupTabs();
    $("btn-connect").addEventListener("click", () => post({ status: "ok" }));
    $("btn-disconnect").addEventListener("click", () => post({ status: "notConnected" }));
    $("switch-enabled").addEventListener("click", () => {
      if (doc) post({ enabled: !doc.state.enabled });
    });

    // 評価用の入力欄は選べる値が分かってから作る。読めなければ待って、もう一度試す。
    let built = false;
    while (!doc) {
      try {
        if (!built) {
          const first = await getJson("/mock/state");
          buildControls(first.choices);
          built = true;
        }
      } catch (e) {
        banner("Mock API に接続できません。server.py が動いているか確かめてください。", "connection");
        await new Promise((resolve) => setTimeout(resolve, POLL_MS));
        continue;
      }
      await refresh(true);
      if (!doc) await new Promise((resolve) => setTimeout(resolve, POLL_MS));
    }
    const loop = async () => {
      if (!document.hidden) await refresh(false);
      setTimeout(loop, POLL_MS);
    };
    setTimeout(loop, POLL_MS);
  }

  start();
})();
