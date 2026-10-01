// SPDX-License-Identifier: LGPL-3.0-or-later
// The stream's pop-ups and its banner, drawn over the game by overlay.html. Stream only: nothing here reaches the agent.
// A pop-up is what the agent just looked at (harness/runner/feed.py writes pops.json), drawn big for a few seconds;
// the banner says in plain words why the world stands still, from the clock's own state.
"use strict";
(() => {
const $ = id => document.getElementById(id);
const esc = s => String(s ?? "").replace(/[&<>"]/g, c => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;"}[c]));
const ease = t => 1 - Math.pow(1 - Math.max(0, Math.min(1, t)), 3);
const pretty = id => String(id || "?").split(":").pop().replace(/[._]/g, " ").replace(/^(block|tile|item|gt) /, "");
const named = v => v.name || pretty(v.id);
const KEY = () => getComputedStyle($("canvas")).getPropertyValue("--key").trim() || "#ffb547";

// Colours: a few common blocks get their own; everything else a stable hue from its id, never a list of what matters.
const PALETTE = [["sandstone", "#cdbd84"], ["sand", "#dfd5a2"], ["dirt", "#7d5636"], ["grass", "#5f9a38"], ["cobblestone", "#6c6c6e"], ["stone", "#8b8b8d"],
  ["chest", "#b07a30"], ["door", "#a3804a"], ["torch", "#ffd34d"], ["water", "#3d6fe0"], ["lava", "#ff7a1a"], ["log", "#6b4f2a"], ["planks", "#b08850"],
  ["leaves", "#3f7f2a"], ["gravel", "#86807b"], ["clay", "#9fa4b0"], ["snow", "#f2f4f8"], ["ice", "#9cc4ff"]];
const hues = new Map();
function colour(id) {
  id = String(id || "");
  if (!hues.has(id)) {
    const p = PALETTE.find(([k]) => id.toLowerCase().includes(k)); let h = 0;
    for (const c of id) h = (h * 31 + c.charCodeAt(0)) % 360;
    hues.set(id, p ? p[1] : `hsl(${h} 35% 52%)`);
  }
  return hues.get(id);
}
const shades = new Map();
function shade(col, f) {  // a face's colour, cached: a big view draws thousands of faces a frame
  const k = col + f; if (shades.has(k)) return shades.get(k);
  const g = document.createElement("canvas").getContext("2d"); g.fillStyle = col; g.fillRect(0, 0, 1, 1);
  const [r, gg, b] = g.getImageData(0, 0, 1, 1).data, v = `rgb(${r * f | 0},${gg * f | 0},${b * f | 0})`; shades.set(k, v); return v;
}

function card(label, tool, caption, bodyClass = "") {
  const el = document.createElement("div"); el.className = "pop";
  el.innerHTML = `<div class="head"><i class="eye"></i>${esc(label)}<span class="tool">${esc(tool)}</span></div>` +
    `<div class="body ${bodyClass}"></div><div class="cap">${caption}</div><div class="timer"></div>`;
  return el;
}
function canvasIn(el, w = 520, h = 310) { const cv = document.createElement("canvas"); cv.width = w; cv.height = h; el.querySelector(".body").append(cv); return cv.getContext("2d"); }
function animate(el, frame, until = 9000) {  // runs frame(t) until the card is gone or its animation is over
  const t0 = performance.now();
  (function go(now) { if (el.dead) return; frame(now - t0); if (now - t0 < until) requestAnimationFrame(go); })(t0);
}
// Which legend entries a viewer wants named: the blocks that are more than a block (a tile entity, named by the game), else the rarest.
function notable(legend, most = 6) {
  const all = Object.entries(legend), tiles = all.filter(([, v]) => v.tile);
  return new Set((tiles.length ? tiles : all.filter(([, v]) => v.count <= 2)).sort((a, b) => a[1].count - b[1].count).slice(0, most).map(([ch]) => ch));
}

/* mb_view: the box it asked for, built layer by layer in 3D, then x-rayed down to the things in it */
function view(p) {
  const d = p.data, L = d.layers, D = L[0].rows.length, W = L[0].rows[0].length, [ox, oy, oz] = d.origin;
  const el = card("Looking at the blocks", "mb_view", `<b>${W}×${L.length}×${D}</b> at ${ox},${oy},${oz} · ${L.length} layer${L.length > 1 ? "s" : ""}`);
  const g = canvasIn(el), s = Math.min(30, 300 / ((W + D) * 0.866), 296 / ((W + D) / 2 + L.length)), dx = s * 0.866, dy = s / 2;
  const rare = notable(d.legend), solid = (li, r, c) => { const ch = L[li]?.rows[r]?.[c]; return ch && ch !== "." && (ch in d.legend || ch === "@"); };
  const cells = [];
  L.forEach((layer, li) => layer.rows.forEach((row, r) => [...row].forEach((ch, c) => {
    if (ch === "." || !(ch in d.legend || ch === "@")) return;
    // a block with all three drawn faces covered is never seen: skipping those keeps a 9000-cell view smooth
    if (!rare.has(ch) && solid(li + 1, r, c) && solid(li, r + 1, c) && solid(li, r, c + 1)) return;
    cells.push({x: c, y: layer.y - oy, z: r, li, ch, id: ch === "@" ? "you" : d.legend[ch].id, rare: rare.has(ch), you: ch === "@"});
  })));
  const cx = 18 + D * dx, cy = 8 + L.length * s, P = (x, y, z) => [cx + (x - z) * dx, cy + (x + z) * dy - y * s];
  const key = KEY(), lx = Math.min(520 - 190, cx + W * dx + 16);
  function cube(c, alpha, lift) {
    const small = c.you || /torch|tallgrass|flower|sapling/.test(c.id), k = small ? 0.45 : 1, o = (1 - k) / 2, h = c.you ? 1.8 : small ? 0.5 : 1;
    const col = c.you ? "#ffffff" : colour(c.id), p = (a, b, e) => { const q = P(c.x + o + a * k, c.y + b * h, c.z + o + e * k); return [q[0], q[1] - lift]; };
    g.globalAlpha = alpha;
    const face = (pts, f) => { g.beginPath(); pts.forEach((q, i) => i ? g.lineTo(...q) : g.moveTo(...q)); g.closePath(); g.fillStyle = shade(col, f); g.fill(); };
    face([p(0, 1, 0), p(1, 1, 0), p(1, 1, 1), p(0, 1, 1)], 1); face([p(0, 0, 1), p(1, 0, 1), p(1, 1, 1), p(0, 1, 1)], .78); face([p(1, 0, 0), p(1, 0, 1), p(1, 1, 1), p(1, 1, 0)], .6);
    if (c.rare && alpha > .9) { g.strokeStyle = key; g.lineWidth = 2; g.beginPath(); [p(0, 1, 0), p(1, 1, 0), p(1, 1, 1), p(0, 1, 1), p(0, 1, 0)].forEach((q, i) => i ? g.lineTo(...q) : g.moveTo(...q)); g.stroke(); }
    g.globalAlpha = 1; return p(.5, 1, .5);
  }
  const step = Math.min(230, 1800 / L.length), built = L.length * step + 400;
  const order = [...cells.filter(c => !c.rare && !c.you), ...cells.filter(c => c.rare || c.you)];
  animate(el, t => {
    g.clearRect(0, 0, 520, 310);
    const xray = ease((t - built - 300) / 900), tops = {};
    for (const c of xray > 0 ? order : cells) {
      const k = ease((t - c.li * step) / 380); if (k <= 0) continue;
      const top = cube(c, c.rare || c.you ? k : k * (1 - .7 * xray), (1 - k) * 70);
      if (c.rare) (tops[c.ch] ??= []).push(top);
    }
    const la = ease((t - built - 1000) / 500); if (la <= 0) return;
    g.globalAlpha = la; g.font = "16px Monocraft"; g.textBaseline = "middle"; let row = 0;
    const byName = {}; for (const [ch, pts] of Object.entries(tops)) (byName[named(d.legend[ch])] ??= []).push(...pts);
    for (const [name, pts] of Object.entries(byName).sort((a, b) => a[1][0][1] - b[1][0][1])) {
      const [px, py] = pts[0], ly = 30 + row++ * 34;
      g.strokeStyle = key; g.lineWidth = 2; g.beginPath(); g.moveTo(px, py); g.lineTo(lx - 8, ly); g.stroke(); g.fillStyle = key; g.fillRect(px - 3, py - 3, 6, 6);
      g.fillStyle = "#e9eaee"; g.fillText((name + (pts.length > 1 ? " ×" + pts.length : "")).slice(0, 22), lx, ly);
    }
    g.globalAlpha = 1;
  }, built + 2000);
  return el;
}

/* mb_view look_down: the top block of every column, as a map that scans in row by row, then its things get names */
function down(p) {
  const d = p.data, rows = d.layers[0].rows, H = rows.length, W = rows[0].length, [ox, , oz] = d.origin;
  const el = card("Looking down on the area", "mb_view look_down", `<b>${W}×${H} columns</b> from ${ox},${oz} · the top block of each`);
  const g = canvasIn(el), cell = Math.max(1, Math.floor(Math.min(290 / H, 300 / W))), x0 = 12, y0 = Math.floor((310 - H * cell) / 2), key = KEY(), lx = x0 + W * cell + 22;
  const marks = notable(d.legend, 7), where = {}, you = [];
  rows.forEach((row, z) => [...row].forEach((ch, x) => { if (marks.has(ch)) (where[ch] ??= []).push([x, z]); if (ch === "@") you.push([x, z]); }));
  if (!you.length && d.you) you.push([d.you[0] - ox, d.you[2] - oz]);
  const byName = {}; for (const [ch, pts] of Object.entries(where)) (byName[named(d.legend[ch])] ??= []).push(...pts);
  const labels = Object.entries(byName).sort((a, b) => a[1][0][1] - b[1][0][1]);
  animate(el, t => {
    const shown = Math.min(H, Math.floor(t / 1400 * H)), dim = ease((t - 1700) / 600);
    g.clearRect(0, 0, 520, 310);
    for (let z = 0; z < shown; z++) for (let x = 0; x < W; x++) {
      const ch = rows[z][x], v = d.legend[ch]; if (!v) continue;
      g.globalAlpha = marks.has(ch) ? 1 : 1 - .45 * dim; g.fillStyle = colour(v.id);
      g.fillRect(x0 + x * cell, y0 + z * cell, cell - (cell > 6 ? 1 : 0), cell - (cell > 6 ? 1 : 0));
    }
    g.globalAlpha = 1;
    if (shown < H) { g.fillStyle = key; g.fillRect(x0, y0 + shown * cell, W * cell, 2); return; }
    for (const [x, z] of you) { const c = [x0 + x * cell + cell / 2, y0 + z * cell + cell / 2]; g.fillStyle = "#fff"; g.beginPath(); g.moveTo(c[0], c[1] - 7); g.lineTo(c[0] + 5, c[1] + 5); g.lineTo(c[0] - 5, c[1] + 5); g.fill(); }
    const la = ease((t - 2000) / 500); if (la <= 0) return;
    g.globalAlpha = la; g.font = "16px Monocraft"; g.textBaseline = "middle"; g.strokeStyle = key; g.lineWidth = 2;
    labels.forEach(([name, pts], i) => {
      const ly = 28 + i * 36, pulse = 2 + Math.sin(t / 180) * 1.5;
      for (const [x, z] of pts.slice(0, 40)) g.strokeRect(x0 + x * cell - pulse, y0 + z * cell - pulse, cell + 2 * pulse, cell + 2 * pulse);
      const [x, z] = pts[0]; g.beginPath(); g.moveTo(x0 + x * cell + cell, y0 + z * cell + cell / 2); g.lineTo(lx - 8, ly); g.stroke();
      g.fillStyle = "#e9eaee"; g.fillText((name + (pts.length > 1 ? " ×" + pts.length : "")).slice(0, 18), lx, ly);
    });
    g.globalAlpha = 1;
  });
  return el;
}

/* mb_obs entities: a sweep around it; hostiles red, dropped items gold, the rest green */
function radar(p) {
  const d = p.data, R = d.radius, me = d.me, ents = d.entities, hostile = ents.filter(e => e.hostile), items = ents.filter(e => e.stack);
  const el = card("Looking around", "mb_obs entities", `<b>${hostile.length} hostile</b>, ${items.length} items, ${ents.length - hostile.length - items.length} other within ${R} blocks`);
  const list = document.createElement("div"); list.className = "list";
  const rel = e => { if (!me) return ""; const h = Math.round(e.pos[1] - me[1]); return h < -2 ? ` ▼${-h}` : h > 2 ? ` ▲${h}` : ""; };
  const piles = new Map(); items.forEach(e => piles.set(e.stack.name, (piles.get(e.stack.name) || 0) + (e.stack.count || 1)));
  list.innerHTML = hostile.slice(0, 7).map(e => `<div class="h">■ ${esc((e.name || pretty(e.type)).slice(0, 18))} ${Math.round(e.distance)}m${rel(e)}${e.visible ? "" : " (unseen)"}</div>`).join("") +
    (piles.size ? `<div class="n" style="margin-top:8px">on the ground:</div>` + [...piles].slice(0, 6).map(([n, k]) => `<div class="i">· ${esc(n.slice(0, 20))}${k > 1 ? " ×" + k : ""}</div>`).join("") : "");
  el.querySelector(".body").append(list);
  const g = canvasIn(el), C = [160, 155], rad = 140, sc = rad / R;
  const blips = me ? ents.map(e => { const x = (e.pos[0] - me[0]) * sc, y = (e.pos[2] - me[2]) * sc;
    return {x: C[0] + x, y: C[1] + y, a: (Math.atan2(y, x) + Math.PI * 2) % (Math.PI * 2), e, col: e.hostile ? "#ff6b5e" : e.stack ? "#ffd75e" : "#7fd36b", size: e.hostile ? 9 : e.stack ? 4 : 6}; }) : [];
  animate(el, ms => {
    const t = ms / 1000, sweep = (t * Math.PI * 2 / 2.2) % (Math.PI * 2), grow = ease(t / .6);
    g.clearRect(0, 0, 520, 310); g.strokeStyle = "#2b3a2f"; g.lineWidth = 2;
    for (const f of [1, .66, .33]) { g.beginPath(); g.arc(C[0], C[1], rad * f * grow, 0, Math.PI * 2); g.stroke(); }
    g.beginPath(); g.moveTo(C[0] - rad, C[1]); g.lineTo(C[0] + rad, C[1]); g.moveTo(C[0], C[1] - rad); g.lineTo(C[0], C[1] + rad); g.stroke();
    g.fillStyle = "#858893"; g.font = "14px Monocraft"; g.textAlign = "center"; g.fillText("N", C[0], C[1] - rad + 10); g.fillText(R + "m", C[0] + rad - 18, C[1] - 6); g.textAlign = "start";
    const grad = g.createConicGradient(sweep - .9, C[0], C[1]); grad.addColorStop(0, "rgba(127,211,107,0)"); grad.addColorStop(.143, "rgba(127,211,107,.28)"); grad.addColorStop(.144, "rgba(0,0,0,0)");
    g.fillStyle = grad; g.beginPath(); g.moveTo(...C); g.arc(C[0], C[1], rad * grow, sweep - .9, sweep); g.closePath(); g.fill();
    if (t > .6) for (const b of blips) {
      const lit = Math.max(.35, 1 - (((sweep - b.a) + Math.PI * 2) % (Math.PI * 2)) / (Math.PI * 2));
      g.globalAlpha = b.e.hostile ? Math.max(.6, lit) : lit; g.fillStyle = b.col; if (b.e.hostile) { g.shadowColor = b.col; g.shadowBlur = 10; }
      g.fillRect(b.x - b.size / 2, b.y - b.size / 2, b.size, b.size); g.shadowBlur = 0;
    }
    g.globalAlpha = 1; g.fillStyle = "#fff"; g.beginPath(); g.moveTo(C[0], C[1] - 8); g.lineTo(C[0] + 6, C[1] + 6); g.lineTo(C[0] - 6, C[1] + 6); g.closePath(); g.fill();
  });
  return el;
}

/* mb_map: its map image, drifting in on where it stands */
function map(p) {
  const d = p.data, b = d.bounds || {}, pl = d.player || [], span = (b.maxX - b.minX + 1) || 1, spanZ = (b.maxZ - b.minZ + 1) || 1;
  const fx = Math.max(0, Math.min(100, (pl[0] - b.minX) / span * 100)) || 50, fz = Math.max(0, Math.min(100, (pl[2] - b.minZ) / spanZ * 100)) || 50;
  const el = card("Checking the map", "mb_map", `<b>${span}×${spanZ} blocks</b> around ${pl[0] ?? "?"},${pl[2] ?? "?"}${d.layer ? " · " + esc(d.layer) + " layer" : ""}`, "map");
  // object-position and transform-origin at the same fraction keep the player's pixel fixed while the image zooms toward it
  el.querySelector(".body").innerHTML = `<img src="/overlay/${esc(p.image)}" style="object-position:${fx}% ${fz}%;transform-origin:${fx}% ${fz}%">`;
  return el;
}
function shot(p) {
  const el = card("Took a screenshot", "mb_screenshot", "what the game window showed it, <b>the moment it asked</b>", "shot");
  el.querySelector(".body").innerHTML = `<img src="/overlay/${esc(p.image)}"><span class="rec">● SNAP</span>`; return el;
}

/* mb_scan: where the blocks it searched for are, around it, pinged one after another */
function scan(p) {
  const d = p.data, m = d.matches.filter(x => Array.isArray(x.pos)), me = d.me;
  const el = card("Searching for blocks", "mb_scan", `<b>${d.found} found</b> · ${esc(d.what)}`);
  const g = canvasIn(el), pts = [...m.map(x => x.pos), ...(me ? [me] : [])], key = KEY();
  const xs = pts.map(q => q[0]), zs = pts.map(q => q[2]), minX = Math.min(...xs), minZ = Math.min(...zs);
  const w = Math.max(...xs) - minX, h = Math.max(...zs) - minZ, span = Math.max(8, w, h) * 1.2, sc = 270 / span;
  const X = q => 155 + (q[0] - minX - w / 2) * sc, Z = q => 155 + (q[2] - minZ - h / 2) * sc;
  const kinds = new Map(); m.forEach(x => kinds.set(x.name, (kinds.get(x.name) || 0) + 1));
  const near = me && m.length ? Math.round(Math.min(...m.map(x => Math.hypot(x.pos[0] - me[0], x.pos[1] - me[1], x.pos[2] - me[2])))) : null;
  animate(el, t => {
    g.clearRect(0, 0, 520, 310); g.strokeStyle = "#22252b"; g.lineWidth = 1;
    for (let i = 0; i <= 270; i += 30) { g.beginPath(); g.moveTo(20 + i, 20); g.lineTo(20 + i, 290); g.moveTo(20, 20 + i); g.lineTo(290, 20 + i); g.stroke(); }
    m.forEach((x, i) => {
      const at = 300 + i * Math.min(120, 1500 / m.length), k = ease((t - at) / 300); if (k <= 0) return;
      const ring = ((t - at) % 1600) / 1600;
      g.strokeStyle = key; g.globalAlpha = (1 - ring) * k; g.beginPath(); g.arc(X(x.pos), Z(x.pos), 4 + ring * 18, 0, Math.PI * 2); g.stroke();
      g.globalAlpha = k; g.fillStyle = colour(x.name); g.fillRect(X(x.pos) - 4, Z(x.pos) - 4, 8, 8);
    });
    g.globalAlpha = 1;
    if (me) { const c = [X(me), Z(me)]; g.fillStyle = "#fff"; g.beginPath(); g.moveTo(c[0], c[1] - 8); g.lineTo(c[0] + 6, c[1] + 6); g.lineTo(c[0] - 6, c[1] + 6); g.fill(); }
    g.font = "16px Monocraft"; g.textBaseline = "top"; g.fillStyle = "#e9eaee"; let y = 24;
    if (!m.length) g.fillText("nothing here", 320, y);
    for (const [n, k] of [...kinds].slice(0, 7)) { g.fillStyle = colour(n); g.fillRect(320, y + 4, 10, 10); g.fillStyle = "#e9eaee"; g.fillText(`${n.slice(0, 15)}${k > 1 ? " ×" + k : ""}`, 338, y); y += 28; }
    if (near != null) { g.fillStyle = "#858893"; g.fillText(`nearest ${near}m away`, 320, y + 8); }
  }, 4000);
  return el;
}

/* mb_inventory: what it carries, hotbar first, the held slot marked; or its totals */
function inventory(p) {
  const d = p.data, rows = d.totals ? d.totals.map(x => ({name: x.name, count: x.count})) : d.slots.map(x => ({...x, held: x.slot === d.selected}));
  const el = card("Checking the inventory", "mb_inventory", d.totals ? `<b>${rows.length} kinds</b> of item in all` :
    `<b>${rows.length} of 36 slots</b> filled${(rows.find(x => x.held) || {}).name ? " · holding " + esc(rows.find(x => x.held).name) : ""}`, "inv");
  el.querySelector(".body").innerHTML = rows.slice(0, 26).map((x, i) => `<div class="${x.held ? "held" : ""}" style="animation-delay:${i * 45}ms">` +
    `<i style="background:${colour(x.id || x.name)}"></i><span>${esc(x.name)}</span><em>${x.count > 1 ? "×" + x.count : ""}</em></div>`).join("");
  return el;
}

/* mb_recipes: the first recipe as a crafting grid: each ingredient a letter, a key beside it, the result past the arrow */
function recipe(p) {
  const d = p.data, ins = d.inputs.filter(x => x.name), letters = new Map();
  ins.forEach(x => letters.has(x.name) || letters.set(x.name, {ch: "ABCDEFGHIJ"[letters.size] || "?", id: x.id}));
  const el = card("Looking up a recipe", "mb_recipes", `<b>${esc(d.target)}</b>${d.handler ? " · " + esc(d.handler) : ""}${d.total > 1 ? ` · 1 of ${d.total}` : ""}`, "recipe");
  const xs = ins.map(x => x.x), ys = ins.map(x => x.y), mx = Math.min(...xs), my = Math.min(...ys), S = 58;
  const at = x => [Math.round((x.x - mx) / 18) * S, Math.round((x.y - my) / 18) * S];
  const cols = Math.max(1, ...ins.map(x => at(x)[0] / S + 1)), rowsN = Math.max(1, ...ins.map(x => at(x)[1] / S + 1));
  const slot = (x, l, extra = "") => `<div class="slot" style="${extra}"><i style="background:${colour(l.id || x.name)}">${l.ch || ""}</i>${x.count > 1 ? `<em>${x.count}</em>` : ""}</div>`;
  const grid = ins.map((x, i) => { const [a, b] = at(x); return slot(x, letters.get(x.name), `left:${a}px;top:${b}px;animation-delay:${i * 90}ms`); }).join("");
  const top = (310 - rowsN * S) / 2, arrowX = 20 + cols * S + 14;
  el.querySelector(".body").innerHTML = `<div class="grid" style="left:20px;top:${top}px">${grid}</div>` +
    `<div class="arrow" style="left:${arrowX}px;top:${top + rowsN * S / 2 - 14}px">→</div>` +
    (d.result && d.result.name ? `<div class="grid" style="left:${arrowX + 44}px;top:${top + rowsN * S / 2 - S / 2}px">${slot(d.result, {id: d.result.id}, "left:0;top:0;animation-delay:900ms")}</div>` : "") +
    `<div class="key">${[...letters].map(([n, l]) => `<div><b style="background:${colour(l.id || n)}">${l.ch}</b>${esc(n.slice(0, 22))}</div>`).join("")}` +
    (d.result && d.result.name ? `<div class="out">makes ${esc(d.result.name)}${d.result.count > 1 ? " ×" + d.result.count : ""}</div>` : "") + `</div>`;
  return el;
}

/* small cards: a block it inspected, a note it read or wrote */
function block(p) {
  const d = p.data, el = document.createElement("div"); el.className = "pop small";
  el.innerHTML = `<div class="head"><i class="eye"></i>Inspecting a block<span class="tool">${esc(p.tool)}</span></div>` +
    `<div class="hover"><b>${esc(d.title)}</b>${d.lines.filter(Boolean).slice(0, 5).map(l => `<div>${esc(l)}</div>`).join("")}${d.pos ? `<div class="n">at ${d.pos.join(", ")}</div>` : ""}</div><div class="timer"></div>`;
  return el;
}
function note(p) {
  const d = p.data, el = document.createElement("div"); el.className = "pop small";
  el.innerHTML = `<div class="head"><i class="eye"></i>${d.wrote ? "Writing in its journal" : "Reading its journal"}<span class="tool">${esc(p.tool)}</span></div>` +
    `<div class="paper"><b>${esc(d.title)}</b>${d.titles ? d.titles.map(t => `<div>· ${esc(t)}</div>`).join("") : `<p>${esc(d.short || d.text)}</p>`}</div><div class="timer"></div>`;
  return el;
}

const MAKE = {view, down, radar, map, shot, scan, inventory, recipe, block, note}, SMALL = new Set(["block", "note"]);
const LIFE = {big: 8000, small: 4000}, DWELL = 3000, QUIET = 5000;  // a small card never cuts into the first QUIET ms of a big one
let shown = null, pending = null, seen = null;
function life(k) { return SMALL.has(k) ? LIFE.small : LIFE.big; }
function swap(p) {
  if (shown) { const old = shown.el; old.dead = true; old.classList.add("out"); setTimeout(() => old.remove(), 360); }
  shown = null; if (!p) return;
  let el; try { el = MAKE[p.kind](p); } catch (e) { console.warn("pop-up", p.kind, e); return; }
  el.style.setProperty("--life", life(p.kind) + "ms"); shown = {p, el, at: performance.now()};
  setTimeout(() => el.dead || $("pops").append(el), 200);
}
function offer(p) {
  if (!MAKE[p.kind]) return;
  const age = shown ? performance.now() - shown.at : Infinity;
  if (SMALL.has(p.kind) && shown && !SMALL.has(shown.p.kind) && age < QUIET) return;  // a glance never hides a picture just put up
  if (pending && SMALL.has(p.kind) && !SMALL.has(pending.kind)) return;
  pending = p;
}
setInterval(() => {
  const age = shown ? performance.now() - shown.at : Infinity;
  if (pending && age >= DWELL) { swap(pending); pending = null; }
  else if (shown && age >= life(shown.p.kind)) swap(null);
}, 200);

function pops(list, now) {
  list = list || [];
  if (seen === null) { seen = list.length ? list[list.length - 1].seq : 0; const last = list[list.length - 1]; if (last && now - last.ts < 4) offer(last); return; }
  if (list.length && list[list.length - 1].seq < seen) seen = 0;  // a new run started a new list
  for (const p of list) if (p.seq > seen) { seen = p.seq; offer(p); }
  // a journal summary arrives a poll or two after its card: swap it into the card already up or waiting
  for (const p of list) {
    if (p.kind !== "note" || !p.data.short) continue;
    if (pending && pending.seq === p.seq) pending = p;
    if (shown && shown.p.seq === p.seq && !shown.p.data.short) { shown.p = p; const t = shown.el.querySelector(".paper p"); if (t) t.textContent = p.data.short; }
  }
}

/* the banner: why the world stands still, in words, with the specifics */
const PLAIN = {requested_pause: null, step: ["STEPPING", "step", "letting the world run a few ticks at a time"],
  threat: null, health_dropped: null, health_threshold: null, air_threshold: ["LOW AIR", "bad", "its air ran low"], burning: ["BURNING", "bad", "it caught fire"],
  food_threshold: ["HUNGRY", "warn", "its food ran low"], agent_disconnected: ["RECONNECTING", "dim", "the agent's connection dropped"],
  client_disconnected: ["RECONNECTING", "dim", "the game client dropped"], client_unresponsive: ["RECONNECTING", "dim", "the game client stopped answering"],
  action_failed: ["ACTION FAILED", "warn", "an action failed, so the world stopped"], operator_hold: ["OPERATOR HOLD", "dim", "paused by the operator"]};
const mob = t => pretty(t).replace(/^(entity|SpecialMobs|mob) /i, "").replace(/([a-z])([A-Z])/g, "$1 $2");
function banner(d) {
  const c = d.clock, st = d.status || {}, b = $("banner");
  let chip = null;
  const last = [...(d.feed || [])].reverse().find(e => (e.kind === "tool" || e.kind === "fail") && e.tool !== "mb_time");  // not the pause itself
  const hp = c && c.player && c.player.health != null ? ` · ${Math.round(c.player.health)} of 20 health` : "";
  if (!c || st.state === "ended" || st.state === "game_down") chip = null;
  else if (c.held) chip = PLAIN.operator_hold;
  else if (["between_turns", "backing_off"].includes(st.state)) chip = ["RECONNECTING", "dim", st.state === "backing_off" ? "the last turn failed; it starts again shortly" : "between turns; the agent picks up where it left off"];
  else if (!c.paused) chip = null;  // nothing to cover: the game draws its strip only while paused
  else {
    const r = String(c.reason || ""), th = c.threat;
    if (r === "requested_pause") chip = ["THINKING", "key", `world paused for ${secs(d.now - (st.since || d.now))}` + (last ? " · after: " + last.text : "")];
    else if (r === "threat" && th) chip = ["THREAT", "bad", `${mob(th.type)} ${Math.round(th.distance)}m away` + (th.swelling ? ", about to explode" : th.ranged ? ", it shoots" : "")];
    else if (r === "threat") chip = ["THREAT", "bad", "a hostile mob came close"];
    else if (r === "health_dropped") chip = ["HIT", "bad", "it took damage" + hp];
    else if (r === "health_threshold") chip = ["LOW HEALTH", "bad", "health below its guard" + hp];
    else if (r.startsWith("interrupt:")) chip = ["ITS OWN WATCH", "key", "stopped by its own watch: " + r.slice(10).replace(/_/g, " ")];
    else chip = PLAIN[r] || ["PAUSED", "dim", r.replace(/_/g, " ")];
    if (c.stepping && r !== "step") chip = PLAIN.step;
    if (c.player && c.player.health <= 0) chip = ["DEAD", "bad", "it died; respawning comes next"];
  }
  if (!chip) { b.className = "off"; return; }
  b.className = "on " + chip[1];
  const html = `<b>${esc(chip[0])}</b><span>${esc(chip[2])}</span>`;
  if (b.dataset.html !== html) { b.dataset.html = html; b.innerHTML = html; }
}
const secs = s => { s = Math.max(0, Math.floor(s)); return s < 60 ? s + "s" : Math.floor(s / 60) + "m " + String(s % 60).padStart(2, "0") + "s"; };

window.Pops = {draw(d) { try { pops(d.pops, d.now); } catch (e) { console.warn(e); } try { banner(d); } catch (e) { console.warn(e); } }, show: swap, MAKE};
})();
