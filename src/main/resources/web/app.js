"use strict";

const state = {
  revision: -1,
  data: null,
  selectedNode: null
};

const $ = (id) => document.getElementById(id);

function toast(message, kind) {
  const el = $("toast");
  el.textContent = message;
  el.className = "toast " + (kind || "");
  clearTimeout(toast._timer);
  toast._timer = setTimeout(() => el.classList.add("hidden"), 3200);
}

async function postJson(url, body) {
  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  const json = await response.json();
  if (!response.ok) {
    const err = new Error(json.message || ("HTTP " + response.status));
    err.code = json.error;
    err.payload = json;
    throw err;
  }
  return json;
}

async function saveInputs() {
  try {
    const data = await postJson("/api/inputs", {
      revision: state.revision,
      base: $("base").value,
      left: $("left").value,
      right: $("right").value
    });
    applyState(data);
    toast(data.hasOutput ? "合并成功，输出已生成" : "已重新解析与合并", data.hasOutput ? "good" : "");
  } catch (err) {
    if (err.code === "CONCURRENT_EDIT") {
      toast("并发编辑冲突：已刷新到最新会话，请重新提交", "bad");
      await refresh();
    } else {
      toast(err.message, "bad");
    }
  }
}

async function decide(conflictId, optionId) {
  try {
    const data = await postJson("/api/decide", {
      revision: state.revision, conflictId, optionId
    });
    applyState(data);
    toast("已记录裁决并重新验证父引用与根可达性", "good");
  } catch (err) {
    if (err.code === "CONCURRENT_EDIT") {
      toast("并发编辑冲突：裁决基于旧修订，已刷新", "bad");
      await refresh();
    } else if (err.code === "CONFLICT_GONE") {
      toast("该冲突已不存在（裁决已随输入变化失效）", "bad");
      await refresh();
    } else {
      toast(err.message, "bad");
    }
  }
}

async function resetDecisions() {
  try {
    const data = await postJson("/api/reset", { revision: state.revision });
    applyState(data);
    toast("已清除全部裁决");
  } catch (err) {
    toast(err.message, "bad");
    await refresh();
  }
}

async function refresh() {
  const response = await fetch("/api/state");
  const data = await response.json();
  applyState(data);
}

function applyState(data) {
  state.data = data;
  state.revision = data.revision;
  if (!$("base").dataset.touched) {
    $("base").value = data.baseRaw || "";
    $("left").value = data.leftRaw || "";
    $("right").value = data.rightRaw || "";
  }
  renderStatus(data);
  renderFingerprints(data);
  renderConflicts(data);
  renderIssues(data);
  renderPruned(data);
  renderChanges(data);
  renderDiagnostics(data);
  renderOutput(data);
  renderGraph(data);
}

function renderStatus(data) {
  const pill = $("status-pill");
  if (data.blocked) {
    const pending = data.pendingCount > 0;
    const parseErrors = data.diagnostics.some((d) => d.severity === "ERROR");
    pill.textContent = pending ? "存在待决冲突"
      : parseErrors ? "解析失败"
      : "检查未通过";
    pill.className = "pill bad";
  } else {
    pill.textContent = "合并闭合";
    pill.className = "pill ok";
  }
  $("rev-pill").textContent = "revision " + data.revision;
  $("out-pill").textContent = "输出版本 " + data.outputVersion
    + (data.outputUpdatedAt ? " · " + data.outputUpdatedAt.replace("T", " ").slice(0, 19) : "");
}

function renderFingerprints(data) {
  for (const side of ["base", "left", "right"]) {
    const el = document.querySelector(`[data-fp="${side}"]`);
    const fp = data.fingerprints[side] || "";
    el.textContent = fp.slice(0, 19);
    el.title = fp;
  }
}

