<template>
  <div :class="embedded ? 'wb-pane' : 'drawer-mask'" @click.self="!embedded && $emit('close')">
    <aside :class="['history-drawer', 'schedule-drawer', { 'wb-embed': embedded }]" role="dialog" aria-label="定时任务">
      <div class="hd-head">
        <h3>定时任务</h3>
        <div class="hd-actions">
          <span class="pane-count">共 {{ tasks.length }} 个</span>
          <button class="hd-close" type="button" aria-label="关闭" @click="$emit('close')">✕</button>
        </div>
      </div>

      <!-- 全局配置：总开关 / 执行时间 / 推送通道 / 推送方式 -->
      <div class="sd-conf">
        <div class="sd-row">
          <span class="sd-key">总开关</span>
          <label class="sd-switch">
            <input
              type="checkbox"
              :checked="enabled"
              :disabled="toggling"
              @change="toggleEnabled($event)"
            />
            <span :class="enabled ? 'on' : 'off'">{{ enabled ? '已开启' : '未开启' }}</span>
          </label>
        </div>
        <div class="sd-row">
          <span class="sd-key">执行时间</span>
          <span class="sd-val mono">{{ status.cron }}</span>
        </div>
        <button class="sd-row sd-row-btn" type="button" @click="channelsOpen = true">
          <span class="sd-key">推送通道</span>
          <span class="sd-val" :class="channelSummary.cls">
            {{ channelSummary.text }}
            <span class="sd-row-arrow" aria-hidden="true">›</span>
          </span>
        </button>
        <div class="sd-row">
          <span class="sd-key">推送方式</span>
          <select
            v-model="notifyMode"
            class="db-input sd-mode"
            :disabled="savingMode"
            @change="saveMode"
          >
            <option v-for="m in notifyModes" :key="m.key" :value="m.key">{{ m.label }}</option>
          </select>
        </div>
        <p class="sd-hint">
          总开关关闭后所有任务都不再<b>自动</b>执行（单个任务仍可「试跑」）。开关、推送通道、推送方式都即改即生效，改完不用重启。<br />
          执行时间由服务端 .env 决定（AGENTFLOW_MORNING_CRON），<b>全局统一</b>：到点后所有启用的任务依次执行。修改需重启。<br />
          <span class="sd-mode-hint">{{ modeHint }}</span>
        </p>
      </div>

      <!-- 试跑结果 -->
      <div v-if="runResult" class="sd-result" :class="runResult.status">
        <div class="sd-result-head">
          {{ runResult.commandLabel || '试跑' }} ·
          {{ statusText(runResult.status) }}
          <template v-if="runResult.pushed"> · 已推送</template>
          <template v-else-if="status.webhookConfigured"> · 未推送</template>
        </div>
        <pre class="sd-result-body">{{ runResult.output || runResult.summary || '（无输出）' }}</pre>
      </div>

      <!-- 任务列表：一个任务 = 一条指令，执行记录按它归类 -->
      <div class="hd-list">
        <div class="sd-section-title">
          <span>定时任务（按指令归类）</span>
          <button class="sd-add-btn" type="button" @click="toggleAdd">{{ adding ? '取消' : '+ 新增' }}</button>
        </div>

        <div v-if="adding" class="pane-card">
          <textarea
            v-model="newCommand"
            class="sd-cmd-input"
            rows="2"
            placeholder="如：根据我的 GitLab 提交记录生成昨天的工作日报"
          ></textarea>
          <label class="sd-switch sd-inline-switch">
            <input v-model="newEnabled" type="checkbox" />
            <span>加入自动执行（关闭则只保存、不参与定时）</span>
          </label>
          <div class="db-form-actions">
            <button class="btn btn-primary" type="button" :disabled="savingTask || !newCommand.trim()" @click="addTask">
              {{ savingTask ? '保存中…' : '添加任务' }}
            </button>
          </div>
          <div v-if="taskMessage" class="td-message" :class="taskMessage.cls">{{ taskMessage.text }}</div>
        </div>

        <div v-if="loading" class="hd-empty">加载中…</div>
        <div v-else-if="!tasks.length" class="hd-empty">
          还没有定时任务<br /><small>点右上角「+ 新增」加一条指令，到点会自动执行并推送</small>
        </div>

        <div v-for="t in tasks" :key="t.id" class="sd-task">
          <div class="sd-task-head">
            <label class="sd-switch">
              <input
                type="checkbox"
                :checked="t.enabled"
                :disabled="togglingTask === t.id"
                @change="toggleTask(t, $event)"
              />
              <span :class="t.enabled ? 'on' : 'off'">{{ t.enabled ? '自动执行' : '已停用' }}</span>
            </label>
            <div class="sd-task-ops">
              <button class="db-op" type="button" :disabled="runningTask === t.id" @click="runTask(t)">
                {{ runningTask === t.id ? '执行中…' : '试跑' }}
              </button>
              <button class="db-op" type="button" @click="startEdit(t)">编辑</button>
              <button class="db-op del" type="button" @click="removeTask(t)">删除</button>
            </div>
          </div>

          <div v-if="editingId === t.id" class="sd-task-edit">
            <textarea v-model="editCommand" class="sd-cmd-input" rows="2"></textarea>
            <div class="db-form-actions">
              <button class="btn btn-primary" type="button" :disabled="savingTask" @click="saveEdit(t)">保存</button>
              <button class="btn btn-ghost" type="button" @click="editingId = null">取消</button>
            </div>
            <div v-if="taskMessage" class="td-message" :class="taskMessage.cls">{{ taskMessage.text }}</div>
          </div>
          <div v-else class="sd-task-cmd">{{ t.command }}</div>

          <button class="sd-task-expand" type="button" @click="toggleExpand(t.id)">
            <span class="sd-caret">{{ expanded.includes(t.id) ? '▾' : '▸' }}</span>
            最近执行 {{ t.runCount }} 次
            <template v-if="t.lastRunAt"> · 最近 {{ t.lastRunAt }}</template>
            <template v-if="t.lastStatus"> · {{ statusText(t.lastStatus) }}</template>
            <template v-if="t.lastPushed"> · 已推送</template>
          </button>

          <div v-if="expanded.includes(t.id)" class="sd-runs">
            <div v-if="!t.runs.length" class="hd-empty">这个任务还没有执行记录</div>
            <div v-for="(r, i) in t.runs" :key="i" class="sd-item">
              <div class="hd-sub">
                <span class="hd-status" :class="r.status">{{ statusText(r.status) }}</span>
                <span class="sd-trigger">{{ r.trigger === 'cron' ? '定时' : '手动' }}</span>
                <span v-if="r.pushed" class="sd-pushed">已推送</span>
                <span class="hd-time">{{ r.createdAt }}</span>
                <button class="db-op del sd-run-del" type="button" @click="removeRun(t, r)">删除</button>
              </div>
              <div class="sd-item-summary">{{ r.summary || r.output || '（无摘要）' }}</div>
            </div>
          </div>
        </div>
      </div>
    </aside>

    <!-- 推送通道管理弹窗（多选） -->
    <NotifyChannelsModal v-if="channelsOpen" @close="channelsOpen = false" @changed="onChannelsChanged" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import NotifyChannelsModal from './NotifyChannelsModal.vue'

