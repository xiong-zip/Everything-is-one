<template>
  <div class="wb-pane pane-col">
    <div class="hd-head">
      <h3>记忆</h3>
      <span class="hd-actions pane-count">共 {{ items.length }} 条</span>
    </div>

    <div class="pane-body">
      <p class="gl-hint">
        跨会话的<b>长期记忆</b>：只存用户偏好与既定事实（常用环境、署名、习惯说法），不存一次性任务数据。
        每次任务的规划与生成都会自动参考；对话里直接说「<b>记住 XXX</b>」「<b>忘记 XXX</b>」也可以增删。
      </p>

      <!-- 提取设置 -->
      <div class="pane-card">
        <div class="pane-card-title">提取方式</div>
        <label class="mem-switch">
          <input v-model="autoExtract" type="checkbox" @change="toggleAuto" />
          <span>任务完成后自动提取值得记住的偏好（需 LLM，也可关闭只用显式「记住」指令）</span>
        </label>
      </div>

      <!-- 新增记忆 -->
      <div class="pane-card">
        <div class="pane-card-title">新增记忆</div>
        <div class="mem-add-row">
          <input
            v-model="draft"
            class="db-input mem-add-input"
            placeholder="如：日报署名用小熊；排查默认用 dev 集群"
            @keydown.enter.prevent="add"
          />
          <button class="btn btn-primary" type="button" :disabled="saving || !draft.trim()" @click="add">
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>
        <div v-if="message" class="td-message" :class="message.cls">{{ message.text }}</div>
      </div>

      <!-- 记忆列表 -->
      <div>
        <div v-if="loading" class="hd-empty">加载中…</div>
        <div v-else-if="!items.length" class="hd-empty">
          还没有长期记忆<br /><small>说「记住 XXX」或在此添加后，之后所有任务都会参考</small>
        </div>
        <div v-else class="pane-list">
          <div v-for="m in items" :key="m.id" class="db-item">
            <div class="db-item-main">
              <div class="mem-content">{{ m.content }}</div>
              <div class="db-item-dbs">{{ m.createdAt }} 记下</div>
            </div>
            <div class="db-item-ops">
              <button class="db-op del" type="button" @click="remove(m)">删除</button>
            </div>
          </div>
          <div class="mem-footer">
            <button class="btn btn-ghost" type="button" @click="clear">清空全部记忆</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { api, toastError } from '../api/client'

const items = ref([])
const loading = ref(false)
const saving = ref(false)
const autoExtract = ref(true)
const draft = ref('')
const message = ref(null)

async function load() {
  loading.value = true
  try {
    const data = await api.get('/api/memory')
    items.value = data.items || []
    autoExtract.value = data.autoExtract !== false
  } catch (err) {
    toastError(err)
  } finally {
    loading.value = false
  }
}

async function add() {
  if (saving.value || !draft.value.trim()) return
  saving.value = true
  message.value = null
  try {
    const res = await fetch('/api/memory', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: draft.value.trim() }),
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || '保存失败')
    message.value = { text: '已保存', cls: 'ok' }
    draft.value = ''
    await load()
  } catch (err) {
    message.value = { text: err.message || '保存失败', cls: 'err' }
  } finally {
    saving.value = false
  }
}

async function remove(m) {
  items.value = items.value.filter((x) => x.id !== m.id)
  try {
    await fetch(`/api/memory/${m.id}`, { method: 'DELETE' })
  } catch { /* 静默 */ }
  await load()
}

async function clear() {
  if (!window.confirm('确定清空全部长期记忆？')) return
  try {
    await fetch('/api/memory', { method: 'DELETE' })
  } catch { /* 静默 */ }
  await load()
}

async function toggleAuto() {
  try {
    await fetch('/api/memory/auto', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled: autoExtract.value }),
    })
  } catch { /* 静默 */ }
}

onMounted(load)
</script>
