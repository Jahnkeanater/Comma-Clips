"use strict";
const $ = (id) => document.getElementById(id);
const SEG = 60, FPS = 20;
const store = {
  get(k, d) { try { const v = localStorage.getItem("cc_" + k); return v === null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem("cc_" + k, JSON.stringify(v)); } catch {} },
};

const state = {
  routes: [], route: null, playable: [], sel: { start: 0, end: 0 },
  opts: Object.assign({ camera: "road", osd: true, units: "mph", mode: "exact", quality: "high", hud: false, audio: true }, store.get("opts", {})),
  tel: new Map(), telLoading: new Set(), hls: null, tab: "drives",
};

async function api(path, opts) {
  const r = await fetch(path, opts);
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || r.statusText);
  return j;
}

// ---------- formatting ----------
const fmtDur = (s) => {
  s = Math.max(0, Math.round(s));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60;
  return h ? `${h}:${String(m).padStart(2, "0")}:${String(x).padStart(2, "0")}` : `${m}:${String(x).padStart(2, "0")}`;
};
const fmtTime = (ts) => new Date(ts * 1000).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
const fmtTimeS = (ts) => new Date(ts * 1000).toLocaleTimeString([], { hour: "numeric", minute: "2-digit", second: "2-digit" });
const fmtDay = (ts) => new Date(ts * 1000).toLocaleDateString([], { weekday: "short", month: "short", day: "numeric", year: "numeric" });
const fmtSize = (b) => b > 1e9 ? (b / 1e9).toFixed(2) + " GB" : (b / 1e6).toFixed(0) + " MB";

// ---------- status ----------
async function refreshStatus() {
  const el = $("devStatus");
  try {
    const s = await api("/api/status");
    const n = $("notice");
    const msgs = [];
    if (!s.ffmpeg) msgs.push("ffmpeg isn't installed on the computer running Comma Clips, so exports and thumbnails won't work. Install it with <code>sudo apt install ffmpeg</code>.");
    if (s.device.configured === false) {
      el.textContent = "not set up"; el.className = "pill warn";
      if (!location.hash.startsWith("#/settings")) location.hash = "#/settings";
    } else if (!s.device.online) { el.textContent = "comma offline"; el.className = "pill bad"; msgs.push(`Can't reach the comma at ${s.host}: ${s.device.error || ""} — <a href="#/settings">change or find the address</a>`); }
    else if (s.device.onroad) { el.textContent = "driving"; el.className = "pill warn"; }
    else { el.textContent = "comma online"; el.className = "pill ok"; }
    n.innerHTML = msgs.join("<br>");
    n.classList.toggle("hidden", !msgs.length);
    return s;
  } catch (e) { el.textContent = "server offline"; el.className = "pill bad"; }
}

// ---------- drives list ----------
async function loadRoutes(force) {
  const list = $("routeList");
  try {
    state.routes = await api("/api/routes" + (force ? "?refresh=1" : ""));
  } catch (e) { list.innerHTML = `<div class="empty">Couldn't list drives: ${e.message}</div>`; return; }
  if (!state.routes.length) { list.innerHTML = `<div class="empty">No drives on the device.</div>`; return; }
  let html = "", lastDay = "";
  for (const r of state.routes) {
    const day = fmtDay(r.start_time);
    if (day !== lastDay) { html += `<div class="day-h">${day}</div>`; lastDay = day; }
    const mins = Math.round((r.last_seg - r.first_seg + 1) * SEG / 60);
    const cams = r.cameras.map((c) => ({ road: "Road", wide: "Wide", driver: "Driver" }[c])).join(" · ");
    html += `<button class="route-card" data-route="${r.name}">
      <span class="thumb" style="background-image:url('/api/routes/${r.name}/thumb.jpg')"></span>
      <span class="meta"><div class="when">${fmtTime(r.start_time)} – ${fmtTime(r.end_time)}</div>
      <div class="sub">${mins} min · ${cams || "no video"}${r.first_seg ? " · partly deleted" : ""}</div></span></button>`;
  }
  list.innerHTML = html;
}

