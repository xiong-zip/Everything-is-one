<template>
  <div :class="embedded ? 'wb-pane' : 'drawer-mask'" @click.self="!embedded && $emit('close')">
    <aside :class="['history-drawer', 'stats-drawer', { 'wb-embed': embedded }]" role="dialog" aria-label="效能统计">
      <div class="hd-head">
        <h3>效能热力图</h3>
        <div class="hd-actions">
          <button class="hd-close" type="button" aria-label="关闭" @click="$emit('close')">✕</button>
        </div>
      </div>

      <div class="hm-body">
        <div v-if="loading" class="hd-empty">统计中…</div>
        <div v-else-if="!data.enabled" class="hd-empty">
          未配置 GitLab 账户<br /><small>在「工作台 → GitLab 账户」添加 Access Token 后即可查看</small>
        </div>
        <template v-else-if="data.error">
          <div class="hd-empty">{{ data.error }}</div>
        </template>
        <template v-else>
          <div class="hm-summary">
            <div class="hm-stat"><b>{{ data.total }}</b><span>总提交</span></div>
            <div class="hm-stat"><b>{{ data.activeDays }}</b><span>活跃天</span></div>
            <div class="hm-stat"><b>{{ data.bestStreak }}</b><span>最长连续</span></div>
          </div>

          <!-- 热力图：列 = 周，行 = 周一~周日 -->
          <div class="hm-scroll">
            <div class="hm-months">
              <span v-for="(m, i) in monthLabels" :key="i" class="hm-month" :style="{ gridColumn: m.col + ' / span 1' }">{{ m.label }}</span>
            </div>
            <div class="hm-grid-wrap">
              <div class="hm-weekdays">
                <span>一</span><span></span><span>三</span><span></span><span>五</span><span></span><span></span>
              </div>
              <div class="hm-grid">
                <div v-for="(week, wi) in weeks" :key="wi" class="hm-week">
                  <div
                    v-for="(cell, di) in week"
                    :key="di"
                    class="hm-cell"
                    :class="'lv' + cell.level"
                    :title="cell.date ? cell.date + ' · ' + cell.count + ' 次提交' : ''"
                  ></div>
                </div>
              </div>
            </div>
          </div>

          <div class="hm-legend">
            <span>少</span>
            <span class="hm-cell lv0"></span><span class="hm-cell lv1"></span>
            <span class="hm-cell lv2"></span><span class="hm-cell lv3"></span><span class="hm-cell lv4"></span>
            <span>多</span>
            <span class="hm-updated">数据更新于 {{ new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}（缓存 10 分钟）</span>
          </div>
        </template>
      </div>
    </aside>
  </div>
</template>

<script setup>
/* embedded=true：作为工作台窗口内的面板渲染，去掉遮罩与固定侧栏宽度 */
defineProps({ embedded: { type: Boolean, default: false } })

import { computed, onMounted, ref } from 'vue'

defineEmits(['close'])

const data = ref({})
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await fetch('/api/stats/heatmap')
    if (res.ok) data.value = await res.json()
  } catch { /* 静默 */ } finally {
    loading.value = false
  }
}

/* 组装周列网格：按周一为行首对齐，缺位补 null */
const weeks = computed(() => {
  const counts = data.value.counts || []
  if (!counts.length || !data.value.startDate) return []
  const byDate = {}
  counts.forEach((c) => { byDate[c.date] = c.count })
  const max = Math.max(1, data.value.maxCount || 1)
  const start = new Date(data.value.startDate + 'T00:00:00')
  const end = new Date(data.value.endDate + 'T00:00:00')
  // 对齐到所在周的周一
  const dayMs = 86400000
  const startDow = (start.getDay() + 6) % 7 // 周一=0
  const gridStart = new Date(start.getTime() - startDow * dayMs)
  const weeks = []
  let cur = gridStart
  while (cur <= end || weeks[weeks.length - 1]?.some(Boolean)) {
    const week = []
    for (let i = 0; i < 7; i++) {
      const d = new Date(cur.getTime() + i * dayMs)
      const key = fmt(d)
      if (d < start || d > end) {
        week.push({ date: null, count: 0, level: -1 })
      } else {
        const count = byDate[key] || 0
        week.push({ date: key, count, level: levelOf(count, max) })
      }
    }
    weeks.push(week)
    cur = new Date(cur.getTime() + 7 * dayMs)
    if (cur > end && weeks.length > 60) break
  }
  return weeks
})

const monthLabels = computed(() => {
  const labels = []
  let lastMonth = -1
  weeks.value.forEach((week, i) => {
    const first = week.find((c) => c.date)
    if (!first) return
    const m = new Date(first.date + 'T00:00:00').getMonth()
    if (m !== lastMonth) {
      labels.push({ col: i + 1, label: (m + 1) + '月' })
      lastMonth = m
    }
  })
  return labels
})

function levelOf(count, max) {
  if (count <= 0) return 0
  const ratio = count / max
  if (ratio <= 0.25) return 1
  if (ratio <= 0.5) return 2
  if (ratio <= 0.75) return 3
  return 4
}

function fmt(d) {
  const p = (n) => String(n).padStart(2, '0')
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
}

onMounted(load)
</script>
