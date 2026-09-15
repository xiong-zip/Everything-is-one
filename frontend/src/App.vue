<template>
  <div class="app">
    <!-- 左侧对话栏：品牌徽标常驻，收起时是细竖条，点击展开历史对话 -->
    <aside
      class="rail"
      :class="{ open: railOpen }"
      :style="{ width: railOpen ? '216px' : '56px', flexBasis: railOpen ? '216px' : '56px' }"
      aria-label="对话列表"
    >
      <button class="rail-brand" type="button" :title="railOpen ? '收起对话栏' : '展开历史对话'" @click="toggleRail()">
        <span class="rail-brand-mark" aria-hidden="true">
          <svg viewBox="0 0 32 32" width="20" height="20" fill="none">
            <circle cx="16" cy="16" r="6.5" stroke="currentColor" stroke-width="2"/>
            <circle cx="16" cy="16" r="2" fill="currentColor"/>
            <path d="M16 2.5v5M16 24.5v5M2.5 16h5M24.5 16h5M6.4 6.4l3.5 3.5M22.1 22.1l3.5 3.5M25.6 6.4l-3.5 3.5M9.9 22.1l-3.5 3.5" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
        </span>
        <span v-show="railOpen" class="rail-brand-text">
          <b>AgentFlow</b>
          <small>AI 任务助手</small>
        </span>
        <i v-show="railOpen" class="rail-brand-arrow" aria-hidden="true">‹</i>
      </button>

      <!-- 列表区：收起时仅透明隐藏（保持占位），保证底部工作台按钮位置不动 -->
      <div class="rail-body">
        <div class="rail-head">
          <button class="rail-new" type="button" @click="onNewSession">＋ 新对话</button>
          <button v-if="history.length" class="rail-op rail-op-text" type="button" @click="confirmClear">清空</button>
        </div>
        <div class="rail-list">          <div v-if="historyLoading && !history.length" class="rail-empty">加载中…</div>
          <div v-else-if="!history.length" class="rail-empty">
            还没有对话<br />
            <small>点「＋ 新对话」开始，每条消息自动归档到所在对话</small>
          </div>
          <div
            v-for="s in history"
            :key="s.id"
            class="rail-item"
            :class="{ active: s.id === sessionId }"
            role="button"
            tabindex="0"
            :title="(s.lastTime || '') + ' · ' + (s.count || 0) + ' 条消息'"
            @click="onOpenSession(s.id)"
            @keydown.enter="onOpenSession(s.id)"
          >
            <div class="rail-row">
              <span class="rail-text">{{ s.title || '（空对话）' }}</span>
              <span class="rail-count">{{ s.count }}</span>
              <button class="rail-del" type="button" aria-label="删除该对话" @click.stop="onDeleteSession(s.id)">✕</button>
            </div>
          </div>
        </div>
      </div>

      <!-- 工作台入口：左栏底部，跟随侧栏一同收起/展开（收起时只剩图标） -->
      <button class="rail-wb" type="button" title="工作台：链路分析 / 数据库 / GitLab / 效能 / 晨报 / 工具 / 模型" @click="openWorkbench">
        <span class="rail-wb-ico" aria-hidden="true">☰</span>
        <span v-show="railOpen" class="rail-wb-text">工作台</span>
      </button>
    </aside>

    <div class="main-col">
      <!-- 工作台：窗口弹窗，左侧菜单 + 右侧内容（入口在左下角输入区） -->
      <WorkbenchModal
        v-if="workbenchOpen"
        :tab="workbenchTab"
        @close="workbenchOpen = false"
        @run="analyzeFromRecord"
        @db-active-changed="dbActive = $event"
      />

      <!-- 全局轻提示：任何面板里的操作结果都从这里统一弹出 -->
      <ToastHost />

      <!-- 聊天区：左缘贴一条消息导航竖条（每条消息一条横线，悬停波浪放大） -->
      <div class="chat-area">
        <!-- 模型选择浮层胶囊：下拉切换当前使用的模型（档案在工作台 → 模型接入 维护） -->
        <div class="llm-float-wrap">
          <ModelPicker />
        </div>
        <nav v-if="runs.length" class="msg-rail" aria-label="消息导航">
          <div
            v-for="m in msgMarks"
            :key="m.id"
            class="mm-item"
            :class="{ active: m.id === activeMsgId, showing: m.id === msgHover }"
            :style="{ top: m.top + 'px' }"
            role="button"
            tabindex="0"
            :title="m.cmd"
            @mouseenter="msgHover = m.id"
            @mouseleave="msgHover = ''"
            @click="scrollToRun(m.id)"
            @keydown.enter="scrollToRun(m.id)"
          >
            <i class="mm-line" :class="m.cls" :style="{ width: lineWidth(m) + 'px' }"></i>
            <div class="mm-pop">
              <div class="rail-cmd">{{ m.cmd }}</div>
              <div class="rail-meta">
                <span class="rail-status" :class="m.done ? 'done' : ''">{{ m.state }}</span>
                <span v-if="m.summary" class="rail-summary">{{ m.summary }}</span>
              </div>
            </div>
          </div>
        </nav>

        <main class="chat-main" ref="scrollEl" @scroll="onChatScroll">
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
            <p>告诉我任何任务——根据提交生成日报周报、分析链路定位故障根因、查数据库表结构，或者写文案、写邮件。我会自动拆解、调用工具、逐步完成并汇总结果。</p>
            <div class="welcome-ideas">
              <button v-for="s in examples" :key="s.command" type="button" class="chip" @click="prefill = { command: s.command, nonce: Date.now() }">{{ s.short }}</button>
            </div>
          </div>

          <!-- 有更早的消息时按需加载：点一下取回上一页，滚动位置保持在原处 -->
          <div v-if="sessionHasMore && runs.length" class="load-earlier">
            <button type="button" :disabled="sessionLoadingMore" @click="onLoadEarlier">
              {{ sessionLoadingMore ? '加载中…' : '↑ 加载更早的消息' }}
            </button>
          </div>

          <div v-for="run in runs" :key="run.id" class="turn" :class="{ replay: run.replayed }" :data-run-id="run.id">
            <div class="user-bubble-row">
              <div class="user-bubble">{{ run.command }}</div>
            </div>
            <AgentRun :run="run" @plan-confirm="onPlanConfirm" @plan-cancel="onPlanCancel" @clarify-run="runAgent" />
          </div>
        </div>
      </main>
      </div>

      <Composer :busy="busy" :examples="examples" :prefill="prefill" :confirmMode="confirmMode" :dbProfiles="dbProfiles" :dbActive="dbActive" @send="onSend" @stop="stopRun" @toggle-confirm="confirmMode = !confirmMode" @db-active="onDbActive" />
    </div>
  </div>