$("routeList").addEventListener("click", (e) => {
  const b = e.target.closest(".route-card");
  if (b) location.hash = "#/route/" + b.dataset.route;
});

// ---------- route view ----------
function routeBounds() {
  const r = state.route;
  return { min: r.first_seg * SEG, max: (r.last_seg + 1) * SEG };
}
// player time <-> drive time (the preview playlist only contains segments that have a preview file)
function playerToRoute(pt) {
  const i = Math.min(Math.floor(pt / SEG), state.playable.length - 1);
  if (i < 0) return routeBounds().min;
  return state.playable[i] * SEG + (pt - i * SEG);
}
function routeToPlayer(rt) {
  const n = Math.floor(rt / SEG);
  let i = state.playable.indexOf(n);
  if (i < 0) { i = state.playable.findIndex((x) => x > n); if (i < 0) i = state.playable.length - 1; return i * SEG; }
  return i * SEG + (rt - n * SEG);
}
const wallOf = (rt) => state.route.start_time + rt;

async function openRoute(name) {
  if (!state.routes.length) await loadRoutes();
  const r = state.routes.find((x) => x.name === name);
  if (!r) { location.hash = ""; return; }
  state.route = r;
  state.playable = r.segments.filter((s) => s.files["qcamera.ts"]).map((s) => s.n);
  state.tel = new Map();
  showView("route");
  $("title").textContent = `${fmtDay(r.start_time)} · ${fmtTime(r.start_time)}`;

  const v = $("video");
  if (state.hls) { state.hls.destroy(); state.hls = null; }
  const src = `/api/routes/${name}/playlist.m3u8`;
  if (window.Hls && Hls.isSupported()) {
    state.hls = new Hls({ maxBufferLength: 30 });
    state.hls.loadSource(src);
    state.hls.attachMedia(v);
  } else if (v.canPlayType("application/vnd.apple.mpegurl")) {
    v.src = src;
  }

  // default selection: the last 30 s of the first minute, or restore
  const b = routeBounds();
  state.sel = { start: b.min, end: Math.min(b.min + 30, b.max) };
  // cameras
  const camNames = { road: "Road", wide: "Wide", driver: "Driver" };
  $("camCtl").innerHTML = ["road", "wide", "driver"].map((c) =>
    `<button data-v="${c}" ${r.cameras.includes(c) ? "" : "disabled"}>${camNames[c]}</button>`).join("");
  if (!r.cameras.includes(state.opts.camera)) state.opts.camera = r.cameras[0] || "road";
  drawMissing();
  syncOpts();
  updateSel();
}

function drawMissing() {
  const b = routeBounds(), span = b.max - b.min;
  const have = new Set(state.route.segments.filter((s) => s.files[{ road: "fcamera.hevc", wide: "ecamera.hevc", driver: "dcamera.hevc" }[state.opts.camera]]).map((s) => s.n));
  let html = "";
  for (let n = state.route.first_seg; n <= state.route.last_seg; n++) {
    if (!have.has(n)) html += `<div class="tl-gap" style="left:${((n * SEG - b.min) / span) * 100}%;width:${(SEG / span) * 100}%"></div>`;
  }
  $("tlMissing").innerHTML = html;
}

function updateSel() {
  const b = routeBounds(), span = b.max - b.min, s = state.sel;
  s.start = Math.max(b.min, Math.min(s.start, b.max - 1));
  s.end = Math.max(s.start + 1, Math.min(s.end, b.max));
  $("tlSel").style.left = ((s.start - b.min) / span) * 100 + "%";
  $("tlSel").style.width = ((s.end - s.start) / span) * 100 + "%";
  $("selStart").textContent = fmtDur(s.start - b.min);
  $("selEnd").textContent = fmtDur(s.end - b.min);
  $("selStartWall").textContent = "≈ " + fmtTimeS(wallOf(s.start));
  $("selEndWall").textContent = "≈ " + fmtTimeS(wallOf(s.end));
  $("selLen").textContent = fmtDur(s.end - s.start);
  $("tlStartLbl").textContent = fmtTime(wallOf(b.min));
  $("tlEndLbl").textContent = fmtTime(wallOf(b.max));
}

