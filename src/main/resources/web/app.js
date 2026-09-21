"use strict";

const state = {
  session: null,
  revision: null,
  fingerprints: null,
};

const $ = (id) => document.getElementById(id);

async function api(method, url, body) {
  const options = { method, headers: {} };
  if (body !== undefined) {
    options.headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(body);
  }
  const resp = await fetch(url, options);
  const text = await resp.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch (_) { /* non-json */ }
  if (!resp.ok) {
    const message = json && json.error ? json.error : ("HTTP " + resp.status);
    const err = new Error(message);
    err.conflict = !!(json && json.conflict);
    throw err;
  }
  return json;
}

async function refreshSessionList(selectId) {
  const data = await api("GET", "/api/sessions");
  const select = $(selectId);
  select.innerHTML = "";
  for (const s of data.sessions) {
    const option = document.createElement("option");
    option.value = s.id;
    option.textContent = s.id + " (rev " + s.revision + ")";
    select.appendChild(option);
  }
  return data.sessions;
}

async function ensureSession() {
  if (state.session) {
    return state.session;
  }
  const sessions = await refreshSessionList("session-select");
  if (sessions.length > 0) {
    state.session = sessions[sessions.length - 1].id;
  } else {
    const created = await api("POST", "/api/sessions");
    state.session = created.id;
  }
  $("session-select").value = state.session;
  return state.session;
}

function loadState(view) {
  state.revision = view.revision;
  state.fingerprints = view.fingerprints;
  $("revision").textContent = "revision " + view.revision;
  renderFingerprints(view.fingerprints);
  renderDiagnostics(view.sides);
  renderConflicts(view);
  renderGraph(view.graph);
  renderIntegrity(view.integrity);
  renderCleanup(view);
  renderOutput(view);
}

function renderFingerprints(fp) {
  $("fp-base").textContent = short(fp.base);
  $("fp-left").textContent = short(fp.left);
  $("fp-right").textContent = short(fp.right);
}

function short(fp) {
  return fp ? fp.slice(0, 19) + "…" : "";
}

function renderDiagnostics(sides) {
  const names = ["base", "left", "right"];
  sides.forEach((side, i) => {
    const box = $("diag-" + names[i]);
    box.innerHTML = "";
    if (!side.hasErrors && side.diagnostics.length === 0) {
      box.innerHTML = '<span class="ok">✓ 解析与校验通过</span>';
      return;
    }
    for (const d of side.diagnostics) {
      const span = document.createElement("span");
      span.className = d.severity.toLowerCase();
      span.textContent = (d.severity === "ERROR" ? "✗ " : "⚠ ")
          + "第" + d.line + "行 [" + d.code + "] " + d.message;
      box.appendChild(span);
    }
    if (!side.hasErrors && side.diagnostics.length > 0) {
      box.insertAdjacentHTML("afterbegin", '<span class="ok">✓ 可合并</span>');
    }
  });
}

function refText(ref) {
  return ref.name + "@" + ref.version + (ref.condition ? " [" + ref.condition + "]" : "");
}

function nodeText(node) {
  if (!node) return "(无)";
  const lines = [
    "package " + node.id,
    "  source " + node.source,
    "  integrity " + node.integrity,
  ];
  if (node.platform) lines.push("  platform " + node.platform);
  for (const child of node.children) {
    lines.push("  child " + refText(child));
  }
  return lines.join("\n");
}

function refsText(refs) {
  if (!refs || refs.length === 0) return "(无)";
  return refs.map(refText).join("\n");
}