</template>

<script setup>
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import AgentRun from './components/AgentRun.vue'
import Composer from './components/Composer.vue'
import ModelPicker from './components/ModelPicker.vue'
import WorkbenchModal from './components/WorkbenchModal.vue'
import ToastHost from './components/ToastHost.vue'
import { useAgent } from './composables/useAgent'

const API_BASE = '/api/agent'

const examples = ref([])
const llm = ref({ text: '● 连接中…', cls: '' })
const scrollEl = ref(null)
const prefill = ref({ command: '', nonce: 0 })
const railOpen = ref(typeof window === 'undefined' || window.innerWidth > 900)
const activeId = ref('')
const activeMsgId = ref('')
const msgMarks = ref([])
const msgHover = ref('')
const workbenchOpen = ref(false)
const workbenchTab = ref('trace')
const dbProfiles = ref([])
const dbActive = ref('')

/* 左下角「工作台」入口：打开窗口弹窗，默认展示链路分析 */
function openWorkbench() {
  workbenchTab.value = 'trace'
  workbenchOpen.value = true
}

/* 链路分析记录里点「再次分析」：关窗并把指令预填进输入框（不直接执行） */
function analyzeFromRecord(traceId) {
  workbenchOpen.value = false
  prefill.value = { command: `分析链路 ${traceId}`, nonce: Date.now() }
}

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

