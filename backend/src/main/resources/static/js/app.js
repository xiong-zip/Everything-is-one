/* ============================================================
   AgentFlow · Agent 执行引擎（前端）
   通过 SSE 接入后端编排引擎：意图分析 → 任务规划 → 逐步执行 → 结果汇总
   ============================================================ */

"use strict";

const $ = (sel) => document.querySelector(sel);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

const API_BASE = "/api/agent";

/* ---------- DOM 引用 ---------- */
const composer = $("#composer");
const runView = $("#runView");
const commandInput = $("#commandInput");
const exampleChips = $("#exampleChips");
const runBtn = $("#runBtn");
const resetBtn = $("#resetBtn");
const copyFinalBtn = $("#copyFinalBtn");
const commandBanner = $("#commandBanner");
const timeline = $("#timeline");
const finalPanel = $("#finalPanel");
const finalSummary = $("#finalSummary");
const finalOutput = $("#finalOutput");
const finalMeta = $("#finalMeta");
const consoleStatus = $("#consoleStatus");

let busy = false;
let completed = false;
let es = null;
let steps = [];

/* ---------- 工具函数 ---------- */
function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  }[c]));
}

function setStatus(text, cls) {
  consoleStatus.className = "console-status" + (cls ? " " + cls : "");
  consoleStatus.innerHTML = `● ${text}`;
}

function setPhase(name, state) {
  const el = document.querySelector(`.phase[data-phase="${name}"]`);
  if (!el) return;
  el.classList.toggle("active", state === "active");
  el.classList.toggle("done", state === "done");
}

async function typeText(el, text, chunk = 2) {
  if (reduceMotion) { el.textContent = text; return; }
  for (let i = 0; i < text.length; i += chunk) {
    el.textContent = text.slice(0, i + chunk);
    await sleep(14);
  }
  el.textContent = text;
}

/* ---------- 步骤元素渲染 ---------- */
function buildStepEl(index, kind, tag, title) {
  const li = document.createElement("li");
  li.className = "step dim";
  li.innerHTML = `
    <div class="step-rail">
      <span class="step-node">${kind === "tool" ? "⚙" : kind === "think" ? "🧠" : "✍"}</span>
      <span class="step-line"></span>
    </div>
    <div class="step-body">
      <div class="step-card">
        <div class="step-head">
          <span class="step-tag">${escapeHtml(tag)}</span>
          <span class="step-title">${escapeHtml(title)}</span>
          <span class="step-state"><span class="dot"></span><span class="state-text">等待中</span></span>
        </div>
        <div class="step-detail"></div>
      </div>
    </div>`;
  return li;
}

function setStepState(li, text) {
  li.querySelector(".state-text").textContent = text;
}

function renderToolCall(name, args) {
  const el = document.createElement("div");
  el.className = "tool-call";
  el.innerHTML = `<span class="tc-name">${escapeHtml(name)}</span><span class="tc-args">({ ${escapeHtml(args)} })</span>`;
  return el;
}

async function appendReasonLine(li, line) {
  const detail = li.querySelector(".step-detail");
  let box = detail.querySelector(".reasoning");
  if (!box) {
    box = document.createElement("div");
    box.className = "reasoning";
    detail.appendChild(box);
  }
  const l = document.createElement("div");
  l.className = "reason-line";
  l.innerHTML = `<span class="bullet">▸</span><span class="txt"></span>`;
  box.appendChild(l);
  await typeText(l.querySelector(".txt"), line);
}

