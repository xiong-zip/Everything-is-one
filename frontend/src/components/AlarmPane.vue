<template>
  <div ref="rootEl" class="wb-pane pane-col">
    <div class="hd-head">
      <h3>告警值守</h3>
      <span class="hd-actions pane-count">共 {{ total }} 条记录</span>
    </div>

    <div class="pane-body">
      <p class="gl-hint">
        把监控平台的告警 webhook 指向下面这个地址，之后<b>告警一到就自动排查</b>：
        有 trace ID 就分析链路并关联代码变更，只有服务名就查 Pod 状态与异常事件，
        结论直接推送到已勾选的推送通道，并 @ 值班人。
      </p>

      <!-- 接入地址 -->
      <div class="pane-card">
        <div class="pane-card-title">接入地址</div>
        <div class="al-url-row">
          <code class="al-url">{{ hookUrl }}</code>
          <button class="btn btn-ghost" type="button" @click="copyHook">复制</button>
        </div>
        <div class="al-meta-row">
          <span class="al-chip" :class="status.enabled ? 'ok' : 'off'">
            {{ status.enabled ? '值守已开启' : '值守已关闭' }}
          </span>
          <span class="al-chip" :class="status.tokenRequired ? 'ok' : 'warn'">
            {{ status.tokenRequired ? '需要令牌' : '未设令牌' }}
          </span>
          <span class="al-chip" :class="status.notifyConfigured ? 'ok' : 'warn'">
            {{ status.notifyConfigured ? '推送通道已配' : '未配推送通道' }}
          </span>
        </div>
        <p class="al-note">
          令牌用请求头 <code>X-AgentFlow-Token</code> 或 <code>?token=</code> 传
          （后者给只能填 URL 的平台用）；可在 .env 用 <code>AGENTFLOW_ALARM_TOKEN</code> 配置。
          去重窗口 {{ status.dedupSeconds || 0 }} 秒，单次排查超时 {{ Math.round((status.timeoutMs || 0) / 1000) }} 秒，
          同时排查 {{ status.maxConcurrent || 0 }} 条（进行中 {{ status.running || 0 }}，排队 {{ status.queued || 0 }}）。
        </p>
        <p v-if="status.commandTemplate" class="al-note">
          自定义排查指令模板：<code>{{ status.commandTemplate }}</code>
        </p>
      </div>

      <!-- 概况 -->
      <div class="al-stats">
        <div class="al-stat"><b>{{ status.stats?.total || 0 }}</b><span>收到告警</span></div>
        <div class="al-stat"><b>{{ stateCount('done') }}</b><span>已排查</span></div>
        <div class="al-stat"><b>{{ stateCount('skipped') }}</b><span>已跳过</span></div>
        <div class="al-stat al-stat-warn"><b>{{ status.stats?.pushed || 0 }}</b><span>已推送</span></div>
      </div>

      <!-- 模拟验证 -->
      <div class="pane-card">
        <div class="pane-card-title">模拟一条告警</div>
        <p class="al-note">
          真实告警不好造，用它验证整条链路（受理 → 排查 → 推送）是否通。
          填了 trace ID 会走链路分析，只填服务名则走服务排查。
        </p>
        <div class="al-sim-row">
          <input v-model="sim.alertName" class="db-input" placeholder="告警名，如 支付服务错误率升高" />
          <input v-model="sim.service" class="db-input" placeholder="服务名，如 pay-service" />
          <input v-model="sim.traceId" class="db-input" placeholder="trace ID（可选，32 位十六进制）" />
          <button class="btn btn-primary" type="button" :disabled="simulating" @click="simulate">
            {{ simulating ? '已提交…' : '模拟' }}
          </button>
        </div>
        <div v-if="message" class="td-message" :class="message.cls">{{ message.text }}</div>
      </div>

      <!-- 记录列表 -->
      <div>
        <div v-if="loading" class="hd-empty">加载中…</div>
        <div v-else-if="!items.length" class="hd-empty">
          还没有收到过告警<br /><small>把上面的地址配到监控平台，或点「模拟」先验证一遍</small>
        </div>
        <div v-else class="pane-list">
          <div v-for="r in items" :key="r.id" class="db-item">
            <div class="db-item-main">
              <div class="al-item-top">
                <span class="al-badge" :class="r.state">{{ stateText(r.state) }}</span>
                <b class="al-item-name">{{ r.alertName || '（未命名告警）' }}</b>
                <span v-if="r.pushed" class="al-badge ok">已推送</span>
              </div>
              <div class="al-item-sub">
                <span>{{ r.receivedAt }}</span>
                <span v-if="r.service">· {{ r.service }}</span>
                <span v-if="r.severity">· {{ r.severity }}</span>
                <span v-if="r.traceId">· <code class="al-mono">{{ short(r.traceId) }}</code></span>
                <span v-if="r.elapsedMs">· 耗时 {{ Math.round(r.elapsedMs / 1000) }}s</span>
                <span v-if="r.source">· 来源 {{ r.source }}</span>
              </div>
              <div v-if="r.summary" class="al-item-summary" @click="open(r)">
                {{ r.summary }}
                <span class="al-more">{{ expanded === r.id ? '收起' : '展开结论' }}</span>
              </div>
              <pre v-if="expanded === r.id" class="al-output">{{ r.output || '（无完整结论）' }}</pre>
            </div>
            <div class="db-item-ops">
              <button class="db-op del" type="button" @click="remove(r)">删除</button>
            </div>
          </div>
          <div class="al-footer">
            <button class="btn btn-ghost" type="button" :disabled="loading" @click="load()">刷新</button>
            <button class="btn btn-ghost btn-danger" type="button" @click="clearAll">清空全部记录</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref } from 'vue'