/* 工作台窗口关闭后刷新输入区选择器与顶栏模型徽标（连接/模型可能在里面改过） */
watch(workbenchOpen, (open) => {
  if (!open) {
    loadDbMeta()
    loadLlmInfo()
  }
})

const {
  runs, busy, runAgent, stopRun, confirmMode, confirmPlan,
  replayRun, history, historyLoading, clearHistoryAll,
  sessionId, loadSessions, newSession, openSession, deleteSession,
  sessionHasMore, sessionLoadingMore, loadEarlier,
} = useAgent()

/* 发送新消息：直接执行（消息横线自动出现在左缘导航条） */
function onSend(command) {
  runAgent(command)
}

function onPlanConfirm({ run, steps }) {
  confirmPlan(run, steps)
}

function onPlanCancel() {
  stopRun()
}

/* 顶栏品牌按钮 / 收起箭头共用：切换左侧历史栏 */
function toggleRail() {
  railOpen.value = !railOpen.value
}

/* 消息导航竖条：横线固定间距聚拢居中（不随滚动位置映射），悬停波浪放大 */
function updateMsgMarks() {
  const sc = scrollEl.value
  if (!sc || !runs.value.length) {
    msgMarks.value = []
    return
  }
  const railH = sc.clientHeight
  const n = runs.value.length
  const spacing = n > 1 ? Math.max(4, Math.min(12, (railH - 20) / (n - 1))) : 0
  const startY = Math.max(6, (railH - (n - 1) * spacing) / 2)
  msgMarks.value = runs.value.map((run, i) => ({
    id: run.id,
    idx: i,
    top: Math.round(startY + i * spacing),
    cmd: run.command,
    summary: run.final.summary,
    state: run.status?.text || (run.final.visible ? '完成' : '进行中'),
    cls: run.status?.cls || (run.final.visible ? 'is-done' : ''),
    done: run.final.visible || !!run.status?.text,
  }))
  updateActive()
}

/* 波浪宽度：悬停的横线最长，相邻按距离衰减（Dock 放大效果），活动消息保底加长 */
function lineWidth(m) {
  const base = m.id === activeMsgId.value ? 10 : 7
  const h = msgHover.value
  if (!h) return base
  const hi = msgMarks.value.findIndex((x) => x.id === h)
  if (hi < 0) return base
  const d = Math.abs(hi - m.idx)
  if (d === 0) return 18
  if (d === 1) return 14
  if (d === 2) return 11
  return base
}

/* 当前阅读位置：视口上 1/3 内最后一条 */
function updateActive() {
  const sc = scrollEl.value
  if (!sc) return
  let active = null
  runs.value.forEach((run) => {
    const el = sc.querySelector(`[data-run-id="${run.id}"]`)
    if (!el) return
    const top = el.getBoundingClientRect().top - sc.getBoundingClientRect().top + sc.scrollTop
    if (top >= sc.scrollTop - 8 && top < sc.scrollTop + sc.clientHeight * 0.4) active = run.id
  })
  if (active) activeMsgId.value = active
}

let mmRaf = 0
function onChatScroll() {
  // 滚动会重算横线位置（鼠标不动横线也会移走），悬停态一并失效
  msgHover.value = ''
  if (mmRaf) return
  mmRaf = requestAnimationFrame(() => {
    mmRaf = 0
    updateActive()
  })
}