function renderConflicts(view) {
  const box = $("conflicts");
  box.innerHTML = "";
  if (view.conflicts.length === 0) {
    box.innerHTML = '<p class="good">没有待决冲突。</p>';
  }
  for (const conflict of view.conflicts) {
    const div = document.createElement("div");
    div.className = "conflict" + (conflict.chosen ? " resolved" : "");
    const badge = '<span class="badge ' + conflict.type + '">' + conflict.type + "</span>";
    div.innerHTML = "<h4>" + escapeHtml(conflict.title) + badge
        + (conflict.chosen ? ' <span class="good">已选择 ' + conflict.chosen + "</span>" : "")
        + "</h4>"
        + '<div class="hint">' + escapeHtml(conflict.detail) + "</div>";

    const variants = [["base", "BASE"], ["left", "LEFT"], ["right", "RIGHT"]];
    for (const [key, label] of variants) {
      const payload = conflict[key];
      if (payload === null || payload === undefined) continue;
      const pre = document.createElement("pre");
      pre.textContent = (typeof payload === "string" ? payload :
          Array.isArray(payload) ? refsText(payload) : nodeText(payload))
          + "\n— " + label;
      div.appendChild(pre);
    }

    const btnRow = document.createElement("div");
    btnRow.className = "choice-btns";
    const options = conflict.type === "EDGE_DIVERGE"
        ? ["LEFT", "RIGHT", "BOTH", "BASE"]
        : conflict.type === "INTEGRITY"
            ? ["LEFT", "RIGHT"]
            : ["LEFT", "RIGHT", "BASE"];
    for (const option of options) {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.textContent = option;
      if (conflict.chosen === option) btn.classList.add("selected");
      btn.onclick = () => choose(conflict.id, option);
      btnRow.appendChild(btn);
    }
    if (conflict.chosen) {
      const undo = document.createElement("button");
      undo.type = "button";
      undo.textContent = "撤销裁决";
      undo.onclick = () => undoDecision(conflict.id);
      btnRow.appendChild(undo);
    }
    div.appendChild(btnRow);
    box.appendChild(div);
  }

  const summary = $("conflict-summary");
  summary.innerHTML = "";
  const parts = [];
  parts.push("待决冲突 <b>" + view.conflicts.filter(c => !c.chosen).length + "</b>");
  parts.push("已裁决 <b class='good'>" + view.conflicts.filter(c => c.chosen).length + "</b>");
  if (view.dangling.length > 0) {
    parts.push("<span class='bad'>悬空引用 " + view.dangling.length + "</span>");
    for (const d of view.dangling.slice(0, 5)) {
      parts.push("<div class='dangling-item'>悬空: " + refText(d) + "</div>");
    }
  }
  if (view.resolutionErrors.length > 0) {
    parts.push("<span class='bad'>裁决错误: "
        + view.resolutionErrors.map(escapeHtml).join("; ") + "</span>");
  }
  summary.innerHTML = parts.join("　");
}

async function choose(conflictId, choice) {
  const id = await ensureSession();
  try {
    const view = await api("POST", "/api/sessions/" + id + "/decisions", {
      revision: state.revision,
      conflictId,
      choice,
      fingerprintBase: state.fingerprints.base,
      fingerprintLeft: state.fingerprints.left,
      fingerprintRight: state.fingerprints.right,
    });
    loadState(view);
  } catch (err) {
    alert(err.message);
    if (err.conflict) await reloadCurrent();
  }
}

async function undoDecision(conflictId) {
  const id = await ensureSession();
  try {
    const view = await api("DELETE",
        "/api/sessions/" + id + "/decisions/" + encodeURIComponent(conflictId),
        { revision: state.revision });
    loadState(view);
  } catch (err) {
    alert(err.message);
    if (err.conflict) await reloadCurrent();
  }
}

