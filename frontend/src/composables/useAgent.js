/* Agent 执行引擎（前端）：多轮对话历史 + SSE 流式消费 + 历史回放
   每一轮 run = { id, command, replayed, status, intent, phases, steps, final } */
import { reactive, ref, watch } from 'vue'

const API_BASE = '/api/agent'
const HISTORY_TURNS = 5
/* 对话消息分页：打开对话默认加载最近 N 条，更早的按需加载 */
const PAGE_SIZE = 20

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const reduceMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches

/* 唤醒正在等待计划确认的 runAgent 循环（确认到达 / 用户取消） */
function resolveConfirm(run) {
  if (run.confirmResolve) {
    const r = run.confirmResolve
    run.confirmResolve = null
    r()
  }
}

function newRun(id, command, replayed = false) {
  return {
    id,
    command,
    replayed,
    taskId: null, // 后端任务 id（confirm 取消/确认用）
    lastSeq: -1, // 已收到的最大事件序号，断线重连时从这之后补发
    stopped: false, // 用户主动停止（与连接异常区分）
    startedAt: Date.now(), // 本轮开始时间（计算总耗时；回放不计时）
    totalSteps: 0, // 规划的总步数（进度条用）
    status: { text: replayed ? '读取历史记录…' : '正在连接后端…', cls: replayed ? 'is-done' : 'is-running' },
    intent: null, // { summary, entities: [] }
    phases: { understand: '', plan: '', execute: '', merge: '' },
    steps: [],
    planProposal: null, // confirm 模式：{ steps, waiting, confirmed }
    clarify: null, // 未命中候选：{ question, options, done }
    confirmResolve: null, // 等待用户确认计划的 Promise resolver
    processExpanded: true, // 执行过程折叠态：完成后默认收起
    final: { visible: false, cancelled: false, summary: '', output: '', meta: [], durationMs: 0 },
  }
}

