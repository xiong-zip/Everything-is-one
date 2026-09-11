<template>
  <div class="wb-pane ta-pane">
    <div class="hd-head">
      <h3>链路分析</h3>
    </div>

    <div class="ta-stats">
      <div class="ta-stat"><b>{{ stats.total || 0 }}</b><span>分析总数</span></div>
      <div class="ta-stat ta-stat-warn"><b>{{ stats.failed || 0 }}</b><span>失败链路</span></div>
      <div class="ta-stat"><b>{{ stats.withCase || 0 }}</b><span>命中案例</span></div>
      <div class="ta-stat"><b>{{ stats.notFound || 0 }}</b><span>未查到</span></div>
    </div>

    <div class="ta-toolbar">
      <input
        v-model="keyword"
        class="ta-input"
        type="search"
        placeholder="搜索 trace ID / 失败点 / 指纹 / 服务 / 案例号…"
        aria-label="搜索链路分析记录"
      />
      <button class="btn btn-ghost" type="button" :disabled="loading" @click="load()">{{ loading ? '加载中…' : '刷新' }}</button>
      <button v-if="total" class="btn btn-ghost btn-danger" type="button" @click="clearAll">清空</button>
    </div>

    <div class="ta-body">
      <div class="ta-list">
        <div v-if="!items.length" class="hd-empty">
          {{ keyword ? '没有匹配的分析记录' : '还没有链路分析记录' }}<br />
          <small v-if="!keyword">在对话里发一个 trace ID（32 位十六进制）即可自动记录</small>
        </div>
        <div
          v-for="r in items"
          :key="r.id"
          class="ta-item"
          :class="{ active: current && current.id === r.id }"
          role="button"
          tabindex="0"
          @click="select(r)"
          @keydown.enter="select(r)"
        >
          <div class="ta-item-top">
            <span class="ta-badge" :class="badgeCls(r)">{{ badgeText(r) }}</span>
            <code class="ta-tid">{{ short(r.traceId) }}</code>
            <span v-if="r.analyzeCount > 1" class="ta-rep" :title="`分析过 ${r.analyzeCount} 次`">×{{ r.analyzeCount }}</span>
          </div>
          <div class="ta-item-main">{{ r.failurePoint || r.signature || '（未记录失败点）' }}</div>
          <div class="ta-item-sub">
            <span>{{ r.analyzedAt }}</span>
            <span v-if="r.totalMs">· {{ ms(r.totalMs) }}ms</span>
            <span v-if="r.env">· {{ r.env }}</span>
            <span v-if="r.kbCaseId" class="ta-kb">· {{ r.kbCaseId }}</span>
          </div>
        </div>
      </div>

      <div class="ta-detail">
        <div v-if="!current" class="hd-empty">选择左侧一条记录查看分析详情</div>
        <template v-else>
          <div class="ta-detail-head">
            <code class="ta-tid-full">{{ current.traceId }}</code>
            <div class="ta-detail-actions">
              <button class="btn btn-ghost" type="button" @click="copyTrace">复制 ID</button>
              <button class="btn btn-ghost" type="button" @click="$emit('run', current.traceId)">再次分析</button>
              <button class="btn btn-ghost btn-danger" type="button" @click="remove(current)">删除</button>
            </div>
          </div>

          <dl class="ta-meta">
            <div><dt>分析时间</dt><dd>{{ current.analyzedAt }}（第 {{ current.analyzeCount }} 次）</dd></div>
            <div><dt>时间窗</dt><dd>{{ current.timeRange || '—' }}<template v-if="current.env"> · 环境 {{ current.env }}</template></dd></div>
            <div><dt>规模</dt><dd>{{ current.spanCount }} spans · {{ (current.services || []).join('、') || '—' }}</dd></div>
            <div><dt>失败点</dt><dd>{{ current.failurePoint || '—' }}</dd></div>
            <div><dt>指纹</dt><dd class="ta-mono">{{ current.errorClass || '—' }} ｜ {{ current.signature || '—' }}</dd></div>
            <div>
              <dt>知识库</dt>
              <dd>
                <template v-if="current.kbCaseId">{{ current.kbCaseId }}（{{ strengthText(current.kbStrength) }}）</template>
                <template v-else>未命中</template>
              </dd>
            </div>
          </dl>

          <div class="ta-digest-label">分析摘要</div>
          <pre class="ta-digest">{{ current.digest || '（无摘要）' }}</pre>
        </template>
      </div>
    </div>

    <div class="ta-tops">
      <span class="ta-total">共 {{ total }} 条</span>
      <template v-if="topSignatures.length">
        <span class="ta-tops-label">高频故障指纹：</span>
        <span v-for="s in topSignatures" :key="s.name" class="ta-top-chip">
          {{ s.name }} <b>{{ s.count }}</b>
        </span>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'