function nowRoute() { return playerToRoute($("video").currentTime || 0); }

function updateHead() {
  if (!state.route) return;
  const b = routeBounds(), rt = nowRoute();
  $("tlHead").style.left = ((rt - b.min) / (b.max - b.min)) * 100 + "%";
  $("tlNowLbl").textContent = fmtDur(rt - b.min) + " · " + fmtTimeS(wallOf(rt));
  if (state.opts.hud) drawHud(rt);
}
$("video").addEventListener("timeupdate", updateHead);
$("video").addEventListener("seeked", updateHead);

// timeline: tap to seek, drag near an edge to move that edge
(function timeline() {
  const tl = $("timeline");
  let drag = null;
  const toRoute = (x) => { const r = tl.getBoundingClientRect(), b = routeBounds(); return b.min + Math.max(0, Math.min(1, (x - r.left) / r.width)) * (b.max - b.min); };
  tl.addEventListener("pointerdown", (e) => {
    const rt = toRoute(e.clientX), b = routeBounds();
    const px = (t) => ((t - b.min) / (b.max - b.min)) * tl.getBoundingClientRect().width;
    const x = px(rt);
    drag = Math.abs(x - px(state.sel.start)) < 18 ? "start" : Math.abs(x - px(state.sel.end)) < 18 ? "end" : "seek";
    tl.setPointerCapture(e.pointerId);
    move(e);
  });
  const move = (e) => {
    if (!drag) return;
    const rt = toRoute(e.clientX);
    if (drag === "seek") { $("video").currentTime = routeToPlayer(rt); updateHead(); }
    else { state.sel[drag] = rt; if (state.sel.start >= state.sel.end) { drag === "start" ? state.sel.end = rt + 1 : state.sel.start = rt - 1; } updateSel(); }
  };
  tl.addEventListener("pointermove", move);
  tl.addEventListener("pointerup", () => (drag = null));
  tl.addEventListener("pointercancel", () => (drag = null));
})();

$("setStart").onclick = () => { state.sel.start = nowRoute(); if (state.sel.end <= state.sel.start) state.sel.end = state.sel.start + 30; updateSel(); };
$("setEnd").onclick = () => { state.sel.end = nowRoute(); if (state.sel.end <= state.sel.start) state.sel.start = Math.max(routeBounds().min, state.sel.end - 30); updateSel(); };
document.querySelectorAll("[data-nudge]").forEach((b) => b.onclick = () => {
  const [k, d] = b.dataset.nudge.split(":"); state.sel[k] += Number(d); updateSel();
  $("video").currentTime = routeToPlayer(state.sel[k]);
});
document.querySelectorAll("[data-preset]").forEach((b) => b.onclick = () => {
  const p = b.dataset.preset, now = nowRoute(), bd = routeBounds();
  if (p === "all") state.sel = { start: bd.min, end: bd.max };
  else { const [a, z] = p.split(":").map(Number); state.sel = { start: now + a, end: now + z }; }
  updateSel();
});

// ---------- export options ----------
function bindSeg(id, key) {
  $(id).addEventListener("click", (e) => {
    const b = e.target.closest("button"); if (!b || b.disabled) return;
    state.opts[key] = b.dataset.v; syncOpts();
  });
}
bindSeg("camCtl", "camera"); bindSeg("unitsCtl", "units"); bindSeg("modeCtl", "mode"); bindSeg("qualityCtl", "quality");
$("osdToggle").onchange = (e) => { state.opts.osd = e.target.checked; syncOpts(); };
$("audioToggle").onchange = (e) => { state.opts.audio = e.target.checked; syncOpts(); };
$("hudToggle").onchange = (e) => { state.opts.hud = e.target.checked; syncOpts(); if (!state.opts.hud) $("hud").classList.add("hidden"); else updateHead(); };

