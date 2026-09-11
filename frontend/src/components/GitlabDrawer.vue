<template>
  <div :class="embedded ? 'wb-pane' : 'drawer-mask'" @click.self="!embedded && $emit('close')">
    <aside :class="['history-drawer', 'db-drawer', { 'wb-embed': embedded }]" role="dialog" aria-label="GitLab 账户">
      <div class="hd-head">
        <h3>GitLab 账户</h3>
        <div class="hd-actions">
          <button class="hd-close" type="button" aria-label="关闭" @click="$emit('close')">✕</button>
        </div>
      </div>

      <!-- 账户表单 -->
      <div class="db-form">
        <div class="db-form-title">{{ editing ? '编辑账户' : '新增账户' }}</div>
        <div class="db-grid">
          <label class="db-field">
            <span>名称</span>
            <input v-model="form.name" class="db-input" placeholder="如 我的账号" :disabled="!!editing" />
          </label>
          <label class="db-field">
            <span>Access Token{{ editing ? '（留空沿用原 Token）' : '' }}</span>
            <input v-model="form.token" class="db-input" type="password" placeholder="glpat-xxxxxxxxxxxxxxxx" />
          </label>
        </div>
        <div class="db-form-actions">
          <button class="btn btn-primary" type="button" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
          <button v-if="editing" class="btn btn-ghost" type="button" @click="resetForm">取消编辑</button>
        </div>
        <div v-if="message" class="td-message" :class="message.cls">{{ message.text }}</div>
        <p class="gl-hint">
          Token 在 GitLab「用户设置 → 访问令牌」创建，勾选 <b>api</b> 权限即可（含读写）。
          保存后立即生效，无需重启；查询提交、日报周报都按「使用中」账户的身份执行。
        </p>
      </div>

      <!-- 账户列表 -->
      <div class="hd-list">
        <div v-if="loading" class="hd-empty">加载中…</div>
        <div v-else-if="!accounts.length" class="hd-empty">
          还没有配置 GitLab 账户<br /><small>添加 Access Token 后即可查询提交记录、生成日报周报</small>
        </div>
        <div v-for="a in accounts" :key="a.name" class="db-item" :class="{ active: a.name === activeName }">
          <div class="db-item-main">
            <div class="db-item-head">
              <code>{{ a.name }}</code>
              <span v-if="a.name === activeName" class="db-active-badge">★ 使用中</span>
              <span class="db-host">{{ a.tokenMasked }}</span>
            </div>
            <div class="db-item-dbs">添加于 {{ a.createdAt }}</div>
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
    </aside>
  </div>
</template>

<script setup>
/* embedded=true：作为工作台窗口内的面板渲染，去掉遮罩与固定侧栏宽度 */
defineProps({ embedded: { type: Boolean, default: false } })

import { onMounted, ref } from 'vue'

const accounts = ref([])
const loading = ref(false)
const saving = ref(false)
const testing = ref('')
const editing = ref(null)
const message = ref(null)
const activeName = ref('')
const emptyForm = { name: '', token: '' }
const form = ref({ ...emptyForm })

async function load() {
  loading.value = true
  try {
    const res = await fetch('/api/gitlab/accounts')
    if (res.ok) {
      const data = await res.json()
      accounts.value = data.accounts || []
      activeName.value = data.active || ''
    }
  } catch { /* 静默 */ } finally {
    loading.value = false
  }
}

/* 设为默认账户：查询提交、日报周报都按该账户身份执行 */
async function makeActive(name) {
  activeName.value = name
  try {
    await fetch('/api/gitlab/accounts/active', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name }),
    })
  } catch { /* 静默 */ }
  message.value = { text: `已切换到 ${name}，后续查询与日报周报按该账户身份执行`, cls: 'ok' }
}

async function save() {
  if (saving.value) return
  saving.value = true
  message.value = null
  try {
    const res = await fetch('/api/gitlab/accounts', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...form.value }),
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || '保存失败')
    message.value = { text: '账户已保存，立即生效（无需重启）', cls: 'ok' }
    resetForm()
    await load()
  } catch (err) {
    message.value = { text: err.message || '保存失败', cls: 'err' }
  } finally {
    saving.value = false
  }
}

function edit(a) {
  editing.value = a.name
  form.value = { name: a.name, token: '' }
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
  await load()
}

onMounted(load)
</script>
