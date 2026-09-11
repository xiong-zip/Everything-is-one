<template>
  <div :class="embedded ? 'wb-pane' : 'drawer-mask'" @click.self="!embedded && $emit('close')">
    <aside :class="['history-drawer', 'db-drawer', { 'wb-embed': embedded }]" role="dialog" aria-label="数据库连接">
      <div class="hd-head">
        <h3>数据库连接</h3>
        <div class="hd-actions">
          <button class="hd-close" type="button" aria-label="关闭" @click="$emit('close')">✕</button>
        </div>
      </div>

      <!-- 连接表单 -->
      <div class="db-form">
        <div class="db-form-title">{{ editing ? '编辑连接' : '新增连接' }}</div>
        <div class="db-grid">
          <label class="db-field">
            <span>连接名{{ editing ? '（改名会同步更新默认连接）' : '' }}</span>
            <input v-model="form.name" class="db-input" placeholder="如 DM_TEST（字母/数字/下划线）" />
          </label>
          <label class="db-field">
            <span>类型</span>
            <select v-model="form.type" class="db-input">
              <option value="dameng">达梦</option>
              <option value="mysql">MySQL</option>
              <option value="postgresql">PostgreSQL</option>
              <option value="oracle">Oracle</option>
            </select>
          </label>
          <label class="db-field db-w2">
            <span>主机</span>
            <input v-model="form.host" class="db-input" placeholder="192.168.x.x" />
          </label>
          <label class="db-field">
            <span>端口</span>
            <input v-model="form.port" class="db-input" type="number" placeholder="5253" />
          </label>
          <label class="db-field db-w2">
            <span>数据库（多个用逗号分隔）</span>
            <input v-model="form.databases" class="db-input" placeholder="ZOE_BASIC_SYS 或 db1,db2" />
          </label>
          <label class="db-field">
            <span>用户名</span>
            <input v-model="form.username" class="db-input" placeholder="SYSDBA" />
          </label>
          <label class="db-field">
            <span>密码{{ editing ? '（留空沿用原密码）' : '' }}</span>
            <input v-model="form.password" class="db-input" type="password" placeholder="••••••" />
          </label>
          <label class="db-field">
            <span>Schema（可选）</span>
            <input v-model="form.schema" class="db-input" placeholder="Oracle/PG/达梦 适用" />
          </label>
          <label class="db-field">
            <span>环境（可选，可自己填）</span>
            <input
              v-model="form.environment"
              class="db-input"
              list="db-env-options"
              placeholder="开发 / 测试 / 生产…"
            />
            <datalist id="db-env-options">
              <option value="开发"></option>
              <option value="测试"></option>
              <option value="预发"></option>
              <option value="生产"></option>
            </datalist>
          </label>
        </div>
        <div class="db-form-actions">
          <button class="btn btn-primary" type="button" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
          <button v-if="editing" class="btn btn-ghost" type="button" @click="resetForm">取消编辑</button>
        </div>
        <div v-if="message" class="td-message" :class="message.cls">{{ message.text }}</div>
      </div>

      <!-- 连接列表 -->
      <div class="hd-list">
        <div v-if="loading" class="hd-empty">加载中…</div>
        <div v-else-if="!profiles.length" class="hd-empty">
          还没有配置连接<br /><small>添加后即可在对话中说「查 DM_TEST 里 SYS_USER 表结构」</small>
        </div>
        <div v-for="p in profiles" :key="p.name" class="db-item" :class="{ active: p.name === activeName }">
          <div class="db-item-main">
            <div class="db-item-head">
              <code>{{ p.name }}</code>
              <span class="db-tag" :class="p.type">{{ typeName(p.type) }}</span>
              <span v-if="p.environment" class="db-env-tag">{{ p.environment }}</span>
              <span v-if="p.name === activeName" class="db-active-badge">★ 默认</span>
              <span class="db-host">{{ p.host }}:{{ p.port }} · {{ p.username }}</span>
            </div>
            <div class="db-item-dbs">库：{{ p.databases }}<template v-if="p.schema"> · Schema：{{ p.schema }}</template></div>
          </div>
          <div class="db-item-ops">
            <button v-if="p.name !== activeName" class="db-op" type="button" @click="makeActive(p.name)">设为默认</button>
            <button class="db-op" type="button" :disabled="testing === p.name" @click="test(p.name)">
              {{ testing === p.name ? '测试中…' : '测试' }}
            </button>
            <button class="db-op" type="button" @click="edit(p)">编辑</button>
            <button class="db-op del" type="button" @click="remove(p.name)">删除</button>
          </div>
        </div>
      </div>
    </aside>
  </div>
