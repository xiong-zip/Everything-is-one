/* Agent 执行引擎（前端）：多轮对话历史 + SSE 流式消费
   每一轮 run = { id, command, status, intent, phases, steps, final } */
import { reactive, ref } from 'vue'

const API_BASE = '/api/agent'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const reduceMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches

function newRun(id, command) {
  return {
    id,
    command,
    status: { text: '正在连接后端…', cls: 'is-running' },
    intent: null, // { summary, entities: [] }
    phases: { understand: '', plan: '', execute: '', merge: '' },
    steps: [],
    final: { visible: false, summary: '', output: '', meta: [] },
  }
}

export function useAgent() {
  const runs = ref([])
  const busy = ref(false)
  let current = null
  let runSeq = 0

  async function typeInto(target, text, chunk = 2) {
    if (reduceMotion()) {
      target.shown = text
      return
    }
    for (let i = 0; i < text.length; i += chunk) {
      target.shown = text.slice(0, i + chunk)
      await sleep(14)
    }
    target.shown = text
  }

  function handlersFor(run) {
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
          result: null,
        }
      },
      'step-state'(d) {
        const st = run.steps[d.index]
        if (st) st.state = d.state
      },
      tool(d) {
        const st = run.steps[d.index]
        if (st) st.tools.push({ name: d.name, args: d.args })
      },
      async reason(d) {
        const st = run.steps[d.index]
        if (!st) return
        const line = { shown: '' }
        st.reasons.push(line)
        await typeInto(line, d.line)
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
    current = run
    const handlers = handlersFor(run)

    try {
      const res = await fetch(`${API_BASE}/run`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ command }),
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
      current = null
    }
  }

  function clearAll() {
    runs.value = []
  }

  return { runs, busy, runAgent, clearAll }
}
