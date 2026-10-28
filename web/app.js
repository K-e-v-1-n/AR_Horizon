(() => {
  "use strict";

  const DATA = window.AR_HORIZON_DATA;
  const DEG = Math.PI / 180;
  const KIND_LABEL = {
    peak: "Peak", city: "City", town: "Town", village: "Village",
    hamlet: "Hamlet", water: "Water", landmark: "Landmark",
  };
  const KIND_COLOR = {
    peak: "#ffffff", city: "#f3b85c", town: "#f3b85c", village: "#e7b478",
    hamlet: "#e7b478", water: "#7ec6e7", landmark: "#b7c4cc",
  };
  const FONT = '"Segoe UI", system-ui, -apple-system, sans-serif';
  const MONO = '"Cascadia Mono", Consolas, ui-monospace, monospace';

  const $ = (id) => document.getElementById(id);
  const scene = $("scene");
  const ctx = scene.getContext("2d");
  const tape = $("tape");
  const tctx = tape.getContext("2d");

  const state = {
    vpIndex: 0,
    heading: 0,
    pitch: 0,
    fov: 72,
    showHidden: true,
    selected: null,
    candidates: [],
    labels: [],
    boxes: [],
    width: 0,
    height: 0,
    dpr: 1,
    drag: null,
    moved: false,
    raf: 0,
  };

  let viewpoints = [];
  let current = null;

  function wrap360(v) { return ((v % 360) + 360) % 360; }
  function signed(v) { let d = ((v + 180) % 360 + 360) % 360 - 180; return d; }
  function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }
  function compass(deg) {
    const names = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
      "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"];
    return names[Math.round(wrap360(deg) / 22.5) % 16];
  }
  function distanceM(lat1, lon1, lat2, lon2) {
    const R = 6371008.8;
    const p1 = lat1 * DEG, p2 = lat2 * DEG;
    const dp = p2 - p1, dl = (lon2 - lon1) * DEG;
    const a = Math.sin(dp / 2) ** 2 + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) ** 2;
    return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
  }
  function bearingRad(lat1, lon1, lat2, lon2) {
    const p1 = lat1 * DEG, p2 = lat2 * DEG, dl = (lon2 - lon1) * DEG;
    const y = Math.sin(dl) * Math.cos(p2);
    const x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
    return Math.atan2(y, x);
  }
  function dropM(d) { return d * d / (2 * 7323825); }
  function fmtDistance(m) {
    if (m < 1000) return `${Math.round(m)} m`;
    if (m < 10000) return `${(m / 1000).toFixed(1)} km`;
    return `${Math.round(m / 1000)} km`;
  }
  function fmtElev(v) {
    if (!Number.isFinite(v) || v < -1000) return "—";
    return `${Math.round(v).toLocaleString("en-US")} m`;
  }

  function profileAt(profile, azRad) {
    if (!profile || !profile.length) return NaN;
    let a = azRad % (Math.PI * 2);
    if (a < 0) a += Math.PI * 2;
    const idx = Math.min(profile.length - 1, Math.floor(a / (Math.PI * 2) * profile.length));
    return profile[idx];
  }

  function prepareViewpoint() {
    current = viewpoints[state.vpIndex];
    const profile = current.silhouette.full;
    const vp = current;
    const candidates = [];
    for (const p of DATA.places) {
      const d = distanceM(vp.lat, vp.lon, p.lat, p.lon);
      if (d < 12 || d > 90000) continue;
      const km = d / 1000;
      if (km > 5 && p.kind === "hamlet") continue;
      if (km > 20 && (p.kind === "village" || p.kind === "hamlet")) continue;
      if (km > 50) {
        if (p.kind === "city" && p.pop < 10000) continue;
        if (p.kind === "town") continue;
        if (p.kind === "peak" && p.rank < 0.62) continue;
        if (p.kind === "water" && p.rank < 0.5) continue;
        if (p.kind === "landmark") continue;
      }
      const ele = Number(p.ele);
      if (!Number.isFinite(ele) || ele < -1000) continue;
      const az = wrap360(bearingRad(vp.lat, vp.lon, p.lat, p.lon) / DEG);
      const elev = Math.atan2(ele - vp.eye - dropM(d), d) / DEG;
      const sil = profileAt(profile, az * DEG) / DEG;
      const hidden = Number.isFinite(sil) ? elev <= sil + 0.03 : false;
      const anchor = hidden && Number.isFinite(sil) ? sil : elev;
      const tw = { peak: 0.34, city: 0.42, town: 0.31, village: 0.19,
        hamlet: 0.05, water: 0.24, landmark: 0.18 }[p.kind] || 0.1;
      const popS = Math.min(1, Math.log10(p.pop + 1) / 7);
      const prox = Math.max(0, 1 - d / 120000);
      const score = tw + popS * 0.34 + p.rank * 0.28 + prox * 0.22 + (hidden ? -0.12 : 0.06);
      candidates.push({
        p, d, az, elev, anchor, hidden, score,
        metrics: null,
      });
    }
    candidates.sort((a, b) => b.score - a.score);
    state.candidates = candidates;
    state.labels = [];
    state.boxes = [];
    state.selected = null;
    hideInfo();
  }

  function metricsFor(c) {
    if (c.metrics) return c.metrics;
    const p = c.p;
    const isPeak = p.kind === "peak";
    const nameSize = isPeak ? 15 : 14;
    const nameFont = `${isPeak ? 600 : 500} ${nameSize}px ${FONT}`;
    ctx.save();
    ctx.font = nameFont;
    const wName = ctx.measureText(p.name).width;
    let suffix = "";
    let wSuffix = 0;
    if (isPeak) {
      suffix = `  ${Math.round(p.ele).toLocaleString("en-US")} m`;
      ctx.font = `400 10px ${FONT}`;
      wSuffix = ctx.measureText(suffix).width;
    }
    let tag = "";
    let wTag = 0;
    if (c.hidden) {
      tag = "  hidden";
      ctx.font = `400 9px ${FONT}`;
      wTag = ctx.measureText(tag).width;
    }
    ctx.restore();
    const m = {
      nameFont,
      suffix,
      suffixFont: `400 10px ${FONT}`,
      tag,
      tagFont: `400 9px ${FONT}`,
      width: wName + wSuffix + wTag,
      height: 19,
      gap: isPeak ? 10 : 9,
      wName,
    };
    c.metrics = m;
    return m;
  }

  function project(azDeg, elevDeg, W, H) {
    const yaw = state.heading * DEG;
    const pitch = state.pitch * DEG;
    const az = azDeg * DEG;
    const el = elevDeg * DEG;
    const vx = Math.sin(az) * Math.cos(el);
    const vy = Math.cos(az) * Math.cos(el);
    const vz = Math.sin(el);
    const fx = Math.sin(yaw) * Math.cos(pitch);
    const fy = Math.cos(yaw) * Math.cos(pitch);
    const fz = Math.sin(pitch);
    const pz = vx * fx + vy * fy + vz * fz;
    if (pz <= 0.02) return null;
    const rx = Math.cos(yaw), ry = -Math.sin(yaw), rz = 0;
    const ux = -Math.sin(yaw) * Math.sin(pitch);
    const uy = -Math.cos(yaw) * Math.sin(pitch);
    const uz = Math.cos(pitch);
    const f = (W * 0.5) / Math.tan(state.fov * DEG / 2);
    const sx = (vx * rx + vy * ry + vz * rz) / pz;
    const sy = (vx * ux + vy * uy + vz * uz) / pz;
    return { x: W * 0.5 + f * sx, y: H * 0.5 - f * sy, f };
  }

  function layoutLabels(W, H) {
    const placed = [];
    const boxes = [];
    const max = 35;
    for (const c of state.candidates) {
      if (placed.length >= max) break;
      if (!state.showHidden && c.hidden) continue;
      const m = metricsFor(c);
      const pr = project(c.az, c.anchor, W, H);
      if (!pr) continue;
      if (pr.x < -80 || pr.x > W + 80 || pr.y < 70 || pr.y > H - 96) continue;
      const left = pr.x - m.width / 2;
      const box = {
        x: left, y: pr.y - m.height - m.gap,
        w: m.width, h: m.height,
        left, right: left + m.width, top: pr.y - m.height - m.gap, bottom: pr.y - m.gap,
      };
      if (box.left < 18 || box.right > W - 26 || box.top < 96 || box.bottom > H - 92) continue;
      let hit = false;
      for (const b of boxes) {
        if (box.left < b.right + 7 && box.right > b.left - 7 &&
            box.top < b.bottom + 5 && box.bottom > b.top - 5) { hit = true; break; }
      }
      if (hit) continue;
      boxes.push(box);
      placed.push({ c, x: pr.x, y: pr.y, m, box });
    }
    state.labels = placed;
    state.boxes = boxes;
  }

  let skyGrad = null;
  function horizonY(H, f) {
    return H * 0.5 + f * Math.tan(state.pitch * DEG);
  }

  function drawTerrainPath(profile, W, H, x0, y0, f) {
    const path = new Path2D();
    let started = false;
    for (let x = 0; x <= W + 3; x += 3) {
      const delta = Math.atan((x - W * 0.5) / f);
      const az = state.heading * DEG + delta;
      const elev = profileAt(profile, az);
      if (!Number.isFinite(elev)) continue;
      const y = y0 - f * Math.tan(elev - state.pitch * DEG);
      if (!started) { path.moveTo(x, y); started = true; }
      else path.lineTo(x, y);
    }
    if (!started) return null;
    path.lineTo(W + 3, H + 4);
    path.lineTo(0, H + 4);
    path.closePath();
    return path;
  }

  function draw() {
    state.raf = 0;
    const W = state.width, H = state.height;
    if (!W || !H || !current) return;
    const dpr = state.dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, W, H);
    const f = (W * 0.5) / Math.tan(state.fov * DEG / 2);
    const hz = horizonY(H, f);

    // Sky
    const sky = ctx.createLinearGradient(0, 0, 0, Math.max(1, hz + H * 0.08));
    sky.addColorStop(0, "#06101a");
    sky.addColorStop(0.44, "#143142");
    sky.addColorStop(0.8, "#6f8f9f");
    sky.addColorStop(1, "#c3d3da");
    ctx.fillStyle = sky;
    ctx.fillRect(0, 0, W, H);

    // Low sun glow
    const glow = ctx.createRadialGradient(W * 0.23, hz * 0.98, 0, W * 0.23, hz * 0.98, W * 0.72);
    glow.addColorStop(0, "rgba(235, 219, 188, 0.20)");
    glow.addColorStop(0.5, "rgba(235, 219, 188, 0.06)");
    glow.addColorStop(1, "rgba(235, 219, 188, 0)");
    ctx.fillStyle = glow;
    ctx.fillRect(0, 0, W, H);

    // Layers, far to near.
    const layers = [
      { key: "full", a: "#667e83", b: "#3f514d", line: "rgba(196, 214, 205, 0.28)" },
      { key: "mid", a: "#436056", b: "#24352c", line: "rgba(190, 210, 197, 0.22)" },
      { key: "near", a: "#1c2c23", b: "#080e0b", line: "rgba(182, 201, 187, 0.25)" },
    ];
    for (const layer of layers) {
      const path = drawTerrainPath(current.silhouette[layer.key], W, H, 0, H * 0.5, f);
      if (!path) continue;
      const g = ctx.createLinearGradient(0, Math.max(0, hz - H * 0.2), 0, H);
      g.addColorStop(0, layer.a);
      g.addColorStop(0.55, layer.b);
      g.addColorStop(1, "#070c09");
      ctx.fillStyle = g;
      ctx.fill(path);
      ctx.strokeStyle = layer.line;
      ctx.lineWidth = 1.15;
      ctx.stroke(path);
    }

    // Haze band at the horizon.
    const haze = ctx.createLinearGradient(0, hz - H * 0.12, 0, hz + H * 0.1);
    haze.addColorStop(0, "rgba(206, 222, 228, 0)");
    haze.addColorStop(0.65, "rgba(206, 222, 228, 0.10)");
    haze.addColorStop(1, "rgba(206, 222, 228, 0)");
    ctx.fillStyle = haze;
    ctx.fillRect(0, hz - H * 0.12, W, H * 0.22);

    drawElevationScale(W, H, f);
    layoutLabels(W, H);
    drawLabels(W, H);
    drawTape();

    if (!state.labels.length) {
      ctx.fillStyle = "rgba(147, 166, 178, 0.9)";
      ctx.font = `400 12px ${MONO}`;
      ctx.textAlign = "center";
      ctx.fillText("no labels in this bearing — drag to explore", W / 2, H / 2 + 52);
      ctx.textAlign = "left";
    }
  }

  function drawElevationScale(W, H, f) {
    ctx.save();
    ctx.font = `400 8px ${MONO}`;
    ctx.textAlign = "right";
    ctx.textBaseline = "middle";
    for (let deg = -10; deg <= 40; deg += 5) {
      const y = H * 0.5 - f * Math.tan((deg - state.pitch) * DEG);
      if (y < 130 || y > H - 140) continue;
      ctx.strokeStyle = "rgba(255,255,255,0.16)";
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(W - 12, y);
      ctx.lineTo(W - 4, y);
      ctx.stroke();
      ctx.fillStyle = "rgba(210,222,230,0.55)";
      ctx.fillText(`${deg}°`, W - 18, y);
    }
    ctx.restore();
  }

  function drawLabels(W, H) {
    for (const item of state.labels) {
      const { c, x, y, m, box } = item;
      const colorBase = KIND_COLOR[c.p.kind] || "#b7c4cc";
      const color = c.hidden ? withAlpha(colorBase, 0.55) : colorBase;
      const selected = state.selected && state.selected.p.id === c.p.id;
      ctx.save();
      if (selected) {
        roundRect(box.left - 9, box.top - 5, box.w + 18, box.h + 10, 8);
        ctx.fillStyle = "rgba(243,184,92,0.16)";
        ctx.fill();
      }
      ctx.shadowColor = "rgba(0,0,0,0.92)";
      ctx.shadowBlur = 4;
      ctx.shadowOffsetX = 1.5;
      ctx.shadowOffsetY = 1.5;
      ctx.textBaseline = "top";
      ctx.textAlign = "left";
      ctx.font = m.nameFont;
      ctx.fillStyle = color;
      const textY = y - m.height - m.gap;
      ctx.fillText(c.p.name, x - m.width / 2, textY);
      let px = x - m.width / 2 + m.wName;
      if (m.suffix) {
        ctx.font = m.suffixFont;
        ctx.fillStyle = c.hidden ? withAlpha(colorBase, 0.38) : withAlpha(colorBase, 0.72);
        ctx.fillText(m.suffix, px, textY + 3);
        px += ctx.measureText(m.suffix).width;
      }
      if (m.tag) {
        ctx.font = m.tagFont;
        ctx.fillStyle = withAlpha(colorBase, 0.65);
        ctx.fillText(m.tag, px, textY + 3);
      }
      ctx.restore();

      // Anchor dot and tick.
      ctx.save();
      ctx.fillStyle = color;
      ctx.beginPath();
      ctx.arc(x, y, 2.5, 0, Math.PI * 2);
      ctx.fill();
      ctx.strokeStyle = withAlpha(colorBase, 0.65);
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(x, y + 4);
      ctx.lineTo(x, y + 9);
      ctx.stroke();
      if (c.hidden) {
        ctx.strokeStyle = withAlpha(colorBase, 0.55);
        ctx.beginPath();
        ctx.arc(x, y, 7, 0, Math.PI * 2);
        ctx.stroke();
      }
      ctx.restore();
    }
  }

  function drawTape() {
    const rect = tape.getBoundingClientRect();
    const dpr = state.dpr;
    if (rect.width < 10) return;
    if (tape.width !== Math.round(rect.width * dpr) ||
        tape.height !== Math.round(rect.height * dpr)) {
      tape.width = Math.round(rect.width * dpr);
      tape.height = Math.round(rect.height * dpr);
    }
    const W = rect.width, H = rect.height;
    tctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    tctx.clearRect(0, 0, W, H);
    const cx = W / 2;
    const pxPerDeg = W / 74;
    const base = Math.floor(state.heading - 37);
    tctx.font = `400 8px ${MONO}`;
    tctx.textAlign = "center";
    tctx.textBaseline = "top";
    for (let d = base; d <= base + 74; d++) {
      const x = cx + (d - state.heading) * pxPerDeg;
      if (x < 4 || x > W - 4) continue;
      const norm = wrap360(d);
      const major = norm % 15 === 0;
      tctx.strokeStyle = major ? "rgba(255,255,255,0.5)" : "rgba(255,255,255,0.16)";
      tctx.lineWidth = major ? 1.3 : 1;
      tctx.beginPath();
      tctx.moveTo(x, H - (major ? 15 : 8));
      tctx.lineTo(x, H - 2);
      tctx.stroke();
      if (major) {
        const label = norm === 0 ? "N" : norm === 90 ? "E" : norm === 180 ? "S" :
          norm === 270 ? "W" : String(norm);
        tctx.fillStyle = norm % 45 === 0 ? "rgba(237,243,247,0.8)" : "rgba(147,166,178,0.7)";
        tctx.fillText(label, x, 0);
      }
    }
    tctx.strokeStyle = "#f3b85c";
    tctx.lineWidth = 2;
    tctx.beginPath();
    tctx.moveTo(cx, 0);
    tctx.lineTo(cx, 7);
    tctx.stroke();
    const headingEl = $("headingText");
    if (headingEl) {
      headingEl.textContent =
        `${Math.round(state.heading).toString().padStart(3, "0")}° ${compass(state.heading)}`;
    }
  }

  function withAlpha(hex, a) {
    const n = parseInt(hex.slice(1), 16);
    return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
  }
  function roundRect(x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }

  function requestDraw() {
    if (state.raf) return;
    state.raf = requestAnimationFrame(draw);
  }

  function resize() {
    const rect = scene.getBoundingClientRect();
    state.dpr = Math.min(2, window.devicePixelRatio || 1);
    state.width = Math.max(1, Math.round(rect.width));
    state.height = Math.max(1, Math.round(rect.height));
    scene.width = Math.round(state.width * state.dpr);
    scene.height = Math.round(state.height * state.dpr);
    const tr = tape.getBoundingClientRect();
    tape.width = Math.round(tr.width * state.dpr);
    tape.height = Math.round(tr.height * state.dpr);
    requestDraw();
  }

  function hitTest(x, y) {
    for (const item of state.labels) {
      const b = item.box;
      if (x >= b.left - 8 && x <= b.right + 8 && y >= b.top - 6 && y <= b.bottom + 10) {
        return item.c;
      }
    }
    return null;
  }

  function selectCandidate(c) {
    if (!c) {
      state.selected = null;
      hideInfo();
      requestDraw();
      return;
    }
    const already = state.selected && state.selected.p.id === c.p.id;
    state.selected = c;
    showInfo(c);
    requestDraw();
    return already;
  }

  function showInfo(c) {
    const p = c.p;
    $("infoKind").textContent = (KIND_LABEL[p.kind] || "Place").toUpperCase() +
      (c.hidden ? " · HIDDEN" : "");
    $("infoName").textContent = p.name;
    $("infoLocal").textContent = p.local && p.local !== p.name ? p.local : "";
    $("infoDist").textContent = fmtDistance(c.d);
    $("infoAz").textContent = `${Math.round(c.az)}° ${compass(c.az)}`;
    $("infoEle").textContent = fmtElev(p.ele);
    $("infoNote").textContent = c.hidden
      ? "Hidden behind the ridge — the terrain between you and this place rises above it."
      : "Clears the local skyline.";
    $("infoNote").style.color = c.hidden ? "#f3b85c" : "#7ec6e7";
    $("infoNote").style.background = c.hidden ? "rgba(243,184,92,0.08)" : "rgba(126,198,231,0.07)";
    $("infoNote").style.borderColor = c.hidden ? "rgba(243,184,92,0.16)" : "rgba(126,198,231,0.14)";
    $("infoDesc").textContent = p.desc && p.desc !== p.kind ? p.desc : "";
    $("infoSource").textContent = "30 m surface model · Copernicus GLO-30 · OSM · ODbL";
    $("info").classList.remove("hidden");
  }
  function hideInfo() { $("info").classList.add("hidden"); }

  function openList() {
    const rows = $("rows");
    rows.innerHTML = "";
    state.selected = null;
    hideInfo();
    requestDraw();
    if (!state.labels.length) {
      const W = scene.clientWidth;
      const H = scene.clientHeight;
      if (W && H) layoutLabels(W, H);
    }
    const list = state.labels.map((l) => l.c).sort((a, b) => a.az - b.az);
    $("listTitle").textContent = `${list.length} features in view`;
    $("listMeta").textContent = "SORTED BY BEARING · 30 M SURFACE MODEL";
    if (!list.length) {
      const empty = document.createElement("div");
      empty.className = "empty";
      empty.textContent = "Nothing on this bearing. Drag to explore.";
      rows.appendChild(empty);
    }
    for (const c of list) {
      const p = c.p;
      const row = document.createElement("button");
      row.type = "button";
      row.className = "row" + (c.hidden ? " hid" : "");
      row.innerHTML = `
        <span class="kd" style="background:${KIND_COLOR[p.kind] || "#b7c4cc"}"></span>
        <span class="nm">${escapeHtml(p.name)}</span>
        <span class="kd2">${c.hidden ? "HIDDEN" : (KIND_LABEL[p.kind] || "").toUpperCase()}</span>
        <span class="mi">${fmtDistance(c.d)} · ${Math.round(c.az)}°</span>`;
      row.addEventListener("click", () => {
        selectCandidate(c);
        $("list").classList.add("hidden");
      });
      rows.appendChild(row);
    }
    $("list").classList.remove("hidden");
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, (ch) => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
    }[ch]));
  }

  function setViewpoint(index) {
    state.vpIndex = clamp(index, 0, viewpoints.length - 1);
    const vp = viewpoints[state.vpIndex];
    state.heading = vp.heading;
    state.pitch = vp.pitch;
    state.fov = vp.fov;
    state.selected = null;
    hideInfo();
    $("viewpoint").value = String(state.vpIndex);
    $("vpName").textContent = vp.name;
    $("vpSub").textContent = `${vp.eye.toLocaleString("en-US")} m eye · ${vp.note}`;
    $("vpEyebrow").textContent = `${vp.name.toUpperCase()} · ${vp.subtitle.toUpperCase()}`;
    prepareViewpoint();
    requestDraw();
  }

  function bind() {
    const vpSelect = $("viewpoint");
    vpSelect.innerHTML = "";
    viewpoints.forEach((vp, i) => {
      const opt = document.createElement("option");
      opt.value = String(i);
      opt.textContent = vp.name;
      vpSelect.appendChild(opt);
    });
    vpSelect.addEventListener("change", (e) => setViewpoint(Number(e.target.value)));

    scene.addEventListener("pointerdown", (e) => {
      if (e.button !== undefined && e.button !== 0) return;
      scene.setPointerCapture(e.pointerId);
      state.drag = { x: e.clientX, y: e.clientY, heading: state.heading, pitch: state.pitch };
      state.moved = false;
      scene.classList.add("dragging");
    });
    scene.addEventListener("pointermove", (e) => {
      if (state.drag) {
        const dx = e.clientX - state.drag.x;
        const dy = e.clientY - state.drag.y;
        if (Math.abs(dx) + Math.abs(dy) > 3) state.moved = true;
        state.heading = wrap360(state.drag.heading - dx / scene.clientWidth * state.fov);
        state.pitch = clamp(state.drag.pitch + dy / scene.clientHeight * state.fov, -22, 38);
        requestDraw();
      } else {
        const rect = scene.getBoundingClientRect();
        const hit = hitTest(e.clientX - rect.left, e.clientY - rect.top);
        scene.classList.toggle("pointing", !!hit);
      }
    });
    const endDrag = (e) => {
      if (!state.drag) return;
      scene.classList.remove("dragging");
      const rect = scene.getBoundingClientRect();
      if (!state.moved) {
        const hit = hitTest(e.clientX - rect.left, e.clientY - rect.top);
        selectCandidate(hit);
      }
      state.drag = null;
    };
    scene.addEventListener("pointerup", endDrag);
    scene.addEventListener("pointercancel", endDrag);
    scene.addEventListener("wheel", (e) => {
      e.preventDefault();
      state.fov = clamp(state.fov + e.deltaY * 0.025, 24, 100);
      requestDraw();
    }, { passive: false });

    document.querySelectorAll("[data-act]").forEach((el) => {
      el.addEventListener("click", () => {
        const act = el.dataset.act;
        if (act === "reset") setViewpoint(state.vpIndex);
        if (act === "hidden") {
          state.showHidden = !state.showHidden;
          $("hiddenBtn").textContent = state.showHidden ? "Hidden on" : "Hidden off";
          $("hiddenBtn").setAttribute("aria-pressed", String(!state.showHidden));
          requestDraw();
        }
        if (act === "list") openList();
        if (act === "about") $("about").classList.remove("hidden");
        if (act === "close-info") { state.selected = null; hideInfo(); requestDraw(); }
        if (act === "close-list") $("list").classList.add("hidden");
        if (act === "close-about") $("about").classList.add("hidden");
      });
    });
    $("about").addEventListener("click", (e) => {
      if (e.target === $("about")) $("about").classList.add("hidden");
    });
    window.addEventListener("keydown", (e) => {
      if (e.key === "Escape") {
        $("about").classList.add("hidden");
        $("list").classList.add("hidden");
        state.selected = null;
        hideInfo();
        requestDraw();
      }
      if (e.key === "ArrowLeft") { state.heading = wrap360(state.heading - 2); requestDraw(); }
      if (e.key === "ArrowRight") { state.heading = wrap360(state.heading + 2); requestDraw(); }
      if (e.key === "ArrowUp") { state.pitch = clamp(state.pitch + 1, -22, 38); requestDraw(); }
      if (e.key === "ArrowDown") { state.pitch = clamp(state.pitch - 1, -22, 38); requestDraw(); }
      if (e.key === "+" || e.key === "=") { state.fov = clamp(state.fov - 3, 24, 100); requestDraw(); }
      if (e.key === "-") { state.fov = clamp(state.fov + 3, 24, 100); requestDraw(); }
    });
    window.addEventListener("resize", resize);
    if (window.ResizeObserver) new ResizeObserver(resize).observe(scene.parentElement);
  }

  function boot() {
    if (!DATA || !DATA.viewpoints || !DATA.viewpoints.length) {
      document.body.innerHTML = "<p style='color:#fff;font:14px sans-serif;padding:40px'>" +
        "AR_Horizon data missing. Run <code>python tools/export_web.py</code> first.</p>";
      return;
    }
    viewpoints = DATA.viewpoints;
    const pack = DATA.pack;
    if (pack && pack.name) {
      $("offlineChip").textContent = "OFFLINE · NO SERVER";
      $("attrib").textContent = "30 m surface model · " + (pack.dem || "Copernicus GLO-30") +
        " · places © OpenStreetMap contributors (ODbL)";
    }
    bind();
    resize();
    setViewpoint(0);
  }

  boot();
})();