function syncOpts() {
  const o = state.opts;
  if (o.osd) o.mode = "exact";
  for (const [id, key] of [["camCtl", "camera"], ["unitsCtl", "units"], ["modeCtl", "mode"], ["qualityCtl", "quality"]]) {
    $(id).querySelectorAll("button").forEach((b) => b.classList.toggle("on", b.dataset.v === o[key]));
  }
  $("modeCtl").querySelector('[data-v="fast"]').disabled = o.osd;
  $("osdToggle").checked = o.osd;
  $("audioToggle").checked = o.audio !== false;
  $("hudToggle").checked = o.hud;
  $("unitsField").classList.toggle("hidden", !o.osd && !o.hud);
  $("qualityField").classList.toggle("hidden", o.mode === "fast");
  $("modeHint").textContent = o.osd ? "The overlay needs Exact cut (the video is re-encoded to H.264)."
    : o.mode === "fast" ? "Original HEVC video, no quality loss, very quick. Starts at the nearest keyframe and may not play in every app."
    : "Cut to the exact second and re-encoded to H.264 so it plays everywhere.";
  store.set("opts", o);
  if (state.route) drawMissing();
}

$("exportBtn").onclick = async () => {
  const btn = $("exportBtn"), msg = $("exportMsg");
  btn.disabled = true; msg.textContent = "";
  try {
    const o = state.opts;
    await api("/api/export", { method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ route: state.route.name, start: state.sel.start, end: state.sel.end, camera: o.camera,
        osd: o.osd, units: o.units, mode: o.mode, quality: o.quality, audio: o.audio !== false }) });
    msg.innerHTML = `Export started. <a href="#/exports">See progress</a>`;
    pollJobs();
  } catch (e) { msg.textContent = "Export failed: " + e.message; }
  btn.disabled = false;
};

// ---------- telemetry HUD on preview ----------
async function loadTel(seg) {
  if (state.tel.has(seg) || state.telLoading.has(seg)) return;
  state.telLoading.add(seg);
  try { state.tel.set(seg, await api(`/api/routes/${state.route.name}/telemetry?seg=${seg}`)); }
  catch (e) { state.tel.set(seg, { error: e.message }); }
  state.telLoading.delete(seg);
}
function drawHud(rt) {
  const hud = $("hud"), L_ = $("hudL"), R_ = $("hudR"), seg = Math.floor(rt / SEG), d = state.tel.get(seg);
  hud.classList.remove("hidden");
  loadTel(seg); if (rt % SEG > 45) loadTel(seg + 1);
  if (!d) { L_.textContent = "loading telemetry…"; R_.textContent = ""; return; }
  if (d.error) { L_.textContent = d.error; R_.textContent = ""; return; }
  const fr = (d.frames && d.frames.road) || [];
  const idx = Math.min(fr.length - 1, Math.floor((rt - seg * SEG) * FPS));
  const mono = fr.length ? fr[idx] : d.t[0] + (rt - seg * SEG) * 1e9;
  let lo = 0, hi = d.t.length - 1;
  while (lo < hi) { const mid = (lo + hi + 1) >> 1; if (d.t[mid] <= mono) lo = mid; else hi = mid - 1; }
  const mph = state.opts.units !== "kmh", k = mph ? 2.23694 : 3.6, u = mph ? "mph" : "km/h";
  const L = d.lead[lo];
  const sgn = (x, n) => { const v = Number(x.toFixed(n)) + 0; return (v > 0 ? "+" : "") + v.toFixed(n); };
  L_.innerHTML = `<div class="spd">${Math.round(d.v[lo] * k)} <span class="unit">${u}</span></div>${d.eng[lo] ? "● engaged" : ""}`;
  R_.innerHTML = `LEAD ${L[0] ? Math.round(L[1] * k) + " " + u + " · " + Math.round(L[2] * (mph ? 3.28084 : 1)) + (mph ? " ft" : " m") : "—"}<br>
    ACCEL ${sgn(d.a[lo], 1)} m/s²<br>
    STEER ${sgn(d.steer[lo], 0)}°`;
}

