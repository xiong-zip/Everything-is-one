/* ============================================================
   AgentFlow · Agent 执行引擎（前端演示）
   编排流程：意图分析 → 任务规划 → 逐步执行 → 结果汇总
   ============================================================ */

"use strict";

const $ = (sel) => document.querySelector(sel);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

/* ---------- 演示场景数据 ---------- */
const SCENARIOS = [
  {
    id: "weather",
    command: "帮我查厦门今天天气，然后生成一段朋友圈文案",
    short: "天气 + 朋友圈文案",
    steps: [
      {
        kind: "tool",
        tag: "工具调用",
        title: "查询厦门今日天气",
        tool: { name: "weather.query", args: 'city: "厦门", date: "今天"' },
        resultType: "weather",
        result: {
          city: "厦门", date: "今天", condition: "晴",
          tempLo: 27, tempHi: 33, wind: "东南风 3 级",
          humidity: "72%", uv: "强", feels: 36,
        },
        dur: 900,
      },
      {
        kind: "think",
        tag: "智能分析",
        title: "提炼天气要点 · 确定文案基调",
        lines: [
          "关键信息：晴天、27~33℃、紫外线强、湿度较高",
          "用户场景：发朋友圈，需要轻松、生活化的口吻",
          "决定基调：慵懒夏日感 + 实用防晒提醒",
          "结构：一句氛围开场 + 天气细节 + 暖心提醒",
        ],
        dur: 1300,
      },
      {
        kind: "write",
        tag: "内容生成",
        title: "生成朋友圈文案（3 版备选）",
        tool: { name: "llm.generate", args: 'prompt: "厦门天气文案", style: "生活化", count: 3' },
        resultType: "copy",
        result: {
          versions: [
            { tag: "V1 · 清爽防晒", text: "厦门的夏天把太阳开到最大档了 ☀️\n今天 27~33℃，晴到发亮，出门记得涂防晒！\n不过天这么蓝，晒一点也值得~" },
            { tag: "V2 · 慵懒氛围", text: "夏日的厦门，连风都是热的。\n27~33℃ 的晴天，适合躲进空调房，也适合大胆出门。\n反正夏天嘛，开心最重要。" },
            { tag: "V3 · 极简", text: "厦门 · 晴 33℃\n阳光正好，适合出发。\n记得防晒，今天的紫外线有点强 🌤" },
          ],
        },
        dur: 1700,
      },
    ],
    final: {
      summary: "已完成 3 个子任务：天气查询 → 要点分析 → 文案生成。今日厦门晴热、紫外线强，推荐使用「清爽防晒」版文案。",
      output: "厦门的夏天把太阳开到最大档了 ☀️\n今天 27~33℃，晴到发亮，出门记得涂防晒！\n不过天这么蓝，晒一点也值得~",
      meta: ["调用工具 2 个", "推理 1 轮", "全程无需人工介入"],
    },
  },
  {
    id: "stock",
    command: "查一下宁德时代今天的股价，然后写一段给领导的周报总结",
    short: "股价 + 周报总结",
    steps: [
      {
        kind: "tool",
        tag: "工具调用",
        title: "查询宁德时代今日行情",
        tool: { name: "stock.query", args: 'symbol: "300750", date: "今天"' },
        resultType: "stock",
        result: { name: "宁德时代", symbol: "300750", price: 189.62, change: "+1.23%", trend: "up", open: 187.2, amount: "42.8 亿", turnover: "1.15%" },
        dur: 900,
      },
      {
        kind: "think",
        tag: "智能分析",
        title: "解读行情 · 提炼汇报要点",
        lines: [
          "今日上涨 1.23%，成交额 42.8 亿，量价配合健康",
          "短期受动力电池订单预期提振",
          "周报侧重：结论 → 数据佐证 → 后续关注",
        ],
        dur: 1300,
      },
      {
        kind: "write",
        tag: "内容生成",
        title: "生成周报汇报文案",
        tool: { name: "llm.generate", args: 'prompt: "宁德时代周报", audience: "领导", tone: "严谨专业"' },
        resultType: "copy",
        result: {
          versions: [
            { tag: "周报 · 汇报版", text: "本周重点关注新能源板块龙头宁德时代：今日收盘 189.62 元，上涨 1.23%，成交额 42.8 亿，走势稳健。短期受动力电池订单预期提振，中期逻辑不变，建议保持跟踪、逢低关注。" },
          ],
        },
        dur: 1700,
      },
    ],
    final: {
      summary: "已完成 3 个子任务：行情查询 → 要点分析 → 周报生成。今日宁德时代收涨 1.23%，文案可直接用于周报汇报。",
      output: "本周重点关注新能源板块龙头宁德时代：今日收盘 189.62 元，上涨 1.23%，成交额 42.8 亿，走势稳健。短期受动力电池订单预期提振，中期逻辑不变，建议保持跟踪、逢低关注。",
      meta: ["调用工具 2 个", "推理 1 轮", "风格：严谨专业"],
    },
  },
  {
    id: "trip",
    command: "帮我规划周末两天从上海去杭州的行程，顺便推荐当地美食",
    short: "周末行程 + 美食",
    steps: [
      {
        kind: "tool",
        tag: "工具调用",
        title: "查询上海 → 杭州交通",
        tool: { name: "transit.query", args: 'from: "上海", to: "杭州", date: "周末"' },
        resultType: "transit",
        result: { mode: "高铁", duration: "约 1 小时 05 分", price: "二等座 ¥73 起", freq: "首班 06:30 · 末班 21:40" },
        dur: 900,
      },
      {
        kind: "tool",
        tag: "工具调用",
        title: "推荐杭州本地美食 Top 5",
        tool: { name: "poi.recommend", args: 'city: "杭州", type: "美食", top: 5' },
        resultType: "list",
        list: ["西湖醋鱼 · 楼外楼", "龙井虾仁 · 绿茶餐厅", "片儿川 · 菊英面店", "葱包烩 · 河坊街", "定胜糕 · 江南春"],
        dur: 1100,
      },
      {
        kind: "think",
        tag: "智能分析",
        title: "规划两天行程动线",
        lines: [
          "Day1 环湖漫游：住湖滨 → 骑行 → 夜市",
          "Day2 灵隐祈福：灵隐寺 → 龙井茶园",
          "美食穿插进动线，减少折返",
        ],
        dur: 1300,
      },
      {
        kind: "write",
        tag: "内容生成",
        title: "生成完整行程单",
        tool: { name: "llm.generate", args: 'prompt: "杭州两日行程", style: "行程单"' },
        resultType: "copy",
        result: {
          versions: [
            { tag: "行程单", text: "Day1 西湖漫游线\n09:00 高铁 上海 → 杭州东\n10:30 入住湖滨酒店 · 放行李\n12:00 午餐「楼外楼」西湖醋鱼\n14:00 环湖骑行（苏堤—白堤）\n17:00 断桥残雪看落日\n19:30 河坊街夜逛 · 葱包烩 / 定胜糕\n\nDay2 灵隐祈福线\n08:00 早餐「菊英面店」片儿川\n09:00 灵隐寺 + 飞来峰\n12:30 龙井村 · 龙井虾仁午餐\n14:00 茶园步道漫步\n16:00 返程高铁 回上海" },
          ],
        },
        dur: 1900,
      },
    ],
    final: {
      summary: "已完成 4 个子任务：交通查询 → 美食推荐 → 动线规划 → 行程生成。两天行程兼顾景点、美食与返程。",
      output: "Day1 西湖漫游线\n09:00 高铁 上海 → 杭州东\n10:30 入住湖滨酒店 · 放行李\n12:00 午餐「楼外楼」西湖醋鱼\n14:00 环湖骑行（苏堤—白堤）\n17:00 断桥残雪看落日\n19:30 河坊街夜逛 · 葱包烩 / 定胜糕\n\nDay2 灵隐祈福线\n08:00 早餐「菊英面店」片儿川\n09:00 灵隐寺 + 飞来峰\n12:30 龙井村 · 龙井虾仁午餐\n14:00 茶园步道漫步\n16:00 返程高铁 回上海",
      meta: ["调用工具 3 个", "推理 1 轮", "覆盖 5 种杭州美食"],
    },
  },
];