function buildResult(resultType, result, list) {
  const wrap = document.createElement("div");
  wrap.className = "step-result";
  const card = document.createElement("div");
  card.className = "result-card";

  const label = document.createElement("div");
  label.className = "rc-label";
  label.textContent = "执行结果";
  card.appendChild(label);

  const r = result || {};

  if (resultType === "weather") {
    card.innerHTML += `
      <div class="weather">
        <div class="weather-main">
          <div>
            <div class="weather-temp">${r.tempLo}° / ${r.tempHi}°</div>
            <div class="weather-cond">${r.city} · ${r.condition}</div>
            <div class="weather-sub">${r.date} · 体感 ${r.feels}°</div>
          </div>
        </div>
        <div class="weather-detail">
          <span><span class="wd-key">风力</span>${r.wind}</span>
          <span><span class="wd-key">湿度</span>${r.humidity}</span>
          <span><span class="wd-key">紫外线</span>${r.uv}</span>
        </div>
      </div>`;
  } else if (resultType === "stock") {
    card.innerHTML += `
      <div class="stock-grid">
        <div class="stock-price ${r.trend === "down" ? "down" : "up"}">${r.price} <small>${r.change}</small></div>
        <div>
          <div class="stock-name">${r.name} <code style="font-size:11px;color:var(--text-3)">${r.symbol}</code></div>
          <div class="stock-sub">今开 ${r.open} · 成交额 ${r.amount} · 换手 ${r.turnover}</div>
        </div>
      </div>`;
  } else if (resultType === "transit") {
    card.innerHTML += `
      <div class="weather">
        <div class="weather-main">
          <div>
            <div class="weather-temp" style="font-size:1.5rem">${r.mode}</div>
            <div class="weather-cond">${r.duration}</div>
          </div>
        </div>
        <div class="weather-detail">
          <span><span class="wd-key">票价</span>${r.price}</span>
          <span><span class="wd-key">班次</span>${r.freq}</span>
        </div>
      </div>`;
  } else if (resultType === "list") {
    const box = document.createElement("div");
    box.className = "list-block";
    (list || []).forEach((item, i) => {
      const row = document.createElement("div");
      row.className = "list-row";
      row.innerHTML = `<span class="n">${String(i + 1).padStart(2, "0")}</span><span>${escapeHtml(item)}</span>`;
      box.appendChild(row);
    });
    card.appendChild(box);
  } else if (resultType === "copy") {
    const box = document.createElement("div");
    box.className = "copy-list";
    (r.versions || []).forEach((v) => {
      const item = document.createElement("div");
      item.className = "copy-item";
      item.innerHTML = `<span class="tag">${escapeHtml(v.tag)}</span>${escapeHtml(v.text).replace(/\n/g, "<br>")}`;
      box.appendChild(item);
    });
    card.appendChild(box);
  }

  wrap.appendChild(card);
  return wrap;
}

/* ---------- SSE 事件处理 ---------- */
function onStatus(d) { setStatus(d.text, d.cls); }
function onPhase(d) { setPhase(d.name, d.state); }

function onStep(d) {
  const li = buildStepEl(d.index, d.kind, d.tag, d.title);
  steps[d.index] = li;
  timeline.appendChild(li);
}

function onStepState(d) {
  const li = steps[d.index];
  if (!li) return;
  if (d.state === "running") {
    li.classList.remove("dim");
    li.classList.add("running");
    setStepState(li, "执行中");
  } else if (d.state === "done") {
    li.classList.remove("running");
    li.classList.add("done");
    setStepState(li, "完成");
  }
}

function onTool(d) {
  const li = steps[d.index];
  if (li) li.querySelector(".step-detail").appendChild(renderToolCall(d.name, d.args));
}

async function onReason(d) {
  const li = steps[d.index];
  if (li) await appendReasonLine(li, d.line);
}

function onResult(d) {
  const li = steps[d.index];
  if (li) li.querySelector(".step-detail").appendChild(buildResult(d.resultType, d.result, d.list));
}

function onDone(d) {
  completed = true;
  finalSummary.textContent = d.summary;
  finalOutput.textContent = d.output;
  finalMeta.innerHTML = (d.meta || []).map((m) => `<span>${escapeHtml(m)}</span>`).join("");
  finalPanel.hidden = false;
  setTimeout(() => finalPanel.scrollIntoView({ behavior: reduceMotion ? "auto" : "smooth", block: "center" }), 60);
}

function endBusy() {
  busy = false;
  runBtn.disabled = false;
  runBtn.classList.remove("running");
}