export function useAgent() {
  const runs = ref([])
  const busy = ref(false)
  const history = ref([])
  const historyLoading = ref(false)
  // 对话分页状态：是否还有更早的消息可加载、加载中标记
  const sessionHasMore = ref(false)
  const sessionLoadingMore = ref(false)
  // 多对话模型：当前对话 id（持久化，刷新后恢复所在对话）
  const sessionId = ref(localStorage.getItem('af-session-id') || '')
  function ensureSession() {
    if (!sessionId.value) {
      sessionId.value = (crypto.randomUUID ? crypto.randomUUID() : String(Date.now() + Math.random()))
      localStorage.setItem('af-session-id', sessionId.value)
    }
    return sessionId.value
  }
  function setSession(id) {
    sessionId.value = id
    localStorage.setItem('af-session-id', id)
  }
  // 确认模式：任务规划后先推送计划，用户确认/编辑再执行（本地记忆开关）
  const confirmMode = ref(localStorage.getItem('af-confirm-mode') === '1')
  watch(confirmMode, (v) => localStorage.setItem('af-confirm-mode', v ? '1' : '0'))
  let runSeq = 0
  let activeTaskId = null
  let activeAbort = null

  async function typeInto(target, text, chunk = 2) {
    if (reduceMotion()) {
      target.shown = text
      return
    }
    // 长行自适应加大步长，单行打字时间收敛在 ~0.6s
    const step = Math.max(2, Math.ceil(text.length / 42))
    for (let i = 0; i < text.length; i += step) {
      target.shown = text.slice(0, i + step)
      await sleep(14)
    }
    target.shown = text
  }

  /* instant=true 时不做打字动画，用于历史回放 */
  function handlersFor(run, instant = false) {
    /* 打字动画串行队列：dispatch 不 await，如果每条推理行各起一个定时器，
       并行步骤会同时跑 N 个 14ms 计时器写响应式状态，白白放大重渲染。
       另外只给前几条做动画——用户看的是开头的推理过程，后面的行直接落地更省。 */
    const MAX_ANIMATED_REASONS = 2
    let reasonQueue = Promise.resolve()
    let animatedReasons = 0

    return {
      status(d) {
        run.status.text = d.text
        run.status.cls = d.cls || ''
      },
      phase(d) {
        run.phases[d.name] = d.state
      },
      /* confirm 模式：计划提案，等待用户确认/编辑；argsJson 供计划卡内编辑工具参数 */
      'plan-proposal'(d) {
        const steps = (d.steps || []).map((s) => ({
          ...s,
          skip: false,
          argsJson: s.tool ? JSON.stringify(s.tool.args || {}, null, 2) : null,
        }))
        run.planProposal = { steps, waiting: true, confirmed: false }
      },
      'plan-confirmed'(d) {
        if (run.planProposal) {
          run.planProposal.waiting = false
          run.planProposal.confirmed = true
          run.planProposal.skipped = run.planProposal.steps.filter((s) => s.skip).length
        }
        resolveConfirm(run)
      },
      /* 未命中候选：选项卡展示；回放时直接呈收起态 */
      clarify(d) {
        run.clarify = {
          question: d.question || '没有找到直接匹配的结果，你想找的是：',
          options: d.options || [],
          done: !!instant,
        }
      },
      intent(d) {
        run.intent = { summary: d.summary || '', entities: d.entities || [] }
      },
      step(d) {
        if (d.total > run.totalSteps) run.totalSteps = d.total
        run.steps[d.index] = {
          index: d.index,
          kind: d.kind,
          tag: d.tag,
          title: d.title,
          state: 'pending',
          tools: [],
          reasons: [],
          reasonExpanded: true,
          result: null,
          durationMs: 0,
        }
      },
      'step-state'(d) {
        const st = run.steps[d.index]
        if (!st) return
        if (d.state === 'running' && !instant) st.startedMs = Date.now()
        st.state = d.state
        if (d.state === 'done' && st.startedMs) {
          st.durationMs = Date.now() - st.startedMs
        }
        // 思考步完成后默认折叠推理过程，只保留结论行，可手动展开
        if (d.state === 'done' && st.kind === 'think' && st.reasons.length) {
          st.reasonExpanded = false
        }
      },
      tool(d) {
        const st = run.steps[d.index]
        if (st) st.tools.push({ name: d.name, args: d.args })
      },
      reason(d) {
        const st = run.steps[d.index]
        if (!st) return
        if (instant || animatedReasons >= MAX_ANIMATED_REASONS) {
          st.reasons.push({ shown: d.line })
          return
        }
        const line = { shown: '' }
        st.reasons.push(line)
        animatedReasons++
        reasonQueue = reasonQueue.then(() => typeInto(line, d.line)).catch(() => {})
      },
      /* write 步流式增量：先建一个持续增长的 copy 结果，收尾由 result 事件整体覆盖 */
      'result-delta'(d) {
        const st = run.steps[d.index]
        if (!st) return
        if (!st.result || !st.result._streaming) {
          st.result = {
            type: 'copy',
            data: { versions: [{ tag: 'AI 生成', text: '' }] },
            list: [],
            _streaming: true,
          }
        }
        st.result.data.versions[0].text += d.delta
      },
      result(d) {
        const st = run.steps[d.index]
        if (st) st.result = { type: d.resultType, data: d.result, list: d.list }
      },
      done(d) {
        run.final.summary = d.summary
        run.final.output = d.output
        run.final.meta = d.meta || []
        run.final.cancelled = !!d.cancelled
        if (!instant) run.final.durationMs = Date.now() - run.startedAt
        run.final.visible = true
        // 任务已交付，执行过程默认折叠，只展示任务汇总
        run.processExpanded = false
      },
    }
  }

  function dispatch(handlers, event, d) {
    const fn = handlers[event]
    if (fn) fn(d)
  }

  function consumeSseBuffer(handlers, buf, run) {
    let idx
    while ((idx = buf.indexOf('\n\n')) !== -1) {
      const block = buf.slice(0, idx)
      buf = buf.slice(idx + 2)
      let event = 'message'
      let data = ''
      block.split('\n').forEach((line) => {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) data = line.slice(5).trim()
      })
      if (data) {
        const parsed = JSON.parse(data)
        if (run && parsed.seq != null) run.lastSeq = parsed.seq
        dispatch(handlers, event, parsed)
      }
    }
    return buf
  }

  async function consumeStream(handlers, taskId, run, afterSeq = -1) {
    activeAbort = new AbortController()
    const q = afterSeq >= 0 ? `?afterSeq=${afterSeq}` : ''
    const res = await fetch(`${API_BASE}/stream/${taskId}${q}`, { signal: activeAbort.signal })
    if (!res.ok) throw new Error('流连接失败')
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buf = ''
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
      buf = consumeSseBuffer(handlers, buf, run)
    }
    reader.releaseLock()
  }

  /* 断线自动重连：带 lastSeq 续读缺失事件；服务端已结束时补发完会自然收流 */
  async function consumeWithResume(handlers, taskId, run, retries = 3) {
    try {
      await consumeStream(handlers, taskId, run, run.lastSeq ?? -1)
    } catch (err) {
      if (run.stopped || err.name === 'AbortError' || run.final.visible) return
      if (retries > 0) {
        await sleep(1200)
        return consumeWithResume(handlers, taskId, run, retries - 1)
      }
      throw err
    }
  }

  /* ---------- 主流程：追加一轮对话并执行 ---------- */
  async function runAgent(command) {
    if (busy.value || !command.trim()) return
    busy.value = true
    const run = reactive(newRun(++runSeq, command))
    runs.value.push(run)
    const handlers = handlersFor(run)

    // 多轮上下文：携带最近几轮的指令与产出，支持“把刚才的结果做成表格”类追问
    const turns = runs.value
      .filter((r) => r !== run && r.final && r.final.output)
      .slice(-HISTORY_TURNS)
      .map((r) => ({ command: r.command, output: r.final.output }))

    try {
      const res = await fetch(`${API_BASE}/run`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ command, history: turns, mode: confirmMode.value ? 'confirm' : 'auto', sessionId: ensureSession() }),
      })
      if (!res.ok) throw new Error('创建任务失败')
      const data = await res.json()
      activeTaskId = data.taskId
      run.taskId = data.taskId
      await consumeWithResume(handlers, data.taskId, run)
      // confirm 模式：等待确认期间服务端可能已收断连接，确认后重连续读余下事件
      while (run.planProposal && run.planProposal.waiting && !run.stopped && !run.final.visible) {
        await new Promise((resolve) => { run.confirmResolve = resolve })
        await consumeWithResume(handlers, data.taskId, run)
      }
    } catch (err) {
      if (!run.final.visible) {
        handlers.status(run.stopped
          ? { text: '已停止', cls: 'is-done' }
          : { text: '连接中断，请重试', cls: 'is-running' })
      }
    } finally {
      busy.value = false
      activeTaskId = null
      activeAbort = null
    }
  }

  /* ---------- 手动停止：通知后端取消；稍等收尾 done 事件（含部分产出）再断流 ---------- */
  async function stopRun() {
    if (!activeTaskId) return
    const run = runs.value[runs.value.length - 1]
    if (run) {
      run.stopped = true
      resolveConfirm(run)
    }
    try {
      await fetch(`${API_BASE}/cancel/${activeTaskId}`, { method: 'POST' })
    } catch { /* 后端不可达时仅本地中止 */ }
    // 服务端在最近的检查点发完收尾事件后会自然收流；2 秒兜底强制断开
    setTimeout(() => {
      if (activeAbort) activeAbort.abort()
    }, 2000)
  }

  /* ---------- confirm 模式：回传确认/编辑后的计划 ---------- */
  async function confirmPlan(run, steps) {
    if (!run || !run.taskId) return
    try {
      await fetch(`${API_BASE}/run/${run.taskId}/confirm`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ steps }),
      })
    } catch { /* 确认请求失败：保持等待态，用户可重试或取消 */ }
  }

  /* ---------- 历史回放：存量事件走同一套渲染管线，瞬时呈现 ---------- */
  async function replayRun(id) {
    try {
      const res = await fetch(`${API_BASE}/history/${id}`)
      if (!res.ok) return
      const data = await res.json()
      const run = reactive(newRun(++runSeq, data.command, true))
      runs.value.push(run)
      const handlers = handlersFor(run, true)
      for (const e of data.events || []) {
        dispatch(handlers, e.event, e.data)
      }
      // 回放中未等到确认结果的计划卡不再显示等待态
      if (run.planProposal && run.planProposal.waiting) {
        run.planProposal.waiting = false
      }
      run.status = { text: '历史回放 · ' + (data.createdAt || ''), cls: 'is-done' }
    } catch {
      /* 历史不可用时静默 */
    }
  }

  async function loadHistory(keyword = '', limit = 50) {
    historyLoading.value = true
    try {
      const q = new URLSearchParams({ limit: String(limit) })
      if (keyword) q.set('keyword', keyword)
      const res = await fetch(`${API_BASE}/history?${q}`)
      if (res.ok) history.value = await res.json()
    } catch {
      /* 静默降级 */
    } finally {
      historyLoading.value = false
    }
  }

  /* ---------- 多对话：列表 / 打开 / 新建 / 删除 ---------- */

  /* 对话列表（history 复用为 sessions 容器，避免 App 大改） */
  async function loadSessions() {
    historyLoading.value = true
    try {
      const res = await fetch(`${API_BASE}/sessions`)
      if (res.ok) history.value = await res.json()
    } catch {
      /* 静默降级 */
    } finally {
      historyLoading.value = false
    }
  }

  /* 新建对话：清空消息区并切换到全新 session（首条消息发出时生效） */
  function newSession() {
    runs.value = []
    sessionHasMore.value = false
    sessionLoadingMore.value = false
    setSession(crypto.randomUUID ? crypto.randomUUID() : String(Date.now() + Math.random()))
    return sessionId.value
  }

  /* 打开一个历史对话：分页回放——默认最近 PAGE_SIZE 条，更早的在聊天区顶部按需加载 */
  async function openSession(id) {
    setSession(id)
    runs.value = []
    sessionHasMore.value = false
    try {
      const res = await fetch(`${API_BASE}/sessions/${encodeURIComponent(id)}/runs?limit=${PAGE_SIZE}`)
      if (res.ok) {
        renderSessionPage(await res.json(), false)
      }
    } catch {
      /* 静默 */
    }
  }

  /* 一页消息一次性回放（事件由接口内联返回），prepend=true 时插到已有消息前面 */
  function renderSessionPage(data, prepend) {
    const pageRuns = []
    for (const item of data.runs || []) {
      const run = reactive(newRun(++runSeq, item.command, true))
      run.dbId = item.id // 后端 run id，供「加载更早」分页游标使用
      const handlers = handlersFor(run, true)
      for (const e of item.events || []) {
        dispatch(handlers, e.event, e.data)
      }
      // 回放中未等到确认结果的计划卡不再显示等待态
      if (run.planProposal && run.planProposal.waiting) {
        run.planProposal.waiting = false
      }
      run.status = { text: '历史回放 · ' + (item.createdAt || ''), cls: 'is-done' }
      pageRuns.push(run)
    }
    runs.value = prepend ? [...pageRuns, ...runs.value] : pageRuns
    sessionHasMore.value = !!data.hasMore
  }

  /* 按需加载更早的消息：以当前最早一条为游标往回取一页 */
  async function loadEarlier() {
    if (!sessionId.value || !sessionHasMore.value || sessionLoadingMore.value) return
    const first = runs.value.find(Boolean)
    if (!first || !first.dbId) return
    sessionLoadingMore.value = true
    try {
      const res = await fetch(`${API_BASE}/sessions/${encodeURIComponent(sessionId.value)}/runs?limit=${PAGE_SIZE}&before=${first.dbId}`)
      if (res.ok) {
        renderSessionPage(await res.json(), true)
      }
    } catch {
      /* 静默 */
    } finally {
      sessionLoadingMore.value = false
    }
  }

  async function deleteSession(id) {
    history.value = history.value.filter((h) => h.id !== id)
    try {
      await fetch(`${API_BASE}/sessions/${encodeURIComponent(id)}`, { method: 'DELETE' })
    } catch { /* 静默 */ }
    // 删的是当前对话：回到新对话
    if (id === sessionId.value) newSession()
  }

  async function clearHistoryAll() {
    history.value = []
    try {
      await fetch(`${API_BASE}/history`, { method: 'DELETE' })
    } catch { /* 静默 */ }
    newSession()
  }

  function clearAll() {
    runs.value = []
  }

  return {
    runs, busy, runAgent, stopRun, clearAll, confirmMode, confirmPlan,
    replayRun, history, historyLoading, loadHistory, clearHistoryAll,
    sessionId, loadSessions, newSession, openSession, deleteSession,
    sessionHasMore, sessionLoadingMore, loadEarlier,
  }
}