const MATCHERS = [
  { id: "weather", keys: ["天气", "朋友圈"] },
  { id: "stock", keys: ["股价", "股票", "周报"] },
  { id: "trip", keys: ["行程", "杭州", "美食", "旅游"] },
];

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
function buildStepEl(step) {
  const li = document.createElement("li");
  li.className = "step dim";
  li.innerHTML = `
    <div class="step-rail">
      <span class="step-node">${step.kind === "tool" ? "⚙" : step.kind === "think" ? "🧠" : "✍"}</span>
      <span class="step-line"></span>
    </div>
    <div class="step-body">
      <div class="step-card">
        <div class="step-head">
          <span class="step-tag">${step.tag}</span>
          <span class="step-title">${escapeHtml(step.title)}</span>
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

function renderToolCall(tool) {
  const el = document.createElement("div");
  el.className = "tool-call";
  el.innerHTML = `<span class="tc-name">${escapeHtml(tool.name)}</span><span class="tc-args">({ ${escapeHtml(tool.args)} })</span>`;
  return el;
}

async function streamReasoning(container, lines) {
  const box = document.createElement("div");
  box.className = "reasoning";
  container.appendChild(box);
  for (let i = 0; i < lines.length; i++) {
    const line = document.createElement("div");
    line.className = "reason-line";
    line.innerHTML = `<span class="bullet">▸</span><span class="txt"></span>`;
    box.appendChild(line);
    await typeText(line.querySelector(".txt"), lines[i]);
    await sleep(110);
  }
}

function buildResult(step) {
  const wrap = document.createElement("div");
  wrap.className = "step-result";
  const card = document.createElement("div");
  card.className = "result-card";

  const label = document.createElement("div");
  label.className = "rc-label";
  label.textContent = "执行结果";
  card.appendChild(label);

  const r = step.result;

  if (step.resultType === "weather") {
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
  } else if (step.resultType === "stock") {
    card.innerHTML += `
      <div class="stock-grid">
        <div class="stock-price ${r.trend === "down" ? "down" : "up"}">${r.price} <small>${r.change}</small></div>
        <div>
          <div class="stock-name">${r.name} <code style="font-size:11px;color:var(--text-3)">${r.symbol}</code></div>
          <div class="stock-sub">今开 ${r.open} · 成交额 ${r.amount} · 换手 ${r.turnover}</div>
        </div>
      </div>`;
  } else if (step.resultType === "transit") {
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
  } else if (step.resultType === "list") {
    const box = document.createElement("div");
    box.className = "list-block";
    step.list.forEach((item, i) => {
      const row = document.createElement("div");
      row.className = "list-row";
      row.innerHTML = `<span class="n">${String(i + 1).padStart(2, "0")}</span><span>${escapeHtml(item)}</span>`;
      box.appendChild(row);
    });
    card.appendChild(box);
  } else if (step.resultType === "copy") {
    const box = document.createElement("div");
    box.className = "copy-list";
    r.versions.forEach((v) => {
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

/* ---------- 单个步骤执行 ---------- */
async function runStep(step, li, index, total) {
  li.classList.remove("dim");
  li.classList.add("running");
  setStepState(li, "执行中");
  setStatus(`正在执行 · ${step.title}`, "is-running");
  setPhase("execute", "active");

  const detail = li.querySelector(".step-detail");

  if (step.tool) {
    detail.appendChild(renderToolCall(step.tool));
    await sleep(450);
  }

  if (step.kind === "think") {
    await streamReasoning(detail, step.lines);
  } else if (step.kind === "write") {
    await sleep(350);
  }

  await sleep(Math.max(200, step.dur - 350));

  detail.appendChild(buildResult(step));

  await sleep(420);
  li.classList.remove("running");
  li.classList.add("done");
  setStepState(li, "完成");
}

/* ---------- Agent 主流程 ---------- */
async function runAgent(command) {
  busy = true;
  runBtn.disabled = true;
  runBtn.classList.add("running");

  composer.hidden = true;
  runView.hidden = false;
  timeline.innerHTML = "";
  finalPanel.hidden = true;
  commandBanner.textContent = command;

  const scenario = findScenario(command) || SCENARIOS[0];
  const total = scenario.steps.length;

  /* 阶段一：意图分析 */
  setPhase("understand", "active");
  setStatus("正在解析指令意图…", "is-running");
  await sleep(700);
  setPhase("understand", "done");

  /* 阶段二：任务规划 */
  setPhase("plan", "active");
  setStatus(`正在拆解任务，生成 ${total} 个子任务执行计划…`, "is-running");
  await sleep(700);
  scenario.steps.forEach((s) => timeline.appendChild(buildStepEl(s)));
  await sleep(500);
  setPhase("plan", "done");

  /* 阶段三：逐步执行 */
  setPhase("execute", "active");
  const items = timeline.querySelectorAll(".step");
  for (let i = 0; i < items.length; i++) {
    await runStep(scenario.steps[i], items[i], i, total);
  }
  setPhase("execute", "done");

  /* 阶段四：结果汇总 */
  setPhase("merge", "active");
  setStatus("正在汇总各任务结果…", "is-running");
  await sleep(700);

  const f = scenario.final;
  finalSummary.textContent = f.summary;
  finalOutput.textContent = f.output;
  finalMeta.innerHTML = f.meta.map((m) => `<span>${escapeHtml(m)}</span>`).join("");
  finalPanel.hidden = false;
  setPhase("merge", "done");

  setStatus("✓ 执行完成", "is-done");
  busy = false;
  runBtn.disabled = false;
  runBtn.classList.remove("running");

  finalPanel.scrollIntoView({ behavior: reduceMotion ? "auto" : "smooth", block: "center" });
}

function findScenario(command) {
  const c = command.trim();
  const exact = SCENARIOS.find((s) => s.command === c);
  if (exact) return exact;
  for (const m of MATCHERS) {
    if (m.keys.some((k) => c.includes(k))) {
      return SCENARIOS.find((s) => s.id === m.id);
    }
  }
  return null;
}

/* ---------- 事件绑定 ---------- */
function renderChips() {
  SCENARIOS.forEach((s) => {
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

runBtn.addEventListener("click", () => {
  const command = commandInput.value.trim();
  if (!command || busy) return;
  runAgent(command);
});

resetBtn.addEventListener("click", () => {
  runView.hidden = true;
  composer.hidden = false;
  timeline.innerHTML = "";
  finalPanel.hidden = true;
  document.querySelectorAll(".phase").forEach((p) => p.classList.remove("active", "done"));
  setStatus("待命", "");
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

/* ---------- 初始化 ---------- */
renderChips();
setStatus("待命", "");