// ---------- exports ----------
let pollTimer = null;
async function pollJobs() {
  clearTimeout(pollTimer);
  let jobs = [];
  try { jobs = await api("/api/jobs"); } catch {}
  const active = jobs.filter((j) => j.state === "running" || j.state === "queued");
  $("jobBadge").textContent = active.length; $("jobBadge").classList.toggle("hidden", !active.length);
  if (state.tab === "exports") renderJobs(jobs);
  if (active.length) pollTimer = setTimeout(pollJobs, 1000);
  else if (state.tab === "exports") loadFiles();
  return jobs;
}
function renderJobs(jobs) {
  const camNames = { road: "Road", wide: "Wide", driver: "Driver" };
  $("jobList").innerHTML = jobs.filter((j) => j.state !== "done").map((j) => {
    const p = j.params, title = `${camNames[p.camera]} · ${fmtDur(p.end - p.start)}${p.osd ? " · overlay" : ""}${p.audio ? " · audio" : ""}`;
    const live = j.state === "running" || j.state === "queued";
    return `<div class="job ${j.state}"><div class="row"><b>${title}</b>
      ${live ? `<button class="small-btn" data-cancel="${j.id}">Cancel</button>` : ""}</div>
      ${live ? `<div class="bar"><div style="width:${Math.round(j.progress * 100)}%"></div></div>` : ""}
      <div class="phase">${j.state === "error" ? "Failed: " + j.message : j.state === "cancelled" ? "Cancelled" : `${j.phase} ${j.message ? "· " + j.message : ""} · ${Math.round(j.progress * 100)}%`}</div></div>`;
  }).join("");
}
$("jobList").addEventListener("click", async (e) => {
  const b = e.target.closest("[data-cancel]"); if (!b) return;
  await api(`/api/jobs/${b.dataset.cancel}/cancel`, { method: "POST" }); pollJobs();
});
async function loadFiles() {
  let files = [];
  try { files = await api("/api/exports"); } catch {}
  $("fileList").innerHTML = files.length ? files.map((f) => `<div class="file">
      <div class="name">${f.file}<small>${fmtSize(f.size)} · ${new Date(f.mtime * 1000).toLocaleString()}</small></div>
      <button class="small-btn" data-play="${encodeURIComponent(f.file)}">Play</button>
      <a class="small-btn" href="/exports/${encodeURIComponent(f.file)}?dl=1" download>Save</a>
      <button class="small-btn" data-del="${encodeURIComponent(f.file)}">✕</button></div>`).join("")
    : `<div class="empty">No exported clips yet.</div>`;
}
$("fileList").addEventListener("click", async (e) => {
  const p = e.target.closest("[data-play]"), d = e.target.closest("[data-del]");
  if (p) {
    $("dlgVideo").src = "/exports/" + p.dataset.play;
    $("dlgDownload").href = "/exports/" + p.dataset.play + "?dl=1";
    $("playerDlg").showModal();
  } else if (d && confirm("Delete " + decodeURIComponent(d.dataset.del) + " from the computer?")) {
    await api("/api/exports/delete", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ file: decodeURIComponent(d.dataset.del) }) });
    loadFiles();
  }
});
$("playerDlg").addEventListener("close", () => { $("dlgVideo").pause(); $("dlgVideo").removeAttribute("src"); });