function renderConflicts(data) {
  const root = $("conflicts");
  root.innerHTML = "";
  if (data.conflicts.length === 0) {
    root.innerHTML = '<div class="empty">没有冲突（自动合并或尚未解析输入）。</div>';
    return;
  }
  for (const conflict of data.conflicts) {
    const card = document.createElement("div");
    card.className = "conflict-card"
      + (conflict.resolved ? " resolved" : "")
      + (conflict.staleDecision ? " stale" : "");

    const title = document.createElement("div");
    title.className = "conflict-title";
    title.textContent = conflict.summary;
    card.appendChild(title);

    const kind = document.createElement("span");
    kind.className = "kind-tag";
    kind.textContent = conflict.kind;
    title.prepend(kind);

    const details = document.createElement("div");
    details.className = "conflict-details";
    for (const line of conflict.details) {
      const d = document.createElement("div");
      d.textContent = "• " + line;
      details.appendChild(d);
    }
    card.appendChild(details);

    if (conflict.resolved) {
      const note = document.createElement("div");
      note.className = "resolution-note";
      const opt = conflict.options.find((o) => o.id === conflict.resolution);
      note.textContent = "✓ 已选择：" + (opt ? opt.label : conflict.resolution)
        + (conflict.resolvedBy === "decision" ? "（人工裁决 " + shortId(conflict.decisionId) + "）" : "");
      card.appendChild(note);
    } else {
      const row = document.createElement("div");
      row.className = "option-row";
      for (const option of conflict.options) {
        const btn = document.createElement("button");
        btn.type = "button";
        if (option.recommended) btn.className = "primary";
        btn.textContent = option.label;
        btn.addEventListener("click", () => decide(conflict.id, option.id));
        row.appendChild(btn);
      }
      card.appendChild(row);
      if (conflict.staleDecision) {
        const stale = document.createElement("div");
        stale.className = "stale-note";
        stale.textContent = "旧裁决（" + conflict.staleDecisionOption
          + "）绑定的三份输入指纹已变化，不会自动套用；请重新裁决。";
        card.appendChild(stale);
      }
    }
    root.appendChild(card);
  }
}

function shortId(id) {
  return id ? id.slice(0, 8) : "";
}

function renderIssues(data) {
  const root = $("issues");
  root.innerHTML = "";
  if (data.issues.length === 0 && data.blockReasons.filter((r) => !r.startsWith("there are unresolved") && !r.includes("parse error")).length === 0
      && data.diagnostics.filter((d) => d.severity === "ERROR").length === 0) {
    root.innerHTML = '<li class="empty">未发现悬空引用或完整性冲突。</li>';
    return;
  }
  for (const issue of data.issues) {
    const li = document.createElement("li");
    li.className = "issue-bad";
    li.innerHTML = "";
    const tag = document.createElement("span");
    tag.className = "kind-tag";
    tag.textContent = issue.kind;
    li.appendChild(tag);
    li.appendChild(document.createTextNode(issue.message));
    root.appendChild(li);
  }
}

function renderPruned(data) {
  const root = $("pruned");
  root.innerHTML = "";
  if (data.pruned.length === 0) {
    root.innerHTML = '<li class="empty">没有节点被可达性清理。</li>';
    return;
  }
  for (const node of data.pruned) {
    const li = document.createElement("li");
    const tag = document.createElement("span");
    tag.className = "kind-tag";
    tag.textContent = node.orphan ? "无根可达" : "未纳入";
    li.appendChild(tag);
    li.appendChild(document.createTextNode(node.nodeRef));
    const sub = document.createElement("div");
    sub.style.color = "var(--muted)";
    sub.style.fontSize = "12px";
    sub.textContent = node.reasons.join("；");
    li.appendChild(sub);
    root.appendChild(li);
  }
}

function renderChanges(data) {
  const root = $("changes");
  root.innerHTML = "";
  if (data.changes.length === 0) {
    root.innerHTML = '<li class="empty">无自动改动。</li>';
    return;
  }
  for (const change of data.changes) {
    const li = document.createElement("li");
    const tag = document.createElement("span");
    tag.className = "kind-tag";
    tag.textContent = change.side;
    li.appendChild(tag);
    li.appendChild(document.createTextNode(change.kind + " · " + change.target
      + (change.details.length ? " — " + change.details.join("；") : "")));
    root.appendChild(li);
  }
}

function renderDiagnostics(data) {
  const root = $("diagnostics");
  root.innerHTML = "";
  if (data.diagnostics.length === 0) {
    root.innerHTML = '<li class="empty">三份输入均无诊断。</li>';
    return;
  }
  for (const d of data.diagnostics) {
    const li = document.createElement("li");
    li.className = d.severity === "ERROR" ? "diag-error" : "diag-warning";
    li.textContent = `[${d.side}] ${d.severity} 行${d.line} ${d.code}: ${d.message}`;
    root.appendChild(li);
  }
}

