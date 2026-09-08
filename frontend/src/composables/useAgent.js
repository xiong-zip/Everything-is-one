/* Agent 执行引擎（前端）：多轮对话历史 + SSE 流式消费 + 历史回放
   每一轮 run = { id, command, replayed, status, intent, phases, steps, final } */
import { reactive, ref } from 'vue'

const API_BASE = '/api/agent'
const HISTORY_TURNS = 5

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const reduceMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches

function newRun(id, command, replayed = false) {
  return {
    id,
    command,
    replayed,
    status: { text: replayed ? '读取历史记录…' : '正在连接后端…', cls: replayed ? 'is-done' : 'is-running' },
    intent: null, // { summary, entities: [] }
    phases: { understand: '', plan: '', execute: '', merge: '' },
    steps: [],
    processExpanded: true, // 执行过程折叠态：完成后默认收起
    final: { visible: false, summary: '', output: '', meta: [] },
  }
}

export function useAgent() {
  const runs = ref([])
  const busy = ref(false)
  const history = ref([])
  const historyLoading = ref(false)
  let runSeq = 0

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
    return {
      status(d) {
        run.status.text = d.text
        run.status.cls = d.cls || ''
      },
      phase(d) {
        run.phases[d.name] = d.state
      },
      intent(d) {
        run.intent = { summary: d.summary || '', entities: d.entities || [] }
      },
      step(d) {
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
        }
      },
      'step-state'(d) {
        const st = run.steps[d.index]
        if (!st) return
        st.state = d.state
        // 思考步完成后默认折叠推理过程，只保留结论行，可手动展开
        if (d.state === 'done' && st.kind === 'think' && st.reasons.length) {
          st.reasonExpanded = false
        }
      },
      tool(d) {
        const st = run.steps[d.index]
        if (st) st.tools.push({ name: d.name, args: d.args })
      },
      async reason(d) {
        const st = run.steps[d.index]
        if (!st) return
        const line = { shown: instant ? d.line : '' }
        st.reasons.push(line)
        if (instant) return
        await typeInto(line, d.line)
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

  function consumeSseBuffer(handlers, buf) {
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
      if (data) dispatch(handlers, event, JSON.parse(data))
    }
    return buf
  }

  async function consumeStream(handlers, taskId) {
    const res = await fetch(`${API_BASE}/stream/${taskId}`)
    if (!res.ok) throw new Error('流连接失败')
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buf = ''
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
      buf = consumeSseBuffer(handlers, buf)
    }
    reader.releaseLock()
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
        body: JSON.stringify({ command, history: turns }),
      })
      if (!res.ok) throw new Error('创建任务失败')
      const data = await res.json()
      await consumeStream(handlers, data.taskId)
    } catch (err) {
      if (!run.final.visible) {
        handlers.status({ text: '连接中断，请重试', cls: 'is-running' })
      }
    } finally {
      busy.value = false
    }
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
      run.status = { text: '历史回放 · ' + (data.createdAt || ''), cls: 'is-done' }
    } catch {
      /* 历史不可用时静默 */
    }
  }

  async function loadHistory() {
    historyLoading.value = true
    try {
      const res = await fetch(`${API_BASE}/history`)
      if (res.ok) history.value = await res.json()
    } catch {
      /* 静默降级 */
    } finally {
      historyLoading.value = false
    }
  }

  async function deleteHistoryRun(id) {
    history.value = history.value.filter((h) => h.id !== id)
    try {
      await fetch(`${API_BASE}/history/${id}`, { method: 'DELETE' })
    } catch { /* 静默 */ }
  }

  async function clearHistoryAll() {
    history.value = []
    try {
      await fetch(`${API_BASE}/history`, { method: 'DELETE' })
    } catch { /* 静默 */ }
  }

  function clearAll() {
    runs.value = []
  }

  return {
    runs, busy, runAgent, clearAll,
    replayRun, history, historyLoading, loadHistory, deleteHistoryRun, clearHistoryAll,
  }
}