</template>

<script setup>
/* embedded=true：作为工作台窗口内的面板渲染，去掉遮罩与固定侧栏宽度 */
defineProps({ embedded: { type: Boolean, default: false } })

import { onMounted, ref } from 'vue'
import { showToast } from '../composables/useToast'

const profiles = ref([])
const loading = ref(false)
const saving = ref(false)
const testing = ref('')
const editing = ref(null)
const message = ref(null)
const activeName = ref('')
const emptyForm = { name: '', type: 'dameng', host: '', port: 5253, databases: '', username: '', password: '', schema: '', environment: '' }
const form = ref({ ...emptyForm })

const emit = defineEmits(['close', 'active-changed'])

async function load() {
  loading.value = true
  try {
    const [res, act] = await Promise.all([
      fetch('/api/dbprofiles'),
      fetch('/api/dbprofiles/active'),
    ])
    if (res.ok) profiles.value = await res.json()
    if (act.ok) activeName.value = (await act.json()).name || ''
  } catch { /* 静默 */ } finally {
    loading.value = false
  }
}

/* 一键设为默认连接：之后聊天不用点名，db.inspect 自动用它 */
async function makeActive(name) {
  activeName.value = name
  emit('active-changed', name)
  try {
    await fetch('/api/dbprofiles/active', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name }),
    })
  } catch { /* 静默 */ }
  message.value = { text: `已把 ${name} 设为默认连接，对话中直接说需求即可`, cls: 'ok' }
}

async function save() {
  if (saving.value) return
  saving.value = true
  message.value = null
  try {
    const res = await fetch('/api/dbprofiles', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        ...form.value,
        port: Number(form.value.port),
        // 编辑态带上原名：服务端据此改名（含默认连接指针跟随），名字没变则等价于普通覆盖
        originalName: editing.value || '',
      }),
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || '保存失败')
    message.value = { text: editing.value ? '连接已更新' : '连接已保存，可直接在对话中使用', cls: 'ok' }
    resetForm()
    await load()
  } catch (err) {
    message.value = { text: err.message || '保存失败', cls: 'err' }
  } finally {
    saving.value = false
  }
}

function edit(p) {
  editing.value = p.name
  form.value = {
    name: p.name, type: p.type, host: p.host, port: p.port,
    databases: p.databases, username: p.username, password: '', schema: p.schema || '',
    environment: p.environment || '',
  }
}

function resetForm() {
  editing.value = null
  form.value = { ...emptyForm }
}

async function test(name) {
  testing.value = name
  try {
    const res = await fetch(`/api/dbprofiles/${encodeURIComponent(name)}/test`, { method: 'POST' })
    const data = await res.json()
    if (data.ok) {
      showToast(`${name}：${data.message}`, 'ok')
    } else {
      showToast(`${name} 连接失败：${causeOf(data.message)}`, 'err')
    }
  } catch {
    showToast(`${name} 测试请求失败，请检查后端是否可用`, 'err')
  } finally {
    testing.value = ''
  }
}

async function remove(name) {
  if (!window.confirm(`确定删除连接 ${name}？`)) return
  profiles.value = profiles.value.filter((p) => p.name !== name)
  try {
    await fetch(`/api/dbprofiles/${encodeURIComponent(name)}`, { method: 'DELETE' })
  } catch { /* 静默 */ }
}

function typeName(t) {
  return { dameng: '达梦', mysql: 'MySQL', postgresql: 'PostgreSQL', oracle: 'Oracle' }[t] || t
}

/**
 * 后端回传的是扫描器原始输出，失败时通常是一整段 Python traceback，
 * 真正的根因在末尾（如 DMException: 网络通信异常）。提示里只带这一行，细节看后端日志。
 */
function causeOf(text) {
  const lines = String(text || '').split('\n').map((l) => l.trim()).filter(Boolean)
  for (let i = lines.length - 1; i >= 0; i--) {
    // 跳过 traceback 的文件行，取最后一条真正的异常行
    if (/(Exception|Error)/.test(lines[i]) && !lines[i].startsWith('File "')) {
      return lines[i].replace(/^连接失败：/, '').slice(0, 200)
    }
  }
  return (lines[lines.length - 1] || '未知原因').replace(/^连接失败：/, '').slice(0, 200)
}

onMounted(load)
</script>