/* ---------- Agent 主流程（SSE 接入后端） ---------- */
function runAgent(command) {
  if (busy) return;
  busy = true;
  completed = false;
  runBtn.disabled = true;
  runBtn.classList.add("running");
  steps = [];

  composer.hidden = true;
  runView.hidden = false;
  timeline.innerHTML = "";
  finalPanel.hidden = true;
  commandBanner.textContent = command;
  document.querySelectorAll(".phase").forEach((p) => p.classList.remove("active", "done"));
  setStatus("正在连接后端…", "is-running");

  es = new EventSource(`${API_BASE}/run?command=${encodeURIComponent(command)}`);

  es.addEventListener("status", (e) => onStatus(JSON.parse(e.data)));
  es.addEventListener("phase", (e) => onPhase(JSON.parse(e.data)));
  es.addEventListener("plan", () => {});
  es.addEventListener("step", (e) => onStep(JSON.parse(e.data)));
  es.addEventListener("step-state", (e) => onStepState(JSON.parse(e.data)));
  es.addEventListener("tool", (e) => onTool(JSON.parse(e.data)));
  es.addEventListener("reason", (e) => onReason(JSON.parse(e.data)));
  es.addEventListener("result", (e) => onResult(JSON.parse(e.data)));
  es.addEventListener("done", (e) => onDone(JSON.parse(e.data)));

  es.onerror = () => {
    if (es) { es.close(); es = null; }
    endBusy();
    if (!completed) setStatus("连接中断，请重试", "is-running");
  };
}

/* ---------- 示例指令 ---------- */
async function renderChips() {
  let list = [];
  try {
    const res = await fetch(`${API_BASE}/scenarios`);
    if (!res.ok) throw new Error("backend unavailable");
    list = await res.json();
  } catch (e) {
    return;
  }
  exampleChips.innerHTML = "";
  list.forEach((s) => {
    const chip = document.createElement("button");
    chip.type = "button";
    chip.className = "chip" + (s.command === commandInput.value.trim() ? " active" : "");
    chip.textContent = s.short;
    chip.setAttribute("role", "listitem");
    chip.addEventListener("click", () => {
      commandInput.value = s.command;
      document.querySelectorAll(".chip").forEach((c) => c.classList.remove("active"));
      chip.classList.add("active");
      commandInput.focus();
    });
    exampleChips.appendChild(chip);
  });
}

/* ---------- 事件绑定 ---------- */
runBtn.addEventListener("click", () => {
  const command = commandInput.value.trim();
  if (!command || busy) return;
  runAgent(command);
});

resetBtn.addEventListener("click", () => {
  if (es) { es.close(); es = null; }
  runView.hidden = true;
  composer.hidden = false;
  timeline.innerHTML = "";
  finalPanel.hidden = true;
  steps = [];
  completed = false;
  document.querySelectorAll(".phase").forEach((p) => p.classList.remove("active", "done"));
  setStatus("待命", "");
  endBusy();
  commandInput.focus();
});

copyFinalBtn.addEventListener("click", async () => {
  const text = finalOutput.textContent;
  const label = copyFinalBtn.querySelector(".copy-label");
  const ok = copyFinalBtn.querySelector(".copy-ok");
  try {
    await navigator.clipboard.writeText(text);
  } catch (e) {
    const ta = document.createElement("textarea");
    ta.value = text;
    document.body.appendChild(ta);
    ta.select();
    document.execCommand("copy");
    ta.remove();
  }
  label.hidden = true;
  ok.hidden = false;
  setTimeout(() => { label.hidden = false; ok.hidden = true; }, 1600);
});

/* 回车执行（Shift+Enter 换行） */
commandInput.addEventListener("keydown", (e) => {
  if (e.key === "Enter" && !e.shiftKey && !e.isComposing) {
    e.preventDefault();
    if (!busy && commandInput.value.trim()) runAgent(commandInput.value.trim());
  }
});

/* ---------- 滚动显现 ---------- */
const io = new IntersectionObserver(
  (entries) => entries.forEach((en) => en.isIntersecting && en.target.classList.add("in")),
  { threshold: 0.12 }
);
document.querySelectorAll(".reveal").forEach((el) => io.observe(el));

/* ---------- LLM 接入状态 ---------- */
async function renderInfo() {
  const badge = $("#llmBadge");
  try {
    const res = await fetch(`${API_BASE}/info`);
    if (!res.ok) throw new Error("backend unavailable");
    const info = await res.json();
    if (info.llmEnabled) {
      badge.textContent = `● ${info.model} 已接入`;
      badge.classList.add("on");
    } else {
      badge.textContent = "● 模拟模式 · 未配置 API Key";
      badge.classList.add("off");
    }
  } catch (e) {
    badge.textContent = "● 后端不可用";
  }
}

/* ---------- 初始化 ---------- */
renderInfo();
renderChips();
setStatus("待命", "");