function renderOutput(data) {
  $("output").value = data.outputText || "";
  const btn = $("btn-download");
  btn.disabled = !data.hasOutput;
  const reasons = $("block-reasons");
  reasons.innerHTML = "";
  if (data.blocked) {
    for (const reason of data.blockReasons) {
      const div = document.createElement("div");
      div.textContent = "⛔ " + reason;
      reasons.appendChild(div);
    }
  }
}

function renderGraph(data) {
  const svg = $("graph");
  svg.innerHTML = "";
  const nodes = data.graph.nodes;
  const edges = data.graph.edges;
  if (nodes.length === 0) {
    svg.innerHTML = '<text x="20" y="30" fill="#93a0b8">提交三份 lockfile 后这里显示包图</text>';
    svg.setAttribute("width", 800);
    svg.setAttribute("height", 320);
    return;
  }

  const reachable = nodes.filter((n) => n.reachable);
  const pruned = nodes.filter((n) => !n.reachable);
  const rootRefs = new Set(data.roots.map((r) => r.ref));

  // depth layering from roots
  const byRef = new Map(nodes.map((n) => [n.ref, n]));
  const childrenOf = new Map();
  for (const edge of edges) {
    if (!childrenOf.has(edge.from)) childrenOf.set(edge.from, []);
    childrenOf.get(edge.from).push(edge.to);
  }
  const depth = new Map();
  for (const root of data.roots) {
    walkDepth(root.ref, 0, new Set());
  }
  function walkDepth(ref, level, seen) {
    if (seen.has(ref)) return;
    seen.add(ref);
    const prev = depth.get(ref);
    if (prev === undefined || level < prev) depth.set(ref, level);
    for (const child of childrenOf.get(ref) || []) {
      walkDepth(child, level + 1, seen);
    }
  }

  const layers = new Map();
  for (const node of reachable) {
    const level = depth.has(node.ref) ? depth.get(node.ref) : 0;
    if (!layers.has(level)) layers.set(level, []);
    layers.get(level).push(node);
  }
  if (pruned.length) {
    layers.set(99, pruned);
  }
  const sortedLevels = [...layers.keys()].sort((a, b) => a - b);

  const colW = 210;
  const rowH = 64;
  const padX = 70;
  const padY = 40;
  const maxRows = Math.max(...[...layers.values()].map((l) => l.length));
  const width = Math.max(640, padX * 2 + sortedLevels.length * colW);
  const height = padY * 2 + maxRows * rowH + 40;
  svg.setAttribute("width", width);
  svg.setAttribute("height", height);

  const positions = new Map();
  for (const level of sortedLevels) {
    const list = layers.get(level);
    list.sort((a, b) => a.ref.localeCompare(b.ref));
    list.forEach((node, i) => {
      positions.set(node.ref, {
        x: padX + sortedLevels.indexOf(level) * colW,
        y: padY + i * rowH
      });
    });
  }

  const ns = "http://www.w3.org/2000/svg";

  // arrow marker
  const defs = document.createElementNS(ns, "defs");
  defs.innerHTML =
    '<marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
    + '<path d="M 0 0 L 10 5 L 0 10 z" fill="#5f7096"/></marker>';
  svg.appendChild(defs);

  // edges first
  for (const edge of edges) {
    const from = positions.get(edge.from);
    const to = positions.get(edge.to);
    if (!from || !to) continue;
    const x1 = from.x + 150;
    const y1 = from.y + 19;
    const x2 = to.x;
    const y2 = to.y + 19;
    const midX = (x1 + x2) / 2;
    const path = document.createElementNS(ns, "path");
    path.setAttribute("d", `M ${x1} ${y1} C ${midX} ${y1}, ${midX} ${y2}, ${x2} ${y2}`);
    path.setAttribute("fill", "none");
    path.setAttribute("stroke", edge.targetReachable ? "#5f7096" : "#ef6f6f");
    path.setAttribute("stroke-width", "1.5");
    path.setAttribute("marker-end", "url(#arrow)");
    if (edge.platforms) {
      path.setAttribute("stroke-dasharray", "5 4");
    }
    svg.appendChild(path);
    if (edge.platforms) {
      const label = document.createElementNS(ns, "text");
      label.setAttribute("x", midX);
      label.setAttribute("y", (y1 + y2) / 2 - 4);
      label.setAttribute("fill", "#93a0b8");
      label.setAttribute("font-size", "9");
      label.setAttribute("text-anchor", "middle");
      label.textContent = edge.platforms.length > 18
        ? edge.platforms.slice(0, 17) + "…" : edge.platforms;
      svg.appendChild(label);
    }
  }

  // nodes
  for (const node of nodes) {
    const pos = positions.get(node.ref);
    if (!pos) continue;
    const group = document.createElementNS(ns, "g");
    group.setAttribute("transform", `translate(${pos.x},${pos.y})`);
    group.style.cursor = "pointer";

    const rect = document.createElementNS(ns, "rect");
    rect.setAttribute("width", 168);
    rect.setAttribute("height", 38);
    rect.setAttribute("rx", 8);
    let fill = "#1b2740";
    let stroke = "#3a4a6e";
    if (!node.reachable) {
      fill = "#2a2130"; stroke = "#8a6a3a";
    }
    if (rootRefs.has(node.ref)) {
      stroke = "#b98bff";
    }
    if (node.requires.some((r) =>
        !nodes.find((n) => n.ref === r.target)?.reachable) && node.reachable) {
      // edge to missing child gets red edge; node itself stays
    }
    rect.setAttribute("fill", fill);
    rect.setAttribute("stroke", stroke);
    rect.setAttribute("stroke-width", "1.5");
    group.appendChild(rect);

    const nameText = document.createElementNS(ns, "text");
    nameText.setAttribute("x", 10);
    nameText.setAttribute("y", 16);
    nameText.setAttribute("fill", "#e6ebf5");
    nameText.setAttribute("font-size", "11.5");
    nameText.setAttribute("font-weight", "600");
    nameText.textContent = node.name + "@" + node.version
      + (node.ref.length > 26 ? "" : "");
    group.appendChild(nameText);

    const subText = document.createElementNS(ns, "text");
    subText.setAttribute("x", 10);
    subText.setAttribute("y", 31);
    subText.setAttribute("fill", "#93a0b8");
    subText.setAttribute("font-size", "9");
    const sub = node.source.length > 24 ? node.source.slice(0, 23) + "…" : node.source;
    subText.textContent = sub + (node.integrity ? "  ✓" : "  ·");
    group.appendChild(subText);

    group.addEventListener("click", () => showNodeCard(node, data));
    svg.appendChild(group);
  }
}