defineProps({ embedded: { type: Boolean, default: false } })

defineEmits(['close'])

const STATUS_TEXT = { done: '成功', error: '异常', cancelled: '取消', running: '进行中', timeout: '超时' }

const status = ref({})
const tasks = ref([])
const notifyInfo = ref(null)
const channelsOpen = ref(false)
const loading = ref(false)
const toggling = ref(false)
const enabled = ref(false)
const runResult = ref(null)
const notifyMode = ref('text')
const notifyModes = ref([{ key: 'text', label: '纯文本' }])
const savingMode = ref(false)

const adding = ref(false)
const newCommand = ref('')
const newEnabled = ref(true)
const expanded = ref([])
const editingId = ref(null)
const editCommand = ref('')
const savingTask = ref(false)
const togglingTask = ref(0)
const runningTask = ref(0)
const taskMessage = ref(null)

function statusText(s) {
  return STATUS_TEXT[s] || s || ''
}

// 各形态的上限/取舍讲清楚，免得以为「推送怎么少了半截」
const modeHint = computed(() => {
  if (notifyMode.value === 'markdown') {
    return 'Markdown：上限 4096 字节（约 1300 中文字），可读性更好；企微下带不上手机号 @。'
  }
  if (notifyMode.value === 'file') {
    return '摘要 + 附件：群里发几条摘要 + 完整 .md 附件，不受字节上限（附件上限 20MB）。钉钉通道不支持文件，会自动降级为 Markdown。'
  }
  return '纯文本：上限 2048 字节（约 680 中文字），超出部分会被企微静默截断。告警固定用这一档，因为 @ 值班人只有文本可靠。'
})

// 推送通道行的摘要：一眼看出「有没有通道、勾了几个」
const channelSummary = computed(() => {
  const info = notifyInfo.value
  if (!info) return { text: '配置…', cls: '' }
  const total = (info.channels || []).length
  const selected = (info.selected || []).length
  if (total === 0) return { text: '未配置（点此添加）', cls: 'off' }
  if (info.usingEnvFallback) return { text: '来自 .env', cls: 'on' }
  if (selected === 0) return { text: `未勾选（共 ${total} 个）`, cls: 'off' }
  return { text: `已选 ${selected} / ${total}`, cls: 'on' }
})

