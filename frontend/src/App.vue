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
              <input
                v-if="renamingId === s.id"
                class="rail-rename"
                v-model="renamingTitle"
                maxlength="60"
                :ref="focusRenameInput"
                @click.stop
                @keydown.enter.prevent="commitRename(s)"
                @keydown.esc.prevent="cancelRename"
                @blur="commitRename(s)"
              />
              <template v-else>
                <span class="rail-text" @dblclick.stop="startRename(s)">{{ s.title || '（空对话）' }}</span>
                <span class="rail-count">{{ s.count }}</span>
                <button class="rail-del rail-edit-btn" type="button" aria-label="编辑对话标题" title="编辑标题" @click.stop="startRename(s)">✎</button>
                <button class="rail-del" type="button" aria-label="删除该对话" @click.stop="onDeleteSession(s.id)">✕</button>
              </template>
            </div>
          </div>
        </div>
      </div>

      <!-- 工作台入口：左栏底部，跟随侧栏一同收起/展开（收起时只剩图标） -->
      <button class="rail-wb" type="button" title="工作台：可观测 / 工具与自动化 / 外部接入 / 知识与记忆 / 模型与成本" @click="openWorkbench">
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
        @run="onWorkbenchRun"
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
            <i class="mm-line" :class="m.cls" :style="{ width: m.width + 'px' }"></i>
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
            <p>告诉我任何任务——根据提交生成日报周报、分析链路定位故障根因、查数据库表结构，或者写文案、写公告。我会自动拆解、调用工具、逐步完成并汇总结果。</p>
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
              <!-- 编辑态：最后一轮消息点 ✎ 进入，Enter 重发（Shift+Enter 换行），Esc 放弃 -->
              <div v-if="editingMsgId === run.id" class="user-bubble-edit">
                <textarea
                  class="ube-text"
                  v-model="editingMsgText"
                  rows="2"
                  :ref="focusMsgEdit"
                  @input="autoGrowMsgEdit"
                  @keydown.enter.exact.prevent="commitMsgEdit(run)"
                  @keydown.esc.prevent="cancelMsgEdit"
                ></textarea>
                <div class="ube-actions">
                  <button class="ube-btn primary" type="button" @click="commitMsgEdit(run)">↻ 重发</button>
                  <button class="ube-btn" type="button" @click="cancelMsgEdit">取消</button>
                </div>
              </div>
              <template v-else>
                <div class="user-bubble">{{ run.command }}</div>
                <button
                  v-if="canEditMsg(run)"
                  class="ube-trigger"
                  type="button"
                  aria-label="编辑并重发这条消息"
                  title="编辑并重发"
                  @click="startMsgEdit(run)"
                >✎</button>
              </template>
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
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import AgentRun from './components/AgentRun.vue'
import Composer from './components/Composer.vue'
import ModelPicker from './components/ModelPicker.vue'
import WorkbenchModal from './components/WorkbenchModal.vue'
import ToastHost from './components/ToastHost.vue'
import { useAgent } from './composables/useAgent'
import { showToast } from './composables/useToast'

const API_BASE = '/api/agent'

const examples = ref([])
const llm = ref({ text: '● 连接中…', cls: '' })
const scrollEl = ref(null)
const prefill = ref({ command: '', nonce: 0 })
const railOpen = ref(typeof window === 'undefined' || window.innerWidth > 900)
const activeId = ref('')
const activeMsgId = ref('')
const msgHover = ref('')
/* 聊天区可视高度：横线间距按它算，由 ResizeObserver 维护（窗口缩放、侧栏收起都涵盖） */
const railHeight = ref(0)
const workbenchOpen = ref(false)
const workbenchTab = ref('obs')
const dbProfiles = ref([])
const dbActive = ref('')

/* 左下角「工作台」入口：打开窗口弹窗，默认展示可观测分组 */
function openWorkbench() {
  workbenchTab.value = 'obs'
  workbenchOpen.value = true
}

/* 链路分析记录里点「再次分析」：关窗并把指令预填进输入框（不直接执行） */
function analyzeFromRecord(traceId) {
  workbenchOpen.value = false
  prefill.value = { command: `分析链路 ${traceId}`, nonce: Date.now() }
}

