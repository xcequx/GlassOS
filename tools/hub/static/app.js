/* GlassOS Hub — przeglądarkowy pulpit okularów.
 *
 * Zasada: /api/status jest małe i odpytywane co sekundę; pełny /api/state
 * ściągamy tylko wtedy, gdy zmieni się jego rewizja. Każdy błąd sieci widać
 * na górze strony — cicha, martwa strona była głównym powodem wrażenia,
 * że "nic nie działa".
 */
const S = {
  desktops: [], computers: [], apps: [], chat: [], files: [], logs: [], diag: {}, settings: {},
  current: null, host: null, rev: -1, logCount: -1, dirty: false, status: null, fails: 0, pane: "logs",
  editingHost: null,
};

const KIND_LABEL = { rdp: "RDP", moonlight: "Moonlight", vnc: "VNC", ssh: "SSH" };
const KIND_PORT = { rdp: 3389, moonlight: 47989, vnc: 5900, ssh: 22 };

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s == null ? "" : s).replace(/[&<>"]/g, (c) =>
  ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

$("hubUrl").textContent = location.origin;

async function api(path, opts) {
  const res = await fetch(path, Object.assign(
    { headers: { "Content-Type": "application/json" }, cache: "no-store" }, opts));
  if (!res.ok) throw new Error(("HTTP " + res.status + " " + await res.text()).slice(0, 200));
  return res.json();
}

function banner(msg) {
  const el = $("banner");
  if (!msg) { el.className = ""; el.textContent = ""; return; }
  el.className = "show";
  el.textContent = msg;
}

/* ---------- status / diagnostyka ---------- */

function renderPills(st) {
  const by = {};
  (st.checks || []).forEach((c) => { by[c.key] = c; });
  const want = [
    ["phone", "telefon"], ["usb", "USB"], ["display", "ekran"],
    ["workspace", "pulpit"], ["tracking", "głowa"], ["adb", "ADB"],
  ];
  $("pills").innerHTML = want.map(([key, label]) => {
    const c = by[key];
    const cls = !c ? "off" : c.ok ? "ok" : "";
    return '<span class="pill ' + cls + '" title="' + esc(c ? c.detail : "") + '">' +
      '<span class="dot"></span><b>' + label + "</b></span>";
  }).join("");
}

function renderChecks(st) {
  const items = st.checks || [];
  $("checks").innerHTML = items.map((c) =>
    '<div class="check ' + (c.ok ? "ok" : "bad") + '">' +
      '<div class="mark">' + (c.ok ? "✓" : "✕") + "</div>" +
      '<div class="body">' +
        '<div class="label">' + esc(c.label) + "</div>" +
        '<div class="detail">' + esc(c.detail || "") + "</div>" +
        '<div class="fix">' + esc(c.hint || "") + "</div>" +
      "</div></div>"
  ).join("") || '<div class="empty">—</div>';
}

function renderPreview(st) {
  const p = st.preview || {};
  const badge = $("liveBadge");
  if (p.present && p.age != null && p.age < 15) {
    badge.textContent = "na żywo · " + p.age.toFixed(1) + " s";
    $("live").src = "/preview.jpg?t=" + Date.now();
    $("live").style.display = "block";
  } else {
    badge.textContent = p.present ? "ostatnia klatka " + Math.round(p.age) + " s temu" : "brak obrazu";
    if (!p.present) $("live").removeAttribute("src");
  }
  $("previewAge").textContent = p.present ? "" : "— pulpit nie nadaje";
}

/* ---------- pulpity ---------- */

function screenCount(layout) {
  if (layout === "THREE_SBS") return 3;
  if (layout === "TWO_SBS") return 2;
  return 1;
}
function currentDesk() { return S.desktops.find((d) => d.id === S.current); }

function renderDesktops() {
  $("deskCount").textContent = S.desktops.length ? "(" + S.desktops.length + ")" : "";
  $("desktops").innerHTML = S.desktops.map((d) =>
    '<div class="desk ' + (d.id === S.current ? "active" : "") + '" data-id="' + esc(d.id) + '">' +
      '<div><div class="name">' + esc(d.name) + "</div>" +
      '<div class="hint">' + esc(d.layout) + " · " +
        (d.screens || []).reduce((n, s) => n + s.length, 0) + " elem.</div></div></div>"
  ).join("") || '<div class="empty">Brak pulpitów.</div>';
  $("desktops").querySelectorAll(".desk").forEach((el) => {
    el.onclick = () => { S.current = el.dataset.id; S.dirty = false; renderAll(); };
  });
}

/* ---------- komputery ---------- */

function hostById(id) { return (S.computers || []).find((c) => c.id === id); }

function renderHosts() {
  const list = S.computers || [];
  $("hostCount").textContent = list.length ? "(" + list.length + ")" : "";
  const d = currentDesk();
  const n = d ? screenCount(d.layout) : 0;
  $("hosts").innerHTML = list.map((c) => {
    const dot = '<span class="dot ' + (c.online ? "on" : "") + '" title="' +
      (c.online ? "odpowiada" + (c.ms != null ? " · " + c.ms + " ms" : "") : "nie odpowiada na port " + (c.port || "")) + '"></span>';
    const screens = [];
    for (let i = 0; i < n; i++) {
      screens.push('<button class="mini" data-open="' + esc(c.id) + '" data-screen="' + i + '" title="Otwórz teraz na ekranie ' + (i + 1) + '">→ ' + (i + 1) + "</button>");
    }
    return '<div class="host ' + (c.id === S.host ? "active" : "") + '" data-id="' + esc(c.id) + '">' +
      '<div style="flex:1;min-width:0">' +
        '<div class="name">' + dot + esc(c.name) + "</div>" +
        '<div class="hint">' + esc(KIND_LABEL[c.kind] || c.kind || "ssh") + " · " + esc(c.user ? c.user + "@" : "") +
          esc(c.host) + ":" + esc(c.port || "") + "</div>" +
        '<div class="row hostrow">' + screens.join("") +
          (c.mac ? '<button class="mini" data-wake="' + esc(c.id) + '" title="Wake-on-LAN">Obudź</button>' : "") +
          '<button class="mini" data-edit="' + esc(c.id) + '">Edytuj</button>' +
          '<button class="mini" data-del="' + esc(c.id) + '">Usuń</button>' +
        "</div>" +
      "</div></div>";
  }).join("") || '<div class="empty">Brak komputerów. Dodaj pierwszy poniżej.</div>';
  $("hosts").querySelectorAll(".host").forEach((el) => {
    el.onclick = () => { S.host = el.dataset.id; renderHosts(); };
  });
  $("hosts").querySelectorAll("[data-open]").forEach((b) => {
    b.onclick = (e) => {
      e.stopPropagation();
      api("/api/computers/" + b.dataset.open + "/open", { method: "POST", body: JSON.stringify({ screenIdx: Number(b.dataset.screen) }) })
        .then(() => banner(""))
        .catch((err) => banner("Nie poszło: " + err.message));
      const c = hostById(b.dataset.open);
      $("applyHint").textContent = "Wysłane: " + (c ? c.name : "komputer") + " → ekran " + (Number(b.dataset.screen) + 1) + " (≤2 s).";
    };
  });
  $("hosts").querySelectorAll("[data-wake]").forEach((b) => {
    b.onclick = async (e) => {
      e.stopPropagation();
      try {
        const r = await api("/api/computers/" + b.dataset.wake + "/wake", { method: "POST", body: "{}" });
        banner(r.ok ? "" : "WOL: " + r.error);
        if (r.ok) $("hostHint").textContent = "Pakiet WOL wysłany (" + (r.sent || []).join(", ") + "). Komputer wstaje ~30–60 s.";
      } catch (err) { banner("WOL: " + err.message); }
    };
  });
  $("hosts").querySelectorAll("[data-edit]").forEach((b) => {
    b.onclick = (e) => { e.stopPropagation(); startEditHost(b.dataset.edit); };
  });
  let delHost = null;
  $("hosts").querySelectorAll("[data-del]").forEach((b) => {
    b.onclick = async (e) => {
      e.stopPropagation();
      if (delHost !== b.dataset.del) {
        delHost = b.dataset.del;
        b.textContent = "Na pewno?";
        setTimeout(() => { if (delHost === b.dataset.del) { delHost = null; b.textContent = "Usuń"; } }, 4000);
        return;
      }
      await api("/api/computers/" + b.dataset.del + "/delete", { method: "POST", body: "{}" });
      if (S.host === b.dataset.del) S.host = null;
      await loadState();
    };
  });
}

function syncKindFields() {
  const kind = $("cKind").value;
  $("moonFields").style.display = kind === "moonlight" ? "" : "none";
  $("cPort").placeholder = String(KIND_PORT[kind] || "");
  $("cUser").placeholder = kind === "rdp" ? "użytkownik Windows" : kind === "ssh" ? "użytkownik ssh" : "użytkownik (opcjonalnie)";
}

function startEditHost(id) {
  const c = hostById(id);
  if (!c) return;
  S.editingHost = id;
  $("cKind").value = c.kind || "ssh";
  $("cName").value = c.name || "";
  $("cHost").value = c.host || "";
  $("cUser").value = c.user || "";
  $("cPort").value = c.port || "";
  $("cMac").value = c.mac || "";
  $("cUuid").value = c.uuid || "";
  $("cApp").value = c.app || "";
  $("addHost").textContent = "Zapisz zmiany";
  $("cancelEdit").style.display = "";
  syncKindFields();
}

function resetHostForm() {
  S.editingHost = null;
  ["cName", "cHost", "cUser", "cPort", "cMac", "cUuid", "cApp"].forEach((i) => { $(i).value = ""; });
  $("addHost").textContent = "Dodaj komputer";
  $("cancelEdit").style.display = "none";
  syncKindFields();
}
$("cKind").onchange = syncKindFields;
$("cancelEdit").onclick = resetHostForm;
syncKindFields();

function renderScreens() {
  const d = currentDesk();
  if (!d) { $("screens").innerHTML = ""; return; }
  $("layout").value = d.layout;
  const n = screenCount(d.layout);
  d.screens = d.screens || [];
  while (d.screens.length < n) d.screens.push([]);
  d.screens = d.screens.slice(0, n);
  $("screens").innerHTML = d.screens.map((apps, i) =>
    '<div class="screen" data-slot="' + i + '">' +
      "<h3>Ekran " + (i + 1) + "</h3>" +
      '<div class="row">' +
        '<button class="mini" data-add="mail" data-slot="' + i + '">+ Poczta</button>' +
        '<button class="mini" data-add="browser" data-slot="' + i + '">+ Strona</button>' +
        '<button class="mini" data-add="ssh" data-slot="' + i + '">+ SSH</button>' +
        '<button class="mini accent" data-add="remote" data-slot="' + i + '">+ Komputer</button>' +
      "</div><div>" +
      (apps.map((a, ai) => {
        let body;
        if (a.type === "browser") {
          body = '<input class="chipurl" data-slot="' + i + '" data-i="' + ai + '" value="' +
            esc(a.url || "") + '" placeholder="https://…" />';
        } else if (a.type === "remote") {
          const c = hostById(a.hostId);
          body = c ? esc(c.name) + ' <span class="kind">' + esc(KIND_LABEL[c.kind] || c.kind) + "</span>"
                   : '<span class="warn">' + esc(a.label || "komputer") + " — usunięty z listy</span>";
        } else {
          body = esc(a.label || a.packageName || "?");
        }
        return '<span class="chip' + (a.type === "remote" ? " remote" : "") + '"><span class="kind">' +
          esc(a.type === "remote" ? "monitor" : (a.type || "app")) + "</span>" + body +
          '<button data-slot="' + i + '" data-i="' + ai + '">✕</button></span>';
      }).join("") || '<div class="empty">Przeciągnij apkę albo dodaj komputer / stronę / pocztę / SSH</div>') +
      "</div></div>"
  ).join("");

  $("screens").querySelectorAll(".screen").forEach((el) => {
    el.ondragover = (e) => { e.preventDefault(); el.classList.add("drag"); };
    el.ondragleave = () => el.classList.remove("drag");
    el.ondrop = (e) => {
      e.preventDefault(); el.classList.remove("drag");
      let app;
      try { app = JSON.parse(e.dataTransfer.getData("application/json")); } catch (err) { return; }
      const list = d.screens[Number(el.dataset.slot)];
      if (!list.some((x) => x.packageName && x.packageName === app.packageName)) {
        list.push(Object.assign({}, app, { type: "app" }));
      }
      S.dirty = true; renderScreens();
    };
  });
  $("screens").querySelectorAll(".chip button").forEach((b) => {
    b.onclick = () => {
      d.screens[Number(b.dataset.slot)].splice(Number(b.dataset.i), 1);
      S.dirty = true; renderScreens();
    };
  });
  $("screens").querySelectorAll(".chipurl").forEach((inp) => {
    const item = d.screens[Number(inp.dataset.slot)][Number(inp.dataset.i)];
    inp.onclick = (e) => e.stopPropagation();
    inp.oninput = () => {
      item.url = inp.value.trim();
      item.label = hostOf(item.url);
      S.dirty = true;
    };
  });
  $("screens").querySelectorAll("[data-add]").forEach((b) => {
    b.onclick = () => {
      const slot = Number(b.dataset.slot);
      const kind = b.dataset.add;
      if (kind === "mail") {
        d.screens[slot].push({ type: "mail", label: "Poczta", packageName: "", activityName: "", url: "" });
      } else if (kind === "browser") {
        // Adres wpisuje się w samym kaflu — żadnych okienek prompt(),
        // które w przeglądarce w okularach nie mają gdzie się pokazać.
        d.screens[slot].push({
          type: "browser", label: "Web", packageName: "", activityName: "",
          url: "https://www.google.com",
        });
      } else if (kind === "ssh") {
        const id = S.host || (S.computers[0] && S.computers[0].id);
        if (!id) { banner("Najpierw dodaj komputer SSH w lewej kolumnie."); return; }
        const c = S.computers.find((x) => x.id === id);
        d.screens[slot].push({ type: "ssh", label: c ? c.name : "SSH", packageName: "", activityName: "", hostId: id });
      } else if (kind === "remote") {
        const id = S.host || (S.computers[0] && S.computers[0].id);
        if (!id) { banner("Najpierw dodaj komputer w lewej kolumnie i zaznacz go."); return; }
        const c = hostById(id);
        if (d.screens[slot].some((x) => x.type === "remote")) {
          banner("Na jednym ekranie mieści się jeden komputer (pełny monitor). Wybierz inny ekran.");
          return;
        }
        d.screens[slot].push({ type: "remote", label: c ? c.name : "Komputer", packageName: "", activityName: "", hostId: id });
      }
      S.dirty = true; renderScreens();
    };
  });
}

function hostOf(url) {
  try { return new URL(url).hostname.replace(/^www\./, ""); } catch (e) { return "Web"; }
}

/* ---------- apki / czat / logi ---------- */

function renderApps() {
  const q = $("filter").value.toLowerCase();
  const all = S.apps || [];
  $("appCount").textContent = all.length ? "(" + all.length + ")" : "";
  const shown = all.filter((a) => (a.label || "").toLowerCase().includes(q)).slice(0, 300);
  $("apps").innerHTML = shown.length ? shown.map((a, i) =>
    '<div class="app" draggable="true" data-i="' + i + '">' +
      '<div class="glyph">' + esc((a.label || "?").slice(0, 1).toUpperCase()) + "</div>" +
      "<div>" + esc(a.label) + '<div class="hint">' + esc(a.packageName) + "</div></div></div>"
  ).join("")
    : '<div class="empty">' + (all.length ? "Nic nie pasuje." : "Brak listy — otwórz GlassOS na telefonie.") + "</div>";
  $("apps").querySelectorAll(".app").forEach((el) => {
    const app = shown[Number(el.dataset.i)];
    el.ondragstart = (e) => e.dataTransfer.setData("application/json", JSON.stringify(app));
  });
}

function renderChat() {
  $("chat").innerHTML = (S.chat || []).slice(-16).map((m) =>
    '<div class="msg ' + (m.role === "user" ? "user" : "") + '">' +
    (m.role === "user" ? "Ty" : "AI") + ": " + esc((m.text || "").slice(0, 1200)) + "</div>"
  ).join("") || '<div class="empty">Napisz, co ma być na ekranach.</div>';
  $("chat").scrollTop = $("chat").scrollHeight;
}

function renderPanes() {
  ["logs", "diag", "files"].forEach((p) => { $(p).style.display = p === S.pane ? "" : "none"; });
  $("logs").innerHTML = (S.logs || []).slice(-150).reverse().map((l) =>
    '<div class="' + esc(l.level) + '">' + new Date(l.ts * 1000).toLocaleTimeString() + " " +
    esc(l.tag) + " " + esc(l.text) + "</div>"
  ).join("") || '<div class="empty">Telefon nic jeszcze nie przysłał. Zainstaluj nowy APK, żeby widzieć log zdalnie.</div>';
  const d = S.diag || {};
  const rows = Object.keys(d).sort().map((k) =>
    "<div><b>" + esc(k) + "</b>: " + esc(typeof d[k] === "object" ? JSON.stringify(d[k]) : d[k]) + "</div>");
  $("diag").innerHTML = rows.join("") || '<div class="empty">Brak danych — stary APK albo telefon offline.</div>';
  $("files").innerHTML = (S.files || []).map((f) => "<div>" + esc(f.folder) + "/" + esc(f.name) + "</div>").join("")
    || '<div class="empty">Brak plików.</div>';
}

function renderAll() { renderDesktops(); renderHosts(); renderScreens(); renderAutostart(); renderApps(); renderChat(); renderPanes(); }

/* ---------- pętle ---------- */

async function pollStatus() {
  try {
    const st = await api("/api/status");
    S.fails = 0;
    S.status = st;
    banner("");
    renderPills(st); renderChecks(st); renderPreview(st);
    // Green dots per computer ride on the cheap status poll, not on a state reload.
    let dotsChanged = false;
    (st.computers || []).forEach((p) => {
      const c = hostById(p.id);
      if (c && (c.online !== p.online || c.ms !== p.ms)) { c.online = p.online; c.ms = p.ms; dotsChanged = true; }
    });
    if (dotsChanged && !S.editingHost) renderHosts();
    $("aiHint").textContent = st.ai.model
      ? (st.ai.deepseek ? "DeepSeek " : "xAI ") + st.ai.model
      : "Brak klucza AI — wstaw DEEPSEEK_API_KEY do tools/hub/.env";
    $("apkLink").textContent = "APK " + (st.apk.versionName || "?");
    if (st.rev !== S.rev) await loadState(st.rev);
    // Log lines don't change the state revision — follow their own counter.
    if (st.logs !== S.logCount) await loadLogs(st.logs);
  } catch (e) {
    S.fails += 1;
    if (S.fails > 1) banner("Brak kontaktu z hubem (" + e.message + "). Serwer stoi? Tailscale działa?");
  }
}

async function loadState(rev) {
  const s = await api("/api/state");
  S.rev = rev != null ? rev : s.rev;
  S.desktops = s.desktops || [];
  S.computers = s.computers || [];
  S.settings = s.settings || {};
  S.apps = (s.phone && s.phone.apps) || [];
  S.diag = (s.phone && s.phone.diag) || {};
  S.chat = s.chat || [];
  S.files = s.files || [];
  if (!S.current && S.desktops[0]) S.current = S.desktops[0].id;
  if (S.dirty) { renderDesktops(); renderHosts(); renderApps(); renderChat(); renderPanes(); }
  else renderAll();
  await loadLogs();
}

async function loadLogs(count) {
  try {
    const l = await api("/api/logs");
    S.logs = l.logs || [];
    S.logCount = count != null ? count : S.logs.length;
    renderPanes();
  } catch (e) { /* log telefonu jest opcjonalny */ }
}

/* ---------- akcje ---------- */

function cmd(type, extra) {
  return api("/api/command", { method: "POST", body: JSON.stringify(Object.assign({ type: type }, extra || {})) })
    .catch((e) => banner("Komenda nie poszła: " + e.message));
}

$("layout").onchange = () => {
  const d = currentDesk();
  if (!d) return;
  d.layout = $("layout").value;
  S.dirty = true;
  renderScreens();
};
$("filter").oninput = renderApps;

$("addDesk").onclick = async () => {
  const name = $("newName").value.trim() || "Nowy pulpit";
  const created = await api("/api/desktops", {
    method: "POST",
    body: JSON.stringify({ name: name, layout: "TWO_SBS", screens: [[], []] }),
  });
  S.current = created.id; S.dirty = false; $("newName").value = "";
  await loadState();
};
let delArmed = null;
$("delDesk").onclick = async () => {
  const d = currentDesk();
  if (!d) return;
  if (delArmed !== d.id) {
    // Dwa kliknięcia zamiast confirm() — modal potrafi zawiesić stronę w okularach.
    delArmed = d.id;
    $("delDesk").textContent = "Na pewno? Kliknij jeszcze raz";
    setTimeout(() => {
      if (delArmed === d.id) { delArmed = null; $("delDesk").textContent = "Usuń"; }
    }, 4000);
    return;
  }
  delArmed = null;
  $("delDesk").textContent = "Usuń";
  await api("/api/desktops/" + d.id + "/delete", { method: "POST", body: "{}" });
  S.current = null; S.dirty = false;
  await loadState();
};
$("addHost").onclick = async () => {
  if (!$("cHost").value.trim()) { banner("Podaj host — nazwę w Tailscale (np. pc-praca) albo IP."); return; }
  const body = {
    name: $("cName").value, host: $("cHost").value.trim(), user: $("cUser").value,
    port: Number($("cPort").value || 0), kind: $("cKind").value, mac: $("cMac").value,
    uuid: $("cUuid").value, app: $("cApp").value,
  };
  try {
    let saved;
    if (S.editingHost) {
      saved = await api("/api/computers/" + S.editingHost, { method: "PUT", body: JSON.stringify(body) });
    } else {
      saved = await api("/api/computers", { method: "POST", body: JSON.stringify(body) });
    }
    S.host = saved.id;
    resetHostForm();
    banner("");
    await loadState();
  } catch (e) { banner("Komputer: " + e.message); }
};

$("autostart").onchange = async () => {
  const d = currentDesk();
  if (!d) return;
  const id = $("autostart").checked ? d.id : "";
  try {
    S.settings = await api("/api/settings", { method: "POST", body: JSON.stringify({ autostart_desktop: id }) });
    $("applyHint").textContent = id
      ? "Telefon włączy „" + d.name + "” sam, gdy wykryje okulary (zapisuje też kopię na wypadek braku huba)."
      : "Autostart wyłączony.";
  } catch (e) { banner("Ustawienia: " + e.message); }
};

function renderAutostart() {
  const d = currentDesk();
  $("autostart").checked = !!(d && S.settings && S.settings.autostart_desktop === d.id);
}

async function saveDesk() {
  const d = currentDesk();
  if (!d) return null;
  await api("/api/desktops/" + d.id, { method: "PUT", body: JSON.stringify(d) });
  S.dirty = false;
  return d;
}

$("save").onclick = async () => { await saveDesk(); await loadState(); banner(""); };
$("apply").onclick = async () => {
  const d = await saveDesk();
  if (!d) return;
  await api("/api/apply", { method: "POST", body: JSON.stringify({ id: d.id }) });
  const st = S.status;
  $("applyHint").textContent = st && st.phone.online
    ? "Wysłane do telefonu: " + d.name + ". Powinno wejść w ≤2 s."
    : "Zapisane, ale telefon jest offline — wejdzie, gdy GlassOS wróci na łącze.";
  await loadState();
};
/* ---------- mysz z przegladarki ---------- */

/*
 * Przeciaganie po podgladzie = ruch kursora w okularach, klik = klik.
 * Delty ida w tysiecznych szerokosci/wysokosci, wiec sa niezalezne od tego,
 * jak duzy jest podglad na ekranie.
 */
(function mouseOverPreview() {
  const img = $("live");
  if (!img) return;
  let dragging = false;
  let last = null;
  let moved = 0;

  img.style.cursor = "crosshair";
  img.title = "Przeciagnij = kursor w okularach, klik = klik, prawy klik = menu";

  img.addEventListener("pointerdown", (e) => {
    dragging = true;
    moved = 0;
    last = { x: e.clientX, y: e.clientY };
    img.setPointerCapture(e.pointerId);
    e.preventDefault();
  });

  img.addEventListener("pointermove", (e) => {
    if (!dragging || !last) return;
    const r = img.getBoundingClientRect();
    const dx = Math.round(((e.clientX - last.x) / r.width) * 1000);
    const dy = Math.round(((e.clientY - last.y) / r.height) * 1000);
    if (dx === 0 && dy === 0) return;
    last = { x: e.clientX, y: e.clientY };
    moved += Math.abs(dx) + Math.abs(dy);
    cmd("cursor", { dx: dx, dy: dy });
  });

  const stop = (e) => {
    if (!dragging) return;
    dragging = false;
    last = null;
    try { img.releasePointerCapture(e.pointerId); } catch (err) { /* juz zwolniony */ }
    if (moved < 6) cmd("click");
  };
  img.addEventListener("pointerup", stop);
  img.addEventListener("pointercancel", stop);
  img.addEventListener("contextmenu", (e) => { e.preventDefault(); cmd("right_click"); });
  img.addEventListener("wheel", (e) => {
    e.preventDefault();
    cmd(e.deltaY > 0 ? "zoom_out" : "zoom_in");
  }, { passive: false });
})();

$("recenter").onclick = () => cmd("recenter");
$("nextScreen").onclick = () => cmd("next_screen");
$("zoomIn").onclick = () => cmd("zoom_in");
$("zoomOut").onclick = () => cmd("zoom_out");
$("resetLook").onclick = () => cmd("reset_look");
document.querySelectorAll("[data-cmd]").forEach((b) => { b.onclick = () => cmd(b.dataset.cmd); });
document.querySelectorAll(".tab").forEach((t) => {
  t.onclick = () => {
    S.pane = t.dataset.pane;
    document.querySelectorAll(".tab").forEach((x) => x.classList.toggle("active", x === t));
    renderPanes();
  };
});

$("ask").onclick = async () => {
  const text = $("prompt").value.trim();
  if (!text) return;
  $("prompt").value = "";
  $("ask").disabled = true;
  const hint = $("aiHint").textContent;
  $("aiHint").textContent = "myślę…";
  try {
    const r = await api("/api/ai/chat", { method: "POST", body: JSON.stringify({ text: text }) });
    $("aiHint").textContent = r.applied ? "AI zmieniło pulpit — wysłane na okulary." : hint;
    await loadState();
  } catch (e) {
    $("aiHint").textContent = "AI: " + String(e.message || e).slice(0, 140);
  } finally {
    $("ask").disabled = false;
  }
};
$("prompt").onkeydown = (e) => {
  if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) $("ask").click();
};
$("clearChat").onclick = async () => {
  await api("/api/chat/clear", { method: "POST", body: "{}" });
  await loadState();
};

pollStatus();
setInterval(pollStatus, 1200);
