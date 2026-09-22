<template>
  <div class="wb-pane pane-col">
    <div class="hd-head">
      <h3>GitLab 效能</h3>
      <span class="hd-actions pane-count">
        {{ activeName ? '当前账户：' + activeName : '未配置账户' }}
      </span>
    </div>

    <div class="pane-body">
      <!-- ============ 账户管理 ============ -->
      <div class="pane-card">
        <div class="pane-card-title">
          {{ editing ? '编辑账户' : '新增账户' }}
          <span class="pane-count">提交 / 日报周报 / 热力图都按「使用中」账户的身份执行</span>
        </div>
        <div class="db-grid">
          <label class="db-field">
            <span>名称</span>
            <input v-model="form.name" class="db-input" placeholder="如 我的账号" :disabled="!!editing" />
          </label>
          <label class="db-field">
            <span>Access Token{{ editing ? '（留空沿用原 Token）' : '' }}</span>
            <input v-model="form.token" class="db-input" type="password" placeholder="glpat-xxxxxxxxxxxxxxxx" />
          </label>
          <label class="db-field db-w2">
            <span>提交作者别名（可选，逗号分隔）</span>
            <input v-model="form.authors" class="db-input" placeholder="本机 git 配置的作者名或邮箱，如 xiaoxiong, me@qq.com" />
          </label>
        </div>
        <div class="db-form-actions">
          <button class="btn btn-primary" type="button" :disabled="saving" @click="save">
            {{ saving ? '保存中…' : (editing ? '保存修改' : '添加账户') }}
          </button>
          <button v-if="editing" class="btn btn-ghost" type="button" @click="resetForm">取消编辑</button>
        </div>
        <div v-if="message" class="td-message" :class="message.cls">{{ message.text }}</div>
        <p class="gl-hint">
          Token 在 GitLab「用户设置 → 访问令牌」创建，勾选 <b>api</b> 权限即可（含读写）。保存后立即生效，无需重启。<br />
          本机 git 配置的作者名/邮箱若与 GitLab 档案不一致（很常见），日报周报会整批漏掉这些提交——
          在「提交作者别名」里补上 git 实际使用的名字或邮箱即可认回来。
        </p>
      </div>

      <div v-if="loadingAccounts" class="hd-empty">加载中…</div>
      <div v-else-if="!accounts.length" class="hd-empty">
        还没有配置 GitLab 账户<br /><small>添加 Access Token 后即可查询提交记录、生成日报周报、查看效能热力图</small>
      </div>
      <div v-else class="pane-list">
        <div v-for="a in accounts" :key="a.name" class="db-item" :class="{ active: a.name === activeName }">
          <div class="db-item-main">
            <div class="db-item-head">
              <code>{{ a.name }}</code>
              <span v-if="a.name === activeName" class="db-active-badge">★ 使用中</span>
              <span class="db-host">{{ a.tokenMasked }}</span>
            </div>
            <div class="db-item-dbs">
              添加于 {{ a.createdAt }}
              <template v-if="a.authors && a.authors.length">
                · 识别作者 {{ a.authors.join('、') }}
              </template>
            </div>
          </div>
          <div class="db-item-ops">
            <button v-if="a.name !== activeName" class="db-op" type="button" @click="makeActive(a.name)">设为默认</button>
            <button class="db-op" type="button" :disabled="testing === a.name" @click="test(a.name)">
              {{ testing === a.name ? '测试中…' : '测试' }}
            </button>
            <button class="db-op" type="button" @click="edit(a)">编辑</button>
            <button class="db-op del" type="button" @click="remove(a.name)">删除</button>
          </div>
        </div>
      </div>

      <!-- ============ 效能热力图 ============ -->
      <div>
        <div v-if="loadingStats" class="hd-empty">统计中…</div>
        <div v-else-if="!stats.enabled" class="hd-empty">
          上方添加 GitLab 账户后，这里展示近半年的贡献热力图
        </div>
        <div v-else-if="stats.error" class="hd-empty">{{ stats.error }}</div>
        <template v-else>
          <div class="hm-summary">
            <div class="hm-stat"><b>{{ stats.total }}</b><span>总贡献</span></div>
            <div class="hm-stat"><b>{{ stats.activeDays }}</b><span>活跃天</span></div>
            <div class="hm-stat"><b>{{ stats.bestStreak }}</b><span>最长连续</span></div>
            <span class="hm-updated pane-count">
              数据更新于 {{ new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}（缓存 10 分钟）
            </span>
          </div>

          <!-- 热力图：单一网格，列 = 周（首列为星期标签），行 = 周一~周日，首行为月份 -->
          <div class="hm-scroll">
            <div class="hm-grid" :style="{ gridTemplateColumns: '18px repeat(' + weeks.length + ', minmax(0, 1fr))' }">
              <span
                v-for="(m, i) in monthLabels"
                :key="'m' + i"
                class="hm-month"
                :style="{ gridColumn: m.col, gridRow: 1 }"
              >{{ m.label }}</span>
              <span
                v-for="(wd, ri) in ['一', '', '三', '', '五', '', '日']"
                :key="'w' + ri"
                class="hm-wd"
                :style="{ gridColumn: 1, gridRow: ri + 2 }"
              >{{ wd }}</span>
              <div
                v-for="cell in flatCells"
                :key="cell.date"
                class="hm-cell"
                :class="'lv' + cell.level"
                :style="{ gridColumn: cell.col + 2, gridRow: cell.row + 2 }"
                :title="cell.date + ' · ' + cell.count + ' 个贡献'"
              ></div>
            </div>
          </div>

          <div class="hm-legend">
            <span>少</span>
            <span class="hm-cell lv0"></span><span class="hm-cell lv1"></span>
            <span class="hm-cell lv2"></span><span class="hm-cell lv3"></span><span class="hm-cell lv4"></span>
            <span>多</span>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
/* GitLab 账户 + 效能热力图 合一面板：账户增删切换后热力图随之刷新（服务端按 token 指纹缓存 10 分钟） */
import { computed, onMounted, ref } from 'vue'

const accounts = ref([])
const activeName = ref('')
const loadingAccounts = ref(false)
const saving = ref(false)
const testing = ref('')
const editing = ref(null)
const message = ref(null)
const emptyForm = { name: '', token: '', authors: '' }
const form = ref({ ...emptyForm })

/* 别名输入支持中英文逗号/分号/空白分隔 */
function parseAuthors(text) {
  return String(text || '')
    .split(/[,，;；、\s]+/)
    .map((s) => s.trim())
    .filter(Boolean)
    .slice(0, 8)
}

const stats = ref({})
const loadingStats = ref(false)

/* ---------- 账户 ---------- */

async function loadAccounts() {
  loadingAccounts.value = true
  try {
    const res = await fetch('/api/gitlab/accounts')
    if (res.ok) {
      const data = await res.json()
      accounts.value = data.accounts || []
      activeName.value = data.active || ''
    }
  } catch { /* 静默 */ } finally {
    loadingAccounts.value = false
  }
}

/* 设为默认账户：提交、日报周报与热力图都切换到该账户口径 */
async function makeActive(name) {
  const prev = activeName.value
  activeName.value = name
  try {
    const res = await fetch('/api/gitlab/accounts/active', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name }),
    })
    // fetch 只在网络层失败时抛异常，HTTP 4xx/5xx 同样算切换失败——
    // 这里不检查的话，界面显示「已切换」但后端还是旧账户（热力图也就不会变）
    if (!res.ok) throw new Error('HTTP ' + res.status)
  } catch (err) {
    activeName.value = prev
    message.value = { text: `切换到 ${name} 失败（${err.message || '后端不可用'}），仍使用 ${prev || '原账户'}`, cls: 'err' }
    return
  }
  message.value = { text: `已切换到 ${name}，热力图与后续查询按该账户身份执行`, cls: 'ok' }
  loadStats()
}