// ---------- navigation ----------
function showView(v) {
  for (const s of ["drives", "route", "exports", "settings"]) $("view-" + s).classList.toggle("hidden", s !== v);
  $("backBtn").classList.toggle("hidden", v !== "route" && v !== "settings");
  $("tabs").classList.toggle("hidden", v === "route" || v === "settings");
  state.tab = v;
  document.querySelectorAll("#tabs button").forEach((b) => b.classList.toggle("active", b.dataset.tab === v));
  if (v !== "route") {
    $("title").textContent = v === "settings" ? "Connection" : "Comma Clips";
    if (state.hls) { state.hls.destroy(); state.hls = null; }
    $("video").removeAttribute("src"); state.route = null; $("hud").classList.add("hidden");
  }
}
function route() {
  const h = location.hash;
  if (h.startsWith("#/route/")) openRoute(h.slice(8));
  else if (h === "#/exports") { showView("exports"); pollJobs().then(loadFiles); }
  else if (h === "#/settings") { showView("settings"); loadSettings(); }
  else { showView("drives"); if (!state.routes.length) loadRoutes(); }
}
document.querySelectorAll("#tabs button").forEach((b) => b.onclick = () => (location.hash = b.dataset.tab === "drives" ? "" : "#/exports"));
$("backBtn").onclick = () => (location.hash = "");
$("refreshBtn").onclick = () => { refreshStatus(); if (state.tab === "drives") loadRoutes(true); else if (state.tab === "exports") { pollJobs(); loadFiles(); } };
$("settingsBtn").onclick = () => (location.hash = "#/settings");
window.addEventListener("hashchange", route);

if ("serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js").catch(() => {});
if (location.search.includes("k=")) history.replaceState(null, "", "/" + location.hash);
syncOpts();
refreshStatus();
route();
pollJobs();
setInterval(refreshStatus, 30000);

// ---------- connection settings ----------
const esc = (t) => String(t ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const hostValue = () => { const v = $("hostInput").value.trim(); return v.includes("@") ? v : "comma@" + v; };

async function loadSettings() {
  try {
    const s = await api("/api/settings");
    $("curHost").textContent = s.host || "not set up yet";
    $("welcome").classList.toggle("hidden", !!s.host);
    if (document.activeElement !== $("keyInput")) $("keyInput").value = s.ssh_key || "";
    $("curIp").textContent = s.ip && s.ip !== s.address ? "→ " + s.ip : "";
    if (!$("hostInput").value) $("hostInput").value = s.address;
  } catch (e) { $("curHost").textContent = e.message; }
  const st = await refreshStatus();
  const el = $("curState");
  if (st && st.device.online) { el.textContent = st.device.onroad ? "online · driving" : "online"; el.className = "pill " + (st.device.onroad ? "warn" : "ok"); }
  else { el.textContent = "not reachable"; el.className = "pill bad"; }
}

function showResult(r) {
  const box = $("testResult");
  box.classList.remove("hidden", "ok", "bad", "trust");
  if (r.ok) {
    box.classList.add("ok");
    box.innerHTML = `<b>Found your comma</b> — ${esc(r.hostname || r.address)} at ${esc(r.ip)}${r.dongle_id ? `<br><span class="hint">Dongle ID ${esc(r.dongle_id)}</span>` : ""}`;
  } else if (r.stage === "trust") {
    box.classList.add("trust");
    box.innerHTML = `<b>New device</b> — ${esc(r.address)} (${esc(r.ip)}) has an SSH key Comma Clips hasn't seen before.
      Only trust it if this is your comma. You can compare the key with <code>ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub</code> on the comma.
      <div class="fp">${esc(r.key_type)} ${esc(r.fingerprint)}</div>
      <button class="primary-ghost" id="trustBtn">Trust this device</button>`;
    $("trustBtn").onclick = async () => {
      $("trustBtn").disabled = true;
      try { showResult(await api("/api/settings/trust", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ host: r.host, fingerprint: r.fingerprint }) })); }
      catch (e) { showResult({ ok: false, error: e.message }); }
    };
  } else {
    box.classList.add("bad");
    box.innerHTML = `<b>Can't connect</b> — ${esc(r.error)}`;
  }
}