function renderGraph(graph) {
  const box = $("graph");
  box.innerHTML = "";
  const nodes = graph.nodes;
  if (nodes.length === 0) {
    box.innerHTML = '<p class="hint">暂无可显示的节点（请先解析并合并）。</p>';
    return;
  }
  const byName = new Map();
  for (const node of nodes) {
    if (!byName.has(node.name)) byName.set(node.name, []);
    byName.get(node.name).push(node);
  }
  const nameOrder = [...byName.keys()].sort();
  const columnX = new Map();
  nameOrder.forEach((name, i) => columnX.set(name, i));

  const rows = [];
  const maxRows = Math.max(1, ...[...byName.values()].map(v => v.length));
  const cellW = 210;
  const rowH = 44;
  const margin = 30;
  const width = Math.max(nameOrder.length * cellW + margin * 2, 400);
  const height = maxRows * rowH + margin * 2 + 30;

  const position = new Map();
  for (const name of nameOrder) {
    const versions = byName.get(name).sort((a, b) => a.version.localeCompare(b.version));
    versions.forEach((node, row) => {
      const x = margin + columnX.get(name) * cellW;
      const y = margin + row * rowH;
      position.set(node.id, { x, y });
    });
  }

  const rootIds = new Set(graph.roots.map(r => r.name + "@" + r.version));
  const edgeDefs = [];
  for (const node of nodes) {
    for (const child of node.children) {
      edgeDefs.push([node.id, child.name + "@" + child.version, child.condition]);
    }
  }

  let svg = '<svg viewBox="0 0 ' + width + ' ' + height + '" xmlns="http://www.w3.org/2000/svg">'
      + '<defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" '
      + 'markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
      + '<path d="M 0 0 L 10 5 L 0 10 z" fill="#8a93a8"/></marker></defs>';

  const index = new Map(nodes.map(n => [n.id, n]));
  for (const [from, to, condition] of edgeDefs) {
    const a = position.get(from);
    const b = position.get(to);
    if (!a || !b) continue;
    const x1 = a.x + 150, y1 = a.y + 13;
    const x2 = b.x + 6, y2 = b.y + 13;
    const midX = (x1 + x2) / 2;
    svg += '<path class="edge-line" d="M' + x1 + ' ' + y1 + ' C' + midX + ' ' + y1 + ','
        + midX + ' ' + y2 + ',' + x2 + ' ' + y2 + '"/>';
    if (condition) {
      svg += '<text class="edge-label" x="' + (midX - 30) + '" y="' + ((y1 + y2) / 2 - 3)
          + '">' + escapeHtml(condition.length > 18 ? condition.slice(0, 17) + "…" : condition)
          + "</text>";
    }
  }
  for (const node of nodes) {
    const p = position.get(node.id);
    svg += '<g><rect class="node-rect' + (rootIds.has(node.id) ? " root" : "")
        + '" x="' + p.x + '" y="' + p.y + '" width="160" height="26" rx="6"/>';
    const label = node.id.length > 24 ? node.id.slice(0, 23) + "…" : node.id;
    svg += '<text class="node-text" x="' + (p.x + 8) + '" y="' + (p.y + 17) + '">'
        + escapeHtml(label) + "</text></g>";
  }
  svg += "</svg>";
  box.innerHTML = svg;
}

function renderIntegrity(items) {
  const box = $("integrity");
  if (!items || items.length === 0) {
    box.innerHTML = '<p class="hint">暂无数据。</p>';
    return;
  }
  let html = "<table><thead><tr><th>来源坐标</th><th>版本</th><th>完整性摘要</th>"
      + "<th>节点</th><th>状态</th></tr></thead><tbody>";
  for (const item of items) {
    html += "<tr><td>" + escapeHtml(item.source) + "</td><td>" + escapeHtml(item.version)
        + '</td><td style="font-family:monospace;font-size:11px">'
        + escapeHtml(item.integrity) + "</td><td>"
        + item.nodes.map(escapeHtml).join("<br>")
        + '</td><td class="' + (item.consistent ? "good" : "bad") + '">'
        + (item.consistent ? "一致" : "冲突") + "</td></tr>";
  }
  box.innerHTML = html + "</tbody></table>";
}

function renderCleanup(view) {
  const box = $("cleanup");
  const parts = [];
  if (view.removed.length === 0) {
    parts.push('<p class="good">没有需要清理的不可达节点。</p>');
  } else {
    parts.push("<ul class='clean'>");
    for (const removed of view.removed) {
      parts.push("<li><b>" + escapeHtml(removed.id) + "</b> — "
          + escapeHtml(removed.reason) + "</li>");
    }
    parts.push("</ul>");
  }
  if (view.staleDecisionIds && view.staleDecisionIds.length > 0) {
    parts.push('<p class="bad">以下旧裁决因输入指纹变化已失效、未自动应用：'
        + view.staleDecisionIds.map(escapeHtml).join(", ") + "</p>");
  }
  box.innerHTML = parts.join("");
}

function renderOutput(view) {
  const output = $("output");
  output.textContent = view.resolvedText
      ? view.resolvedText
      : "存在未决冲突、解析错误或悬空引用时暂不生成稳定输出。";
  $("publish").disabled = !view.canPublish;
  $("download").disabled = view.versions.length === 0;
  const status = $("publish-status");
  if (view.versions.length > 0) {
    const latest = view.versions[view.versions.length - 1];
    status.textContent = "已发布 v" + latest.number + "（" + latest.publishedAt + "），"
        + "共 " + view.versions.length + " 个版本";
  } else {
    status.textContent = view.canPublish ? "可以发布" : "尚不可发布";
  }
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (ch) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  }[ch]));
}