function showNodeCard(node, data) {
  const card = $("node-card");
  card.classList.remove("hidden");
  const incoming = data.graph.edges.filter((e) => e.to === node.ref);
  const rows = [
    ["名称", node.name],
    ["版本", node.version],
    ["来源", node.source || "-"],
    ["完整性", node.integrity || "(未声明)"],
    ["平台条件", node.platforms || "true（无条件）"],
    ["出现于", node.presentIn.join(", ")],
    ["来源裁决", node.origins.join(", ")],
    ["可达性", node.reachable ? "根可达，已纳入输出" : "已清理"],
    ["入边", incoming.map((e) => e.from + (e.platforms ? " [" + e.platforms + "]" : "")).join("；") || "（无）"],
    ["子节点", node.requires.map((r) => r.target + (r.platforms ? " [" + r.platforms + "]" : "")).join("；") || "（无）"]
  ];
  card.innerHTML = "<h4>" + node.name + "@" + node.version + "</h4>";
  const kv = document.createElement("div");
  kv.className = "kv";
  card.appendChild(kv);
  for (const [k, v] of rows) {
    const dk = document.createElement("div");
    dk.className = "k";
    dk.textContent = k;
    const dv = document.createElement("div");
    dv.textContent = v;
    kv.appendChild(dk);
    kv.appendChild(dv);
  }
  if (node.pruneReasons && node.pruneReasons.length) {
    const h = document.createElement("div");
    h.style.marginTop = "8px";
    h.style.color = "var(--warn)";
    h.textContent = "清理原因：" + node.pruneReasons.join("；");
    card.appendChild(h);
  }
  const close = document.createElement("button");
  close.type = "button";
  close.textContent = "关闭";
  close.style.marginTop = "10px";
  close.addEventListener("click", () => card.classList.add("hidden"));
  card.appendChild(close);
}

