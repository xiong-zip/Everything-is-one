<template>
  <div class="app">
    <ChatHeader :llm="llm" :hasRuns="runs.length > 0" @clear="clearAll()" @history="openHistory()" @tools="toolsOpen = true" @morning="morningOpen = true" @stats="statsOpen = true" @db="dbOpen = true" @gitlab="gitlabOpen = true" />

    <!-- 任务历史抽屉：左侧滑出，点击条目即可回放当时的完整执行过程 -->
    <div v-if="historyOpen" class="drawer-mask" @click.self="historyOpen = false">
      <aside class="history-drawer hd-left" role="dialog" aria-label="任务历史">
        <div class="hd-head">
          <h3>任务历史</h3>
          <div class="hd-actions">
            <button v-if="history.length" class="hd-clear" type="button" @click="confirmClear">清空历史</button>
            <button class="hd-close" type="button" aria-label="关闭" @click="historyOpen = false">✕</button>
          </div>
        </div>
        <div class="his-search">
          <input
            v-model="historyKeyword"
            class="his-input"
            type="search"
            placeholder="搜索指令或结果摘要…"
            aria-label="搜索历史"
          />
        </div>
        <div class="hd-list">
          <div v-if="historyLoading" class="hd-empty">加载中…</div>
          <div v-else-if="!history.length" class="hd-empty">
            {{ historyKeyword ? '没有匹配的历史任务' : '还没有历史任务' }}<br />
            <small v-if="!historyKeyword">每次执行都会自动存档，可随时回放</small>
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
          <button
            v-if="!historyLoading && history.length && history.length >= historyLimit"
            class="his-more"
            type="button"
            @click="loadMoreHistory"
          >加载更多</button>
        </div>
      </aside>
    </div>

    <!-- 工具管理抽屉：查看/导入/删除工具 -->
    <ToolsDrawer v-if="toolsOpen" @close="toolsOpen = false" />

    <!-- 晨报机器人抽屉：定时状态 + 立即试跑 -->
    <ScheduleDrawer v-if="morningOpen" @close="morningOpen = false" />

    <!-- 效能热力图抽屉 -->
    <StatsDrawer v-if="statsOpen" @close="statsOpen = false" />

    <!-- 数据库连接抽屉 -->
    <DbDrawer v-if="dbOpen" @close="dbOpen = false" @active-changed="dbActive = $event" />

    <!-- GitLab 账户抽屉：维护 Access Token -->
    <GitlabDrawer v-if="gitlabOpen" @close="gitlabOpen = false" />

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
          <AgentRun :run="run" @plan-confirm="onPlanConfirm" @plan-cancel="onPlanCancel" @clarify-run="runAgent" />
        </div>
      </div>
    </main>

    <Composer :busy="busy" :examples="examples" :prefill="prefill" :confirmMode="confirmMode" :dbProfiles="dbProfiles" :dbActive="dbActive" @send="runAgent" @stop="stopRun" @toggle-confirm="confirmMode = !confirmMode" @db-active="onDbActive" />
  </div>
</template>

<script setup>
import { nextTick, onMounted, ref, watch } from 'vue'
import ChatHeader from './components/ChatHeader.vue'
import AgentRun from './components/AgentRun.vue'
import Composer from './components/Composer.vue'
import ToolsDrawer from './components/ToolsDrawer.vue'
import ScheduleDrawer from './components/ScheduleDrawer.vue'
import StatsDrawer from './components/StatsDrawer.vue'
import DbDrawer from './components/DbDrawer.vue'
import GitlabDrawer from './components/GitlabDrawer.vue'
import { useAgent } from './composables/useAgent'

const API_BASE = '/api/agent'

const examples = ref([])
const llm = ref({ text: '● 连接中…', cls: '' })
const scrollEl = ref(null)
const prefill = ref({ command: '', nonce: 0 })
const historyOpen = ref(false)
const historyKeyword = ref('')
let historyLimit = 50
const toolsOpen = ref(false)
const morningOpen = ref(false)
const statsOpen = ref(false)
const dbOpen = ref(false)
const gitlabOpen = ref(false)
const dbProfiles = ref([])
const dbActive = ref('')

async function loadDbMeta() {
  try {
    const [res, act] = await Promise.all([
      fetch('/api/dbprofiles'),
      fetch('/api/dbprofiles/active'),
    ])
    if (res.ok) dbProfiles.value = await res.json()
    if (act.ok) dbActive.value = (await act.json()).name || ''
  } catch { /* 后端不可用时静默降级 */ }
}

/* 输入区一键切换默认数据库连接：之后聊天不点名库也用它 */
async function onDbActive(name) {
  dbActive.value = name
  try {
    await fetch('/api/dbprofiles/active', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name }),
    })
  } catch { /* 静默 */ }
}

/* 连接抽屉里可能改了连接/默认，关闭后刷新输入区选择器状态 */
watch(dbOpen, (open) => {
  if (!open) loadDbMeta()
})

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
  await loadHistory(historyKeyword.value)
}

/* 搜索防抖：300ms 后带关键词重查 */
let searchTimer = null
watch(historyKeyword, (kw) => {
  if (!historyOpen.value) return
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    historyLimit = 50
    loadHistory(kw)
  }, 300)
})

function loadMoreHistory() {
  historyLimit += 50
  loadHistory(historyKeyword.value, historyLimit)
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
  loadDbMeta()
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
