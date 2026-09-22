<template>
  <div class="wb-pane pane-col">
    <div class="hd-head">
      <h3>效能与成本</h3>
      <span class="hd-actions pane-count">累计 {{ (data.records || 0).toLocaleString() }} 次调用</span>
    </div>

    <div class="pane-body">
      <p class="gl-hint">
        Agent 自己每次调用模型的<b>用途、token、耗时与成败</b>都记录在案。
        用它回答「钱和时间花在哪个环节」「哪个定时任务最贵」「最近有没有变慢」——
        这也是后续做任何成本或延迟优化的依据。
      </p>

      <!-- 时间窗 -->
      <div class="us-range">
        <button
          v-for="r in RANGES"
          :key="r.hours"
          class="us-range-btn"
          :class="{ active: hours === r.hours }"
          type="button"
          @click="pick(r.hours)"
        >
          {{ r.label }}
        </button>
        <button class="btn btn-ghost us-refresh" type="button" :disabled="loading" @click="load">
          {{ loading ? '加载中…' : '刷新' }}
        </button>
      </div>

      <!-- 总量 -->
      <div class="us-stats">
        <div class="us-stat"><b>{{ (t.calls || 0).toLocaleString() }}</b><span>调用次数</span></div>
        <div class="us-stat"><b>{{ fmtTokens(t.tokens) }}</b><span>总 token</span></div>
        <div class="us-stat"><b>{{ t.avgMs || 0 }}<i>ms</i></b><span>平均耗时</span></div>
        <div class="us-stat us-stat-warn"><b>{{ t.failures || 0 }}</b><span>失败次数</span></div>
        <div v-if="data.priceConfigured" class="us-stat us-stat-cost">
          <b>¥{{ data.cost }}</b><span>折算金额</span>
        </div>
      </div>
      <p class="us-note">
        输入 {{ (t.promptTokens || 0).toLocaleString() }} · 输出 {{ (t.completionTokens || 0).toLocaleString() }} token，
        累计耗时 {{ fmtMs(t.totalMs) }}，单次最长 {{ fmtMs(t.maxMs) }}。
        <template v-if="t.estimatedCalls">
          其中 <b>{{ t.estimatedCalls }}</b> 次为估算值（流式响应未返回用量，按文本长度估算）——
          想要精确值可在 .env 设置 <code>AGENTFLOW_LLM_STREAM_USAGE=true</code>（需服务端支持）。
        </template>
        <template v-if="!data.priceConfigured">
          金额未显示：配置 <code>AGENTFLOW_LLM_PRICE_INPUT</code> / <code>AGENTFLOW_LLM_PRICE_OUTPUT</code>（元/百万 token）后按单价折算。
        </template>
      </p>

      <div class="us-grid">
        <!-- 阶段分布 -->
        <div class="pane-card">
          <div class="pane-card-title">按阶段<span class="pane-count">回答「花在哪个环节」</span></div>
          <div v-if="!data.byPurpose?.length" class="hd-empty">该时间窗内没有调用</div>
          <div v-else class="us-bars">
            <div v-for="row in data.byPurpose" :key="row.key" class="us-bar-row">
              <div class="us-bar-head">
                <span class="us-bar-label">{{ row.label }}</span>
                <span class="us-bar-value">{{ fmtTokens(row.tokens) }} · {{ row.calls }} 次 · {{ row.avgMs }}ms</span>
              </div>
              <div class="us-bar-track">
                <div class="us-bar-fill" :style="{ width: barWidth(row.tokens, maxPurposeTokens) }"></div>
              </div>
              <div v-if="row.failures" class="us-bar-fail">失败 {{ row.failures }} 次</div>
            </div>
          </div>
        </div>

        <!-- 模型分布 -->
        <div class="pane-card">
          <div class="pane-card-title">按模型<span class="pane-count">换模型前后各花了多少</span></div>
          <div v-if="!data.byModel?.length" class="hd-empty">该时间窗内没有调用</div>
          <div v-else class="us-bars">
            <div v-for="row in data.byModel" :key="row.key" class="us-bar-row">
              <div class="us-bar-head">
                <span class="us-bar-label mono">{{ row.key || '（未知）' }}</span>
                <span class="us-bar-value">{{ fmtTokens(row.tokens) }} · {{ row.calls }} 次</span>
              </div>
              <div class="us-bar-track">
                <div class="us-bar-fill alt" :style="{ width: barWidth(row.tokens, maxModelTokens) }"></div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 任务花费 -->
      <div class="pane-card">
        <div class="pane-card-title">任务花费排行<span class="pane-count">哪个任务最贵</span></div>
        <div v-if="!data.topTasks?.length" class="hd-empty">该时间窗内没有归属到任务的调用</div>
        <div v-else class="us-table">
          <div class="us-tr us-th">
            <span>任务</span><span>调用</span><span>token</span><span>耗时</span>
          </div>
          <div v-for="row in data.topTasks" :key="row.taskId" class="us-tr">
            <code class="al-mono">{{ short(row.taskId) }}</code>
            <span>{{ row.calls }}</span>
            <span>{{ (row.tokens || 0).toLocaleString() }}</span>
            <span>{{ fmtMs(row.totalMs) }}</span>
          </div>
        </div>
      </div>

      <!-- 最近调用 -->
      <div class="pane-card">
        <div class="pane-card-title">最近调用<span class="pane-count">核对异常与慢调用</span></div>
        <div v-if="!data.recent?.length" class="hd-empty">还没有调用记录</div>
        <div v-else class="us-table us-table-wide">
          <div class="us-tr us-th">
            <span>时间</span><span>阶段</span><span>模型</span><span>输入</span><span>输出</span><span>耗时</span><span>结果</span>
          </div>
          <div v-for="(row, i) in data.recent" :key="i" class="us-tr" :class="{ bad: !row.ok }">
            <span class="dim">{{ row.createdAt }}</span>
            <span>{{ label(row.purpose) }}<i v-if="row.stream" class="us-tag">流式</i></span>
            <span class="mono dim">{{ row.model }}</span>
            <span>{{ row.promptTokens }}<i v-if="row.estimated" class="us-tag est">估</i></span>
            <span>{{ row.completionTokens }}</span>
            <span>{{ fmtMs(row.elapsedMs) }}</span>
            <span :title="row.error || ''">{{ row.ok ? '成功' : '失败' }}</span>
          </div>
        </div>
      </div>

      <div class="us-footer">
        <button class="btn btn-ghost btn-danger" type="button" @click="clearAll">清空全部埋点</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { api, toastError } from '../api/client'