async function save() {
  if (saving.value) return
  saving.value = true
  message.value = null
  try {
    const res = await fetch('/api/gitlab/accounts', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: form.value.name, token: form.value.token, authors: parseAuthors(form.value.authors) }),
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || '保存失败')
    message.value = { text: '账户已保存，立即生效（无需重启）', cls: 'ok' }
    resetForm()
    await loadAccounts()
    loadStats()
  } catch (err) {
    message.value = { text: err.message || '保存失败', cls: 'err' }
  } finally {
    saving.value = false
  }
}

function edit(a) {
  editing.value = a.name
  form.value = { name: a.name, token: '', authors: (a.authors || []).join(', ') }
}

function resetForm() {
  editing.value = null
  form.value = { ...emptyForm }
}

async function test(name) {
  testing.value = name
  message.value = null
  try {
    const res = await fetch(`/api/gitlab/accounts/${encodeURIComponent(name)}/test`, { method: 'POST' })
    const data = await res.json()
    message.value = { text: `${name}：${data.message || data.error}`, cls: data.ok ? 'ok' : 'err' }
  } catch {
    message.value = { text: '测试请求失败', cls: 'err' }
  } finally {
    testing.value = ''
  }
}

async function remove(name) {
  if (!window.confirm(`确定删除账户 ${name}？`)) return
  accounts.value = accounts.value.filter((a) => a.name !== name)
  try {
    await fetch(`/api/gitlab/accounts/${encodeURIComponent(name)}`, { method: 'DELETE' })
  } catch { /* 静默 */ }
  await loadAccounts()
  loadStats()
}