defineEmits(['run'])

const items = ref([])
const stats = ref({})
const keyword = ref('')
const loading = ref(false)
const total = ref(0)
const current = ref(null)
let timer = null

/* 底部条只展示前 4 个高频指纹，避免挤占空间 */
const topSignatures = computed(() => (stats.value.topSignatures || []).slice(0, 4))

async function load() {
  loading.value = true
  try {
    const qs = new URLSearchParams({ limit: '200' })
    if (keyword.value.trim()) qs.set('keyword', keyword.value.trim())
    const [listRes, statRes] = await Promise.all([
      fetch(`/api/signoz/analyses?${qs}`),
      fetch('/api/signoz/stats'),
    ])
    if (listRes.ok) {
      const data = await listRes.json()
      items.value = data.items || []
      total.value = data.total || 0
      // 选中项按 id 重新定位，避免刷新后详情与列表不一致
      if (current.value) current.value = items.value.find((i) => i.id === current.value.id) || null
    }
    if (statRes.ok) stats.value = await statRes.json()
  } catch { /* 后端不可用时静默降级 */ } finally {
    loading.value = false
  }
}

/* 搜索防抖：300ms 后带关键词重查 */
watch(keyword, () => {
  clearTimeout(timer)
  timer = setTimeout(load, 300)
})

async function select(r) {
  try {
    const res = await fetch(`/api/signoz/analyses/${r.id}`)
    if (res.ok) {
      current.value = await res.json()
      return
    }
  } catch { /* 退回列表里的字段 */ }
  current.value = r
}

async function remove(r) {
  if (!window.confirm(`删除这条分析记录？\n${r.traceId}`)) return
  try { await fetch(`/api/signoz/analyses/${r.id}`, { method: 'DELETE' }) } catch { /* 静默 */ }
  if (current.value && current.value.id === r.id) current.value = null
  load()
}

async function clearAll() {
  if (!window.confirm('确定清空全部链路分析记录？此操作不可恢复（不影响故障案例知识库）。')) return
  try { await fetch('/api/signoz/analyses', { method: 'DELETE' }) } catch { /* 静默 */ }
  current.value = null
  load()
}

async function copyTrace() {
  try { await navigator.clipboard.writeText(current.value.traceId) } catch { /* 静默 */ }
}

function short(id) {
  return id && id.length > 16 ? id.slice(0, 16) + '…' : id
}

/* 后端存的是原始 double，显示前收敛到两位小数，避免 58.629451ms 这种噪声 */
function ms(v) {
  return Math.round(Number(v) * 100) / 100
}

function badgeCls(r) {
  if (!r.found) return 'nf'
  return r.failed ? 'bad' : 'ok'
}

function badgeText(r) {
  if (!r.found) return '未查到'
  return r.failed ? '失败' : '成功'
}

function strengthText(s) {
  return { STRONG: '强命中', MEDIUM: '中命中', WEAK: '弱线索', NONE: '未命中' }[s] || s
}

onMounted(load)
</script>