async function postHost(path, btn, label) {
  const old = btn.textContent; btn.disabled = true; btn.textContent = label;
  try { return await api(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ host: hostValue() }) }); }
  finally { btn.disabled = false; btn.textContent = old; }
}

$("testBtn").onclick = async () => {
  try { showResult(await postHost("/api/settings/test", $("testBtn"), "Testing…")); }
  catch (e) { showResult({ ok: false, error: e.message }); }
};
$("saveBtn").onclick = async () => {
  try {
    const r = await postHost("/api/settings/save", $("saveBtn"), "Connecting…");
    showResult(r);
    $("testResult").insertAdjacentHTML("beforeend", "<br>Saved. Comma Clips now uses this address.");
    state.routes = []; loadSettings();
  } catch (e) {
    // show why (test result tells the user what to fix, including the trust step)
    try { showResult(await api("/api/settings/test", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ host: hostValue() }) })); }
    catch { showResult({ ok: false, error: e.message }); }
  }
};
$("hostInput").addEventListener("keydown", (e) => { if (e.key === "Enter") $("testBtn").click(); });

$("scanBtn").onclick = async () => {
  const btn = $("scanBtn"), out = $("scanResults");
  btn.disabled = true; btn.textContent = "Searching… (about 5 s)"; out.innerHTML = "";
  try {
    const list = await api("/api/settings/scan", { method: "POST" });
    const row = (d) => `<div class="found"><div class="who"><b>${esc(d.name ? d.name.replace(/\.localdomain$|\.lan$|\.home$/, "") : d.ip)}</b>
        <small>${esc(d.ip)}</small>${d.current ? '<span class="tag good">connected</span>' : ""}${d.comma ? '<span class="tag good">comma</span>' : ""}${d.trusted ? '<span class="tag">trusted key</span>' : ""}</div>
        <button class="small-btn" data-use="${esc(d.suggest)}">Use</button></div>`;
    const likely = list.filter((d) => d.comma || d.trusted || d.current), others = list.filter((d) => !(d.comma || d.trusted || d.current));
    out.innerHTML = (likely.length ? likely.map(row).join("") : `<div class="hint">No comma found. Make sure it's switched on and on the same Wi-Fi.</div>`) +
      (others.length ? `<details class="others"><summary>${others.length} other device${others.length > 1 ? "s" : ""} with SSH</summary>${others.map(row).join("")}</details>` : "");
  } catch (e) { out.innerHTML = `<div class="hint">Search failed: ${esc(e.message)}</div>`; }
  btn.disabled = false; btn.textContent = "Search network";
};
$("scanResults").addEventListener("click", (e) => {
  const b = e.target.closest("[data-use]"); if (!b) return;
  $("hostInput").value = b.dataset.use;
  $("testBtn").click();
  $("hostInput").scrollIntoView({ block: "center", behavior: "smooth" });
});

$("keyBtn").onclick = async () => {
  const box = $("keyResult"), btn = $("keyBtn");
  btn.disabled = true;
  box.classList.remove("hidden", "ok", "bad", "trust");
  try {
    const r = await api("/api/settings/key", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ path: $("keyInput").value }) });
    box.classList.add(r.warning ? "trust" : "ok");
    box.innerHTML = r.path ? `<b>Using ${esc(r.path)}</b>${r.fingerprint ? `<div class="fp">${esc(r.fingerprint)}</div>` : ""}${r.warning ? `<br>${esc(r.warning)}` : ""}`
      : `<b>${esc(r.note)}</b>`;
    loadSettings();
  } catch (e) { box.classList.add("bad"); box.innerHTML = `<b>Can't use that key</b> — ${esc(e.message)}`; }
  btn.disabled = false;
};
