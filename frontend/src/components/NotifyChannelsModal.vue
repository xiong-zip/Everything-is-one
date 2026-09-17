<template>
  <div class="nm-mask" @click.self="$emit('close')">
    <section class="nm-window" role="dialog" aria-modal="true" aria-label="推送通道">
      <div class="hd-head">
        <h3>推送通道</h3>
        <div class="hd-actions">
          <span class="pane-count">已选 {{ selected.length }} / {{ channels.length }}</span>
          <button class="hd-close" type="button" aria-label="关闭" @click="$emit('close')">✕</button>
        </div>
      </div>

      <div class="nm-body">
        <p class="gl-hint">
          勾选要接收推送的群机器人，<b>可多选</b>——晨报与告警会同时发到所有勾选的通道。
          Webhook 地址含密钥，列表里已脱敏显示；编辑时留空表示不修改原地址。
        </p>

        <div v-if="usingEnvFallback" class="td-message ok">
          当前还没有在这里配置通道，推送使用的是服务端 .env 里的 AGENTFLOW_NOTIFY_WEBHOOK。新增一条通道后即以这里为准。
        </div>

        <!-- 通道列表（勾选 = 多选推送目标） -->
        <div v-if="loading" class="hd-empty">加载中…</div>
        <div v-else-if="!channels.length" class="hd-empty">
          还没有配置任何通道<br /><small>在下面添加一个企微 / 钉钉群机器人的 Webhook 地址</small>
        </div>
        <div v-else class="pane-list">
          <div v-for="c in channels" :key="c.name" class="nc-item" :class="{ on: isSelected(c.name) }">
            <label class="nc-check">
              <input
                type="checkbox"
                :checked="isSelected(c.name)"
                :disabled="savingSel"
                @change="toggleSelect(c, $event)"
              />
            </label>
            <div class="nc-main">
              <div class="nc-head">
                <span class="nc-name">{{ c.name }}</span>
                <span class="nc-type" :class="c.type">{{ c.typeName }}</span>
                <span v-if="!isSelected(c.name)" class="nc-off-badge">不推送</span>
              </div>
              <div class="nc-url">{{ c.url }}</div>
            </div>
            <div class="db-item-ops">
              <button class="db-op" type="button" :disabled="testing === c.name" @click="test(c)">
                {{ testing === c.name ? '发送中…' : '测试' }}
              </button>
              <button class="db-op" type="button" @click="edit(c)">编辑</button>
              <button class="db-op del" type="button" @click="remove(c)">删除</button>
            </div>
          </div>
        </div>

        <!-- 新增 / 编辑 -->
        <div class="pane-card">
          <div class="pane-card-title">{{ editing ? '编辑通道' : '新增通道' }}</div>
          <div class="db-grid">
            <label class="db-field">
              <span>通道名</span>
              <input v-model="form.name" class="db-input" placeholder="如 研发告警群" />
            </label>
            <label class="db-field">
              <span>类型</span>
              <select v-model="form.type" class="db-input">
                <option value="wecom">企业微信</option>
                <option value="dingtalk">钉钉</option>
              </select>
            </label>
            <label class="db-field db-w2">
              <span>Webhook 地址</span>
              <input
                v-model="form.url"
                class="db-input"
                :placeholder="editing ? '留空表示不修改原地址' : 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...'"
              />
            </label>
          </div>
          <div class="db-form-actions">
            <button class="btn btn-primary" type="button" :disabled="saving" @click="save">
              {{ saving ? '保存中…' : '保存' }}
            </button>
            <button v-if="editing" class="btn btn-ghost" type="button" @click="resetForm">取消编辑</button>
          </div>
          <div v-if="message" class="td-message" :class="message.cls">{{ message.text }}</div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'

const emit = defineEmits(['close', 'changed'])

const channels = ref([])
const selected = ref([])
const usingEnvFallback = ref(false)
const loading = ref(false)
const saving = ref(false)
const savingSel = ref(false)
const testing = ref('')
const editing = ref(false)
const message = ref(null)
const form = ref({ name: '', type: 'wecom', url: '', originalName: '' })

function isSelected(name) {
  return selected.value.includes(name)
}

async function load() {
  loading.value = true
  try {
    const res = await fetch('/api/notify/channels')
    if (res.ok) {
      const data = await res.json()
      channels.value = data.channels || []
      selected.value = data.selected || []
      usingEnvFallback.value = data.usingEnvFallback === true
    }
  } catch { /* 静默 */ } finally {
    loading.value = false
  }
}

// 多选：整体提交选中集合，避免并发勾选互相覆盖
async function toggleSelect(c, ev) {
  const next = ev.target.checked
  const names = next
    ? [...selected.value, c.name]
    : selected.value.filter((n) => n !== c.name)
  savingSel.value = true
  try {
    const res = await fetch('/api/notify/selected', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ names }),
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || '保存失败')
    selected.value = data.selected || names
    emit('changed')
  } catch (err) {
    ev.target.checked = !next // 保存失败回滚勾选，避免界面与服务端不一致
    message.value = { text: err.message || '保存失败', cls: 'err' }
  } finally {
    savingSel.value = false
  }
}

function edit(c) {
  editing.value = true
  message.value = null
  // 地址是脱敏值，不回填——留空即表示不修改
  form.value = { name: c.name, type: c.type || 'wecom', url: '', originalName: c.name }
}

function resetForm() {
  editing.value = false
  message.value = null
  form.value = { name: '', type: 'wecom', url: '', originalName: '' }
}

async function save() {
  if (saving.value) return
  saving.value = true
  message.value = null
  try {
    const res = await fetch('/api/notify/channels', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(form.value),
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || '保存失败')
    message.value = { text: '已保存', cls: 'ok' }
    resetForm()
    await load()
    emit('changed')
  } catch (err) {
    message.value = { text: err.message || '保存失败', cls: 'err' }
  } finally {
    saving.value = false
  }
}

async function remove(c) {
  if (!window.confirm(`确定删除通道「${c.name}」？`)) return
  try {
    await fetch(`/api/notify/channels/${encodeURIComponent(c.name)}`, { method: 'DELETE' })
  } catch { /* 静默 */ }
  if (editing.value && form.value.originalName === c.name) resetForm()
  await load()
  emit('changed')
}

async function test(c) {
  testing.value = c.name
  message.value = null
  try {
    const res = await fetch(`/api/notify/channels/${encodeURIComponent(c.name)}/test`, { method: 'POST' })
    const data = await res.json()
    message.value = { text: `${c.name}：${data.message || (data.ok ? '已发送' : '失败')}`, cls: data.ok ? 'ok' : 'err' }
  } catch {
    message.value = { text: '请求失败，请检查后端服务', cls: 'err' }
  } finally {
    testing.value = ''
  }
}

// 捕获阶段拦下 Esc，避免同时把外层工作台一起关掉
function onKeydown(e) {
  if (e.key === 'Escape') {
    e.stopImmediatePropagation()
    emit('close')
  }
}

onMounted(() => {
  load()
  window.addEventListener('keydown', onKeydown, true)
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown, true))
</script>