/* 点击横线滚动定位到那条消息 */
function scrollToRun(id) {
  activeMsgId.value = id
  msgHover.value = ''
  const el = scrollEl.value?.querySelector(`[data-run-id="${id}"]`)
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

/* 兜底：横线随滚动移位后 mouseleave 可能不触发，全局监听鼠标位置强制收起预览 */
function onGlobalMouseMove(e) {
  if (msgHover.value && !(e.target && e.target.closest && e.target.closest('.mm-item'))) {
    msgHover.value = ''
  }
}

/* 新建对话：清空消息区并切到全新对话 */
function onNewSession() {
  if (busy.value) return
  newSession()
}

/* 打开历史对话：整段消息回放到聊天区。
   渲染完成直接瞬时定位到底部（最新消息结尾）——chat-main 是 smooth 滚动，
   走默认跟随会把整段对话从头滑到尾，切换体验极差，这里必须 instant。 */
async function openSessionAtBottom(id) {
  suppressAutoScroll = true
  try {
    await openSession(id)
    await nextTick()
    const sc = scrollEl.value
    if (sc) sc.scrollTo({ top: sc.scrollHeight, behavior: 'instant' })
    updateMsgMarks()
  } finally {
    suppressAutoScroll = false
  }
}

async function onOpenSession(id) {
  if (busy.value || id === sessionId.value) return
  activeId.value = id
  if (typeof window !== 'undefined' && window.innerWidth <= 900) railOpen.value = false
  await openSessionAtBottom(id)
}

/* 删除对话（删的是当前对话时自动落到新对话） */
function onDeleteSession(id) {
  if (busy.value) return
  deleteSession(id)
}

function confirmClear() {
  if (window.confirm('确定清空全部对话历史？此操作不可恢复。')) {
    clearHistoryAll()
  }
}

/* 新一轮开始、步骤增加或汇总出现时，滚动到底部跟随最新进展，并刷新消息导航条；
   加载更早消息属于向上补历史，不触发跟随（由 onLoadEarlier 自行保持滚动位置） */
let suppressAutoScroll = false
watch(
  () => runs.value.map((r) => `${r.steps.length}-${r.final.visible ? 1 : 0}`).join(','),
  async () => {
    await nextTick()
    if (suppressAutoScroll) return
    if (scrollEl.value) scrollEl.value.scrollTop = scrollEl.value.scrollHeight
    updateMsgMarks()
  }
)

/* 加载更早的消息：记录当前滚动位置，插入上一页后锚回原位，视觉上不跳动
   （chat-main 是 smooth 滚动，这里必须 instant，否则锚点会滑行动画跑偏） */
async function onLoadEarlier() {
  const sc = scrollEl.value
  const prevHeight = sc ? sc.scrollHeight : 0
  const prevTop = sc ? sc.scrollTop : 0
  suppressAutoScroll = true
  try {
    await loadEarlier()
    await nextTick()
    if (sc) sc.scrollTo({ top: prevTop + (sc.scrollHeight - prevHeight), behavior: 'instant' })
    updateMsgMarks()
  } finally {
    suppressAutoScroll = false
  }
}

/* 视口尺寸变化时重算横线位置 */
if (typeof window !== 'undefined') {
  window.addEventListener('resize', () => updateMsgMarks())
}

/* 每轮对话结束后刷新左侧对话列表（新对话首条消息后出现） */
watch(
  () => runs.value.map((r) => r.final.visible ? 1 : 0).join(','),
  () => { loadSessions() }
)

/* 顶栏模型徽标：随「模型接入」配置变化刷新 */
async function loadLlmInfo() {
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
}

onMounted(async () => {
  document.addEventListener('mousemove', onGlobalMouseMove)
  // 模型接入面板里的增删改/切换会广播该事件，顶栏徽标即时刷新
  window.addEventListener('af-llm-changed', loadLlmInfo)
  loadDbMeta()
  loadLlmInfo()
  // 恢复上次的对话：存在则整段回放，否则落到新对话
  await loadSessions()
  const last = localStorage.getItem('af-session-id') || ''
  if (last && history.value.some((s) => s.id === last)) {
    await openSessionAtBottom(last)
    await nextTick()
    updateMsgMarks()
  } else if (!last) {
    newSession()
  }
  try {
    const res = await fetch(`${API_BASE}/scenarios`)
    if (res.ok) examples.value = await res.json()
  } catch { /* 后端不可用时静默降级 */ }
})
onUnmounted(() => {
  document.removeEventListener('mousemove', onGlobalMouseMove)
  window.removeEventListener('af-llm-changed', loadLlmInfo)
})
</script>
