<template>
  <div class="app">
    <ChatHeader :llm="llm" :hasRuns="runs.length > 0" :confirmMode="confirmMode" @clear="clearAll()" @history="openHistory()" @toggle-confirm="confirmMode = !confirmMode" @tools="toolsOpen = true" />

    <!-- 任务历史抽屉：点击条目即可回放当时的完整执行过程 -->
    <div v-if="historyOpen" class="drawer-mask" @click.self="historyOpen = false">
      <aside class="history-drawer" role="dialog" aria-label="任务历史">
        <div class="hd-head">
          <h3>任务历史</h3>
          <div class="hd-actions">
            <button v-if="history.length" class="hd-clear" type="button" @click="confirmClear">清空历史</button>
            <button class="hd-close" type="button" aria-label="关闭" @click="historyOpen = false">✕</button>
          </div>
        </div>
        <div class="hd-list">
          <div v-if="historyLoading" class="hd-empty">加载中…</div>
          <div v-else-if="!history.length" class="hd-empty">
            还没有历史任务<br /><small>每次执行都会自动存档，可随时回放</small>
          </div>
          <div
            v-for="h in history"
            :key="h.id"
            class="hd-item"
            role="button"
            tabindex="0"
            @click="replay(h.id)"
            @keydown.enter="replay(h.id)"
          >
            <div class="hd-cmd">{{ h.command }}</div>
            <div class="hd-sub">
              <span class="hd-status" :class="h.status">{{ statusText(h.status) }}</span>
              <span class="hd-time">{{ h.createdAt }}</span>
              <span v-if="h.summary" class="hd-summary">{{ h.summary }}</span>
            </div>
            <button class="hd-del" type="button" aria-label="删除该记录" @click.stop="removeHistory(h.id)">✕</button>
          </div>
        </div>
      </aside>
    </div>

    <!-- 工具管理抽屉：查看/导入/删除工具 -->
    <ToolsDrawer v-if="toolsOpen" @close="toolsOpen = false" />

    <main class="chat-main" ref="scrollEl">
      <div class="chat-scroll">
        <div v-if="runs.length === 0" class="welcome">
          <div class="welcome-mark">
            <svg viewBox="0 0 32 32" width="32" height="32" fill="none">
              <circle cx="16" cy="16" r="6.5" stroke="currentColor" stroke-width="2"/>
              <circle cx="16" cy="16" r="2" fill="currentColor"/>
              <path d="M16 2.5v5M16 24.5v5M2.5 16h5M24.5 16h5M6.4 6.4l3.5 3.5M22.1 22.1l3.5 3.5M25.6 6.4l-3.5 3.5M9.9 22.1l-3.5 3.5" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            </svg>
          </div>
          <h1>你好，我是 <em>AgentFlow</em></h1>
          <p>告诉我任何任务——查天气、看股价、规划行程，或者写文案、写邮件。我会自动拆解、调用工具、逐步完成并汇总结果。</p>
          <div class="welcome-ideas">
            <button v-for="s in examples" :key="s.command" type="button" class="chip" @click="prefill = { command: s.command, nonce: Date.now() }">{{ s.short }}</button>
          </div>
        </div>

        <div v-for="run in runs" :key="run.id" class="turn">
          <div class="user-bubble-row">
            <div class="user-bubble">{{ run.command }}</div>
          </div>
          <AgentRun :run="run" @plan-confirm="onPlanConfirm" @plan-cancel="onPlanCancel" />
        </div>
      </div>
    </main>

    <Composer :busy="busy" :examples="examples" :prefill="prefill" @send="runAgent" @stop="stopRun" />
  </div>
</template>

<script setup>
import { nextTick, onMounted, ref, watch } from 'vue'
import ChatHeader from './components/ChatHeader.vue'
import AgentRun from './components/AgentRun.vue'
import Composer from './components/Composer.vue'
import ToolsDrawer from './components/ToolsDrawer.vue'
import { useAgent } from './composables/useAgent'

const API_BASE = '/api/agent'

const examples = ref([])
const llm = ref({ text: '● 连接中…', cls: '' })
const scrollEl = ref(null)
const prefill = ref({ command: '', nonce: 0 })
const historyOpen = ref(false)
const toolsOpen = ref(false)

const {
  runs, busy, runAgent, stopRun, clearAll, confirmMode, confirmPlan,
  replayRun, history, historyLoading, loadHistory, deleteHistoryRun, clearHistoryAll,
} = useAgent()

function onPlanConfirm({ run, steps }) {
  confirmPlan(run, steps)
}

function onPlanCancel() {
  stopRun()
}

async function openHistory() {
  historyOpen.value = true
  await loadHistory()
}

async function replay(id) {
  historyOpen.value = false
  await replayRun(id)
}

function removeHistory(id) {
  deleteHistoryRun(id)
}

function confirmClear() {
  if (window.confirm('确定清空全部任务历史？此操作不可恢复。')) {
    clearHistoryAll()
  }
}

function statusText(s) {
  return { done: '完成', error: '异常', interrupted: '中断', running: '进行中' }[s] || s
}

/* 新一轮开始、步骤增加或汇总出现时，滚动到底部跟随最新进展 */
watch(
  () => runs.value.map((r) => `${r.steps.length}-${r.final.visible ? 1 : 0}`).join(','),
  async () => {
    await nextTick()
    if (scrollEl.value) scrollEl.value.scrollTop = scrollEl.value.scrollHeight
  }
)

onMounted(async () => {
  try {
    const res = await fetch(`${API_BASE}/scenarios`)
    if (res.ok) examples.value = await res.json()
  } catch { /* 后端不可用时静默降级 */ }

  try {
    const res = await fetch(`${API_BASE}/info`)
    if (res.ok) {
      const info = await res.json()
      llm.value = info.llmEnabled
        ? { text: `● ${info.model} 已接入`, cls: 'on' }
        : { text: '● 模拟模式 · 未配置 API Key', cls: 'off' }
    }
  } catch {
    llm.value = { text: '● 后端不可用', cls: '' }
  }
})
</script>