import { api, toastError } from '../api/client'

const items = ref([])
const total = ref(0)
const status = ref({})
const loading = ref(false)
const simulating = ref(false)
const expanded = ref(null)
const message = ref(null)
const sim = ref({ alertName: '', service: '', traceId: '' })
let timer = null

const hookUrl = computed(() => `${window.location.origin}/api/hooks/alarm`)

// 后端返回 byState 计数，面板上只关心几个固定状态
function stateCount(state) {
  return status.value?.stats?.byState?.[state] || 0
}

const STATE_TEXT = { running: '排查中', done: '已完成', skipped: '已跳过', error: '失败' }

function stateText(s) {
  return STATE_TEXT[s] || s
}

function short(id) {
  return id && id.length > 16 ? `${id.slice(0, 16)}…` : id
}

function open(r) {
  expanded.value = expanded.value === r.id ? null : r.id
}

async function load() {
  loading.value = true
  try {
    const [data, statusData] = await Promise.all([
      api.get('/api/hooks/alarm/records?limit=100'),
      api.get('/api/hooks/alarm/status'),
    ])
    items.value = data.records || []
    total.value = data.total || 0
    status.value = statusData
  } catch (err) {
    toastError(err)
  } finally {
    loading.value = false
  }
}

async function simulate() {
  simulating.value = true
  message.value = null
  try {
    const res = await fetch('/api/hooks/alarm/simulate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        alertName: sim.value.alertName.trim(),
        service: sim.value.service.trim(),
        traceId: sim.value.traceId.trim(),
      }),
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || '提交失败')
    message.value = data.action === 'investigating'
      ? { text: `已受理，正在后台排查（记录 ${data.recordId}）。完成后会推送到已勾选的通道。`, cls: 'ok' }
      : { text: data.reason || '未触发排查', cls: 'err' }
    await load()
  } catch (err) {
    message.value = { text: err.message || '提交失败', cls: 'err' }
  } finally {
    simulating.value = false
  }
}

async function remove(r) {
  if (!window.confirm(`删除这条值守记录？\n${r.alertName || r.id}`)) return
  try { await fetch(`/api/hooks/alarm/records/${r.id}`, { method: 'DELETE' }) } catch { /* 静默 */ }
  if (expanded.value === r.id) expanded.value = null
  await load()
}

async function clearAll() {
  if (!window.confirm('确定清空全部告警值守记录？此操作不可恢复。')) return
  try { await fetch('/api/hooks/alarm/records', { method: 'DELETE' }) } catch { /* 静默 */ }
  expanded.value = null
  await load()
}

async function copyHook() {
  try { await navigator.clipboard.writeText(hookUrl.value) } catch { /* 静默 */ }
}

/* 排查在后台跑，面板可见时定期刷新才能看到状态从「排查中」变「已完成」。
   停轮询不依赖 onDeactivated——实测嵌套 KeepAlive（工作台缓存分组、分组内缓存子面板）
   下激活/停用钩子并不可靠，每次挂载都可能泄漏一个定时器，多个叠加后请求频率成倍上涨。
   改为触发前判断根节点是否还在文档里：被 KeepAlive 摘除的实例 isConnected 必为 false，
   无论是内层切子面板还是外层切菜单，天然停轮询，泄漏的旧定时器也会因同一检查而失效。 */
const rootEl = ref(null)

function startPolling() {
  clearInterval(timer)
  timer = setInterval(() => {
    if (rootEl.value && rootEl.value.isConnected) load()
  }, 15000)
}

onMounted(() => {
  load()
  startPolling()
})
onActivated(() => {
  load()
  startPolling()
})
onDeactivated(() => clearInterval(timer))
onBeforeUnmount(() => clearInterval(timer))
</script>