/* 工作台面板请求预填指令：32 位十六进制仍是链路 ID（链路面板旧约定），其余按完整指令处理 */
function onWorkbenchRun(payload) {
  const text = String(payload || '')
  workbenchOpen.value = false
  prefill.value = {
    command: /^[0-9a-f]{32}$/i.test(text) ? `分析链路 ${text}` : text,
    nonce: Date.now(),
  }
}

/* ---------- 最新一条消息：编辑后原地重发覆盖 ---------- */

/* 只有最后一轮、已收尾（完成/停止/回放完）且当前空闲时才可编辑重发 */
function canEditMsg(run) {
  return !busy.value
    && !!run.final?.visible
    && runs.value[runs.value.length - 1] === run
}

const editingMsgId = ref(0)
const editingMsgText = ref('')

function startMsgEdit(run) {
  editingMsgId.value = run.id
  editingMsgText.value = run.command
}

function cancelMsgEdit() {
  editingMsgId.value = 0
  editingMsgText.value = ''
}

/* 挂载即聚焦；高度按内容自适应，避免短消息也占一大块 */
function focusMsgEdit(el) {
  if (!el) return
  nextTick(() => {
    el.focus()
    el.style.height = 'auto'
    el.style.height = el.scrollHeight + 'px'
  })
}

/* 输入过程中随内容增高（上限一屏，超出滚动） */
function autoGrowMsgEdit(e) {
  const el = e.target
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 320) + 'px'
}