const RANGES = [
  { hours: 1, label: '近 1 小时' },
  { hours: 24, label: '近 24 小时' },
  { hours: 24 * 7, label: '近 7 天' },
  { hours: 24 * 30, label: '近 30 天' },
]

const data = ref({})
const hours = ref(24)
const loading = ref(false)

const t = computed(() => data.value.totals || {})
const maxPurposeTokens = computed(() => Math.max(1, ...(data.value.byPurpose || []).map((r) => r.tokens || 0)))
const maxModelTokens = computed(() => Math.max(1, ...(data.value.byModel || []).map((r) => r.tokens || 0)))

function label(purpose) {
  return data.value.purposeLabels?.[purpose] || purpose || '未标注'
}

function short(id) {
  return id && id.length > 12 ? `${id.slice(0, 12)}…` : id
}

/* 条形长度按当前分组内的最大值归一，低于 2% 也留一点宽度，否则小项看起来像没有数据 */
function barWidth(value, max) {
  const pct = Math.round(((value || 0) / max) * 100)
  return `${Math.max(pct, 2)}%`
}

function fmtTokens(v) {
  const n = Number(v || 0)
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}M`
  if (n >= 10_000) return `${(n / 1000).toFixed(1)}k`
  return n.toLocaleString()
}

function fmtMs(v) {
  const n = Number(v || 0)
  if (n >= 60_000) return `${(n / 60_000).toFixed(1)}min`
  if (n >= 1000) return `${(n / 1000).toFixed(1)}s`
  return `${Math.round(n)}ms`
}

async function load() {
  loading.value = true
  try {
    data.value = await api.get(`/api/llm/usage?hours=${hours.value}`)
  } catch (err) {
    toastError(err)
  } finally {
    loading.value = false
  }
}

function pick(h) {
  hours.value = h
  load()
}

async function clearAll() {
  if (!window.confirm('清空全部 LLM 调用埋点？此操作不可恢复（不影响任务历史与分析记录）。')) return
  try { await fetch('/api/llm/usage', { method: 'DELETE' }) } catch { /* 静默 */ }
  await load()
}

onMounted(load)
</script>