async function load() {
  loading.value = true
  try {
    const res = await fetch('/api/schedule/status')
    if (res.ok) {
      status.value = await res.json()
      enabled.value = status.value.enabled === true
      tasks.value = status.value.tasks || []
      notifyMode.value = status.value.notifyMode || 'text'
      if ((status.value.notifyModes || []).length) notifyModes.value = status.value.notifyModes
    }
  } catch { /* 静默 */ } finally {
    loading.value = false
  }
  await loadNotify()
}

async function loadNotify() {
  try {
    const res = await fetch('/api/notify/channels')
    if (res.ok) notifyInfo.value = await res.json()
  } catch { /* 静默 */ }
}

async function onChannelsChanged() {
  await loadNotify()
  await load()
}

async function post(url, body, method = 'POST') {
  const res = await fetch(url, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const data = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(data.error || '操作失败')
  return data
}

async function toggleEnabled(ev) {
  const next = ev.target.checked
  toggling.value = true
  try {
    await post('/api/schedule/enabled', { enabled: next })
    await load()
  } catch {
    ev.target.checked = enabled.value // 失败回滚显示，避免界面与服务端不一致
  } finally {
    toggling.value = false
  }
}

async function saveMode() {
  savingMode.value = true
  try {
    await post('/api/schedule/mode', { mode: notifyMode.value })
  } catch { /* 静默 */ }
  await load() // 以服务端为准重读
  savingMode.value = false
}

function toggleAdd() {
  adding.value = !adding.value
  taskMessage.value = null
  if (!adding.value) newCommand.value = ''
}

async function addTask() {
  if (savingTask.value || !newCommand.value.trim()) return
  savingTask.value = true
  taskMessage.value = null
  try {
    await post('/api/schedule/tasks', { command: newCommand.value.trim(), enabled: newEnabled.value })
    newCommand.value = ''
    adding.value = false
    await load()
  } catch (err) {
    taskMessage.value = { text: err.message, cls: 'err' }
  } finally {
    savingTask.value = false
  }
}

function startEdit(t) {
  editingId.value = t.id
  editCommand.value = t.command
  taskMessage.value = null
}

async function saveEdit(t) {
  if (savingTask.value) return
  savingTask.value = true
  taskMessage.value = null
  try {
    await post(`/api/schedule/tasks/${t.id}`, { command: editCommand.value.trim() }, 'PUT')
    editingId.value = null
    await load()
  } catch (err) {
    taskMessage.value = { text: err.message, cls: 'err' }
  } finally {
    savingTask.value = false
  }
}

async function toggleTask(t, ev) {
  const next = ev.target.checked
  togglingTask.value = t.id
  try {
    await post(`/api/schedule/tasks/${t.id}/enabled`, { enabled: next }, 'PUT')
    await load()
  } catch {
    ev.target.checked = t.enabled
  } finally {
    togglingTask.value = 0
  }
}

async function removeTask(t) {
  if (!window.confirm(`删除任务「${short(t.command)}」？\n它的 ${t.runCount} 条执行记录也会一起删除。`)) return
  try {
    await post(`/api/schedule/tasks/${t.id}`, undefined, 'DELETE')
    if (editingId.value === t.id) editingId.value = null
    await load()
  } catch (err) {
    taskMessage.value = { text: err.message, cls: 'err' }
  }
}

async function removeRun(t, r) {
  try {
    await post(`/api/schedule/runs?taskId=${encodeURIComponent(r.taskId || '')}&createdAt=${encodeURIComponent(r.createdAt || '')}`,
      undefined, 'DELETE')
    await load()
  } catch (err) {
    taskMessage.value = { text: err.message, cls: 'err' }
  }
}

async function runTask(t) {
  if (runningTask.value) return
  runningTask.value = t.id
  runResult.value = null
  try {
    const data = await post('/api/schedule/run-now', { taskId: t.id })
    runResult.value = { ...data, commandLabel: `试跑「${short(t.command)}」` }
    await load()
  } catch (err) {
    runResult.value = { status: 'error', output: err.message, commandLabel: '试跑失败' }
  } finally {
    runningTask.value = 0
  }
}

function toggleExpand(id) {
  expanded.value = expanded.value.includes(id)
    ? expanded.value.filter((x) => x !== id)
    : [...expanded.value, id]
}

function short(s) {
  const v = s || ''
  return v.length > 24 ? v.slice(0, 24) + '…' : v
}

onMounted(load)
</script>