async function commitMsgEdit(run) {
  if (editingMsgId.value !== run.id) return
  const text = editingMsgText.value.trim()
  editingMsgId.value = 0
  editingMsgText.value = ''
  // 只要文本非空就重发：内容没改也重新执行一遍（相当于「重新生成」），取消走 Esc/取消按钮
  if (!text) return
  editResend(run, text)
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
  sessionId, loadSessions, newSession, openSession, deleteSession, editResend,
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

/* 消息导航竖条：横线固定间距聚拢居中（不随滚动位置映射），悬停波浪放大。
   做成 computed 而不是命令式重建：悬停只需重算宽度，不必整表重建；
   宽度也在算子里一次算好——原来由模板对每个 mark 回头 findIndex，是 O(n²)。 */
const msgMarks = computed(() => {
  const n = runs.value.length
  const railH = railHeight.value
  if (!n || !railH) return []
  const spacing = n > 1 ? Math.max(4, Math.min(12, (railH - 20) / (n - 1))) : 0
  const startY = Math.max(6, (railH - (n - 1) * spacing) / 2)
  const hoverIdx = msgHover.value === '' ? -1 : runs.value.findIndex((r) => r.id === msgHover.value)
  return runs.value.map((run, i) => {
    const base = run.id === activeMsgId.value ? 10 : 7
    let width = base
    if (hoverIdx >= 0) {
      const d = Math.abs(hoverIdx - i)
      width = d === 0 ? 18 : d === 1 ? 14 : d === 2 ? 11 : base
    }
    return {
      id: run.id,
      top: Math.round(startY + i * spacing),
      cmd: run.command,
      summary: run.final.summary,
      state: run.status?.text || (run.final.visible ? '完成' : '进行中'),
      cls: run.status?.cls || (run.final.visible ? 'is-done' : ''),
      width,
    }
  })
})

/* run id → 消息元素缓存：滚动每帧都要定位，不能每帧对每条消息做一次 querySelector */
let turnEls = new Map()
/* 观察聊天区高度变化，卸载时断开 */
let railObserver = null

function refreshTurnEls() {
  const sc = scrollEl.value
  if (!sc) return
  const next = new Map()
  sc.querySelectorAll('[data-run-id]').forEach((el) => next.set(el.dataset.runId, el))
  turnEls = next
}

/* 当前阅读位置：视口上 40% 内最后一条。容器 rect 每帧只读一次，元素走缓存 */
function updateActive() {
  const sc = scrollEl.value
  if (!sc) return
  if (turnEls.size !== runs.value.length) refreshTurnEls()
  const containerTop = sc.getBoundingClientRect().top
  const base = sc.scrollTop
  const limit = base + sc.clientHeight * 0.4
  let active = null
  for (const run of runs.value) {
    const el = turnEls.get(String(run.id))
    if (!el) continue
    const top = el.getBoundingClientRect().top - containerTop + base
    if (top >= base - 8 && top < limit) active = run.id
  }
  if (active) activeMsgId.value = active
}

/* 滚动高亮：60ms 时间节流。
   原先用 requestAnimationFrame 做「一帧最多一次」，但闩锁只在回调里清零——一旦 rAF 不触发
   （标签页被遮挡、嵌入式 webview 等场景确实会挂起），闩锁就被永久占住，之后所有滚动都不再更新高亮。
   时间节流没有这个隐患；元素已缓存，单次重算只有几次 rect 读取。 */
let lastActiveAt = 0
function onChatScroll() {
  // 滚动会重算横线位置（鼠标不动横线也会移走），悬停态一并失效
  msgHover.value = ''
  const now = Date.now()
  if (now - lastActiveAt < 60) return
  lastActiveAt = now
  updateActive()
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
    refreshTurnEls()
    updateActive()
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

/* ---------- 对话标题就地编辑：✎ 或双击标题进入，Enter/失焦保存，Esc 放弃 ---------- */

const renamingId = ref('')
const renamingTitle = ref('')

function startRename(s) {
  renamingId.value = s.id
  renamingTitle.value = s.title || ''
}

function cancelRename() {
  renamingId.value = ''
  renamingTitle.value = ''
}

/* input 挂载即聚焦；全选放到 nextTick——ref 回调时 v-model 的初值可能尚未 patch，
   过早 select() 选中的是空串，看起来就是没有全选 */
function focusRenameInput(el) {
  if (!el) return
  el.focus()
  nextTick(() => el.select())
}

async function commitRename(s) {
  if (renamingId.value !== s.id) return // 已被 Enter/Esc 收起后冒出来的重复 blur
  const title = renamingTitle.value.trim()
  renamingId.value = ''
  if (!title || title === (s.title || '')) return
  try {
    const res = await fetch(`${API_BASE}/sessions/${encodeURIComponent(s.id)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title }),
    })
    if (!res.ok) throw new Error(String(res.status))
    s.title = title
    showToast('标题已更新')
  } catch {
    showToast('标题保存失败', 'err')
  }
}

function confirmClear() {
  if (window.confirm('确定清空全部对话历史？此操作不可恢复。')) {
    clearHistoryAll()
  }
}

/* 加载更早消息 / 切换对话期间抑制自动跟随，由调用方自行控制滚动位置 */
let suppressAutoScroll = false

/* 新一轮开始、步骤增加或汇总出现时，滚动到底部跟随最新进展；
   加载更早消息属于向上补历史，不触发跟随（由 onLoadEarlier 自行保持滚动位置）。

   监听的是一个单调递增的标量（步数 + 完成标志之和）：原来把整个 runs 映射成字符串再 join，
   每次求值都要新建数组和字符串。递减不会发生，所以求和与逐项比较等价。 */
const progressSignal = computed(() =>
  runs.value.reduce((n, r) => n + (r.steps ? r.steps.length : 0) + (r.final.visible ? 1 : 0), 0)
)

watch(progressSignal, async () => {
  await nextTick()
  refreshTurnEls()
  if (suppressAutoScroll) return
  if (scrollEl.value) scrollEl.value.scrollTop = scrollEl.value.scrollHeight
  updateActive()
})

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
    refreshTurnEls()
  } finally {
    suppressAutoScroll = false
  }
}

/* 每轮对话结束后刷新左侧对话列表（新对话首条消息后出现）。
   只在完成数增加时刷新：切换对话会让计数重置，不该顺带多发一次列表请求。 */
const finishedCount = computed(() => runs.value.reduce((n, r) => n + (r.final.visible ? 1 : 0), 0))

watch(finishedCount, (now, prev) => {
  if (now > prev) loadSessions()
})

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
  // 横线间距依赖聊天区可视高度：观察元素本身，窗口缩放与侧栏收起都涵盖，
  // 也就不需要再挂一个永不摘除的 window resize 监听
  if (scrollEl.value && typeof ResizeObserver !== 'undefined') {
    railHeight.value = scrollEl.value.clientHeight
    railObserver = new ResizeObserver(() => {
      if (scrollEl.value) railHeight.value = scrollEl.value.clientHeight
    })
    railObserver.observe(scrollEl.value)
  }
  loadDbMeta()
  loadLlmInfo()
  // 恢复上次的对话：存在则整段回放，否则落到新对话
  await loadSessions()
  const last = localStorage.getItem('af-session-id') || ''
  if (last && history.value.some((s) => s.id === last)) {
    await openSessionAtBottom(last)
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
  railObserver?.disconnect()
  railObserver = null
})
</script>