/* ---------- 热力图 ---------- */

/* 热力图请求序号：切换账户后首次抓取要 6~16 秒，等待期间再点一次切换的话，
   后完成的旧账户响应会覆盖新账户的数据，界面停在「A 选中 + B 的图」——只应用最新一次请求的结果 */
let statsReqSeq = 0

async function loadStats() {
  const seq = ++statsReqSeq
  loadingStats.value = true
  try {
    // 起点对齐到周一，保证热力图第一列是完整一周，避免首列只在部分行出现
    const dow = (new Date().getDay() + 6) % 7 // 周一=0 … 周日=6
    const res = await fetch('/api/stats/heatmap?days=' + (365 + dow))
    // 已有更新的切换发生：本次响应作废，loading 也交给新请求收尾
    if (!res.ok || seq !== statsReqSeq) return
    stats.value = await res.json()
  } catch { /* 静默 */ } finally {
    if (seq === statsReqSeq) loadingStats.value = false
  }
}

/* 组装周列网格：按周一为行首对齐，缺位补 null */
const weeks = computed(() => {
  const counts = stats.value.counts || []
  if (!counts.length || !stats.value.startDate) return []
  const byDate = {}
  counts.forEach((c) => { byDate[c.date] = c.count })
  const max = Math.max(1, stats.value.maxCount || 1)
  const start = new Date(stats.value.startDate + 'T00:00:00')
  const end = new Date(stats.value.endDate + 'T00:00:00')
  const dayMs = 86400000
  const startDow = (start.getDay() + 6) % 7 // 周一=0
  const gridStart = new Date(start.getTime() - startDow * dayMs)
  const weeks = []
  let cur = gridStart
  while (cur <= end || weeks[weeks.length - 1]?.some((c) => c && c.date)) {
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
  }
  return weeks
})

/* 展平为 (col=周, row=星期) 坐标，跳过补位空格 */
const flatCells = computed(() => {
  const out = []
  weeks.value.forEach((week, wi) => {
    week.forEach((cell, di) => {
      if (cell.date) out.push({ ...cell, col: wi, row: di })
    })
  })
  return out
})

const monthLabels = computed(() => {
  const labels = []
  let lastMonth = -1
  weeks.value.forEach((week, i) => {
    const first = week.find((c) => c.date)
    if (!first) return
    const m = new Date(first.date + 'T00:00:00').getMonth()
    if (m !== lastMonth) {
      labels.push({ col: i + 2, label: (m + 1) + '月' })
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

onMounted(() => {
  loadAccounts()
  loadStats()
})
</script>