async function reloadCurrent() {
  if (!state.session) return;
  const view = await api("GET", "/api/sessions/" + state.session);
  loadState(view);
}

async function submitInputs() {
  const id = await ensureSession();
  try {
    const view = await api("PUT", "/api/sessions/" + id + "/inputs", {
      revision: state.revision,
      base: $("base").value,
      left: $("left").value,
      right: $("right").value,
    });
    loadState(view);
    $("save-hint").textContent = "已保存并重新合并 · " + new Date().toLocaleTimeString();
  } catch (err) {
    alert(err.message);
    if (err.conflict) await reloadCurrent();
  }
}

async function publish() {
  const id = await ensureSession();
  try {
    const view = await api("POST", "/api/sessions/" + id + "/publish", {
      revision: state.revision,
    });
    loadState(view);
  } catch (err) {
    alert(err.message);
    if (err.conflict) await reloadCurrent();
  }
}

function digest(seed) {
  let hash = 0xcbf29ce484222325n;
  for (const ch of seed) {
    hash ^= BigInt(ch.charCodeAt(0));
    hash = BigInt.asUintN(64, hash * 0x100000001b3n);
  }
  let hex = hash.toString(16);
  return "sha256:" + hex.padStart(16, "0").repeat(4);
}

const SAMPLE = () => {
  const d = (seed) => digest(seed);
  const base = `lockfile v1
root app@1.0.0

package app@1.0.0
  source registry:https://repo.example.com/app-1.0.0.tgz
  integrity ${d("app-1.0.0")}
  child libx@1.0.0
  child util@2.0.0 [os=linux | os=darwin]

package libx@1.0.0
  source registry:https://repo.example.com/libx-1.0.0.tgz
  integrity ${d("libx-1.0.0")}
  child util@2.0.0

package util@2.0.0
  source registry:https://repo.example.com/util-2.0.0.tgz
  integrity ${d("util-2.0.0")}
`;
  const left = `lockfile v1
root app@1.0.0

package app@1.0.0
  source registry:https://repo.example.com/app-1.0.0.tgz
  integrity ${d("app-1.0.0")}
  child libx@1.0.0
  child util@2.1.0 [os=darwin | os=linux]

package libx@1.0.0
  source registry:https://repo.example.com/libx-1.0.0.tgz
  integrity ${d("libx-1.0.0")}
  child util@2.1.0

package util@2.1.0
  source registry:https://repo.example.com/util-2.1.0.tgz
  integrity ${d("util-2.1.0-left")}
`;
  const right = `lockfile v1
root app@1.0.0

package app@1.0.0
  source registry:https://repo.example.com/app-1.0.0.tgz
  integrity ${d("app-1.0.0")}
  child libx@1.1.0
  child util@2.1.0 [os=linux|os=darwin]

package libx@1.1.0
  source registry:https://repo.example.com/libx-1.1.0.tgz
  integrity ${d("libx-1.1.0")}
  child util@2.1.0

package util@2.1.0
  source registry:https://repo.example.com/util-2.1.0.tgz
  integrity ${d("util-2.1.0-right")}
`;
  return { base, left, right };
};

function loadSample() {
  const sample = SAMPLE();
  $("base").value = sample.base;
  $("left").value = sample.left;
  $("right").value = sample.right;
}

window.addEventListener("DOMContentLoaded", async () => {
  $("new-session").onclick = async () => {
    const created = await api("POST", "/api/sessions");
    state.session = created.id;
    state.revision = null;
    await refreshSessionList("session-select");
    $("session-select").value = created.id;
    loadState(created);
  };
  $("session-select").onchange = async (event) => {
    state.session = event.target.value;
    await reloadCurrent();
  };
  $("load-sample").onclick = loadSample;
  $("merge").onclick = submitInputs;
  $("publish").onclick = publish;
  $("download").onclick = () => {
    if (state.session) {
      window.location.href = "/api/sessions/" + state.session + "/latest";
    }
  };

  await refreshSessionList("session-select");
  const sessions = await api("GET", "/api/sessions");
  if (sessions.sessions.length > 0) {
    state.session = sessions.sessions[sessions.sessions.length - 1].id;
    $("session-select").value = state.session;
    const view = await api("GET", "/api/sessions/" + state.session);
    loadState(view);
  } else {
    loadSample();
    await ensureSession();
  }
});