const SAMPLE = {
  // base: two roots (web, tool); lib@1.0.0 shared; cache aliased by registry-mirror
  base: [
    "lockfile v1",
    "root web@1.0.0",
    "root tool@3.0.0",
    "",
    "package web@1.0.0",
    "    source registry:acme/web",
    "    integrity sha256:aaaa1111",
    "    requires:",
    "        lib@1.0.0",
    "        cache@1.0.0",
    "    end",
    "",
    "package lib@1.0.0",
    "    source registry:acme/lib",
    "    integrity sha256:bbbb1111",
    "    end",
    "",
    "package cache@1.0.0",
    "    source registry:mirror/cache",
    "    integrity sha256:c0ffee01",
    "    end",
    "",
    "package tool@3.0.0",
    "    source git:https://example.com/tool#v3.0.0",
    "    integrity sha256:dddd1111",
    "    requires:",
    "        util@1.0.0",
    "    end",
    "",
    "package util@1.0.0",
    "    source registry:acme/util",
    "    integrity sha256:eeee1111"
  ].join("\n"),
  // left: upgrades web's child to lib@1.2.0 (recommended resolution); only
  // upgrades the transitive util used by tool; same cache digest
  left: [
    "lockfile v1",
    "root web@1.0.0",
    "root tool@3.0.0",
    "",
    "package web@1.0.0",
    "    source registry:acme/web",
    "    integrity sha256:aaaa1111",
    "    requires:",
    "        lib@1.2.0",
    "        cache@1.0.0",
    "    end",
    "",
    "package lib@1.0.0",
    "    source registry:acme/lib",
    "    integrity sha256:bbbb1111",
    "    end",
    "",
    "package lib@1.2.0",
    "    source registry:acme/lib",
    "    integrity sha256:bbbb2222",
    "    end",
    "",
    "package cache@1.0.0",
    "    source registry:mirror/cache",
    "    integrity sha256:c0ffee01",
    "    end",
    "",
    "package tool@3.0.0",
    "    source git:https://example.com/tool#v3.0.0",
    "    integrity sha256:dddd1111",
    "    requires:",
    "        util@1.2.0",
    "    end",
    "",
    "package util@1.0.0",
    "    source registry:acme/util",
    "    integrity sha256:eeee1111",
    "    end",
    "",
    "package util@1.2.0",
    "    source registry:acme/util",
    "    integrity sha256:eeee2222"
  ].join("\n"),
  // right: deletes root 'tool' entirely (orphan text nodes remain), upgrades
  // web on a different line (lib@1.3.0), and ships a SECOND alias of the same
  // (source,version) with a conflicting digest -> integrity mismatch
  right: [
    "lockfile v1",
    "root web@1.0.0",
    "",
    "package web@1.0.0",
    "    source registry:acme/web",
    "    integrity sha256:aaaa1111",
    "    requires:",
    "        lib@1.3.0",
    "        cache@1.0.0",
    "    end",
    "",
    "package lib@1.0.0",
    "    source registry:acme/lib",
    "    integrity sha256:bbbb1111",
    "    end",
    "",
    "package lib@1.3.0",
    "    source registry:acme/lib",
    "    integrity sha256:bbbb3333",
    "    end",
    "",
    "package cache@1.0.0",
    "    source registry:mirror/cache",
    "    integrity sha256:c0ffee01",
    "    end",
    "",
    "package tool@3.0.0",
    "    source git:https://example.com/tool#v3.0.0",
    "    integrity sha256:dddd1111",
    "    requires:",
    "        util@1.0.0",
    "    end",
    "",
    "package util@1.0.0",
    "    source registry:acme/util",
    "    integrity sha256:eeee1111"
  ].join("\n")
};

function loadSample() {
  $("base").value = SAMPLE.base;
  $("left").value = SAMPLE.left;
  $("right").value = SAMPLE.right;
  $("base").dataset.touched = "1";
  saveInputs();
}

function clearAll() {
  $("base").value = "";
  $("left").value = "";
  $("right").value = "";
  $("base").dataset.touched = "1";
  saveInputs();
}

function downloadOutput() {
  window.location.href = "/api/download";
}

function bind() {
  $("btn-save").addEventListener("click", () => {
    $("base").dataset.touched = "1";
    saveInputs();
  });
  $("btn-sample").addEventListener("click", loadSample);
  $("btn-clear").addEventListener("click", clearAll);
  $("btn-reset").addEventListener("click", resetDecisions);
  $("btn-download").addEventListener("click", downloadOutput);
  for (const id of ["base", "left", "right"]) {
    $(id).addEventListener("input", () => { $(id).dataset.touched = "1"; });
  }
}

bind();
refresh().catch((err) => toast("加载会话失败：" + err.message, "bad"));
