<template>
  <div class="wb-pane pane-col">
    <div class="hd-head">
      <h3>服务映射</h3>
      <span class="hd-actions pane-count">共 {{ items.length }} 条</span>
    </div>

    <div class="pane-body">
      <p class="gl-hint">
        维护 <b>SigNoz / K8s 服务名 → GitLab 项目</b> 的对应关系。变更关联按
        本面板配置 → 按服务名自动搜索推断 的顺序解析；推断歧义时对话里反问，选中后自动保存到这里。
      </p>

      <div v-if="!gitlabConfigured" class="td-message err">
        尚未配置 GitLab 账户：无法搜索 / 校验项目，请先到「GitLab 账户」面板配置。
      </div>

      <!-- 新增映射 -->
      <div class="pane-card">
        <div class="pane-card-title">新增映射</div>
        <div class="db-grid">
          <label class="db-field">
            <span>服务名（如 pay-service）</span>
            <input v-model="form.service" class="db-input" placeholder="pay-service" />
          </label>
          <label class="db-field">
            <span>GitLab 项目（关键词搜索后点选）</span>
            <input
              v-model="form.keyword"
              class="db-input"
              placeholder="项目名关键词，回车搜索"
              @keydown.enter.prevent="search"
            />
          </label>
        </div>
        <div class="db-form-actions">
          <button class="btn btn-primary" type="button" :disabled="saving || !form.service || !form.projectPath" @click="save">
            {{ saving ? '保存中…' : '保存映射' }}
          </button>
          <span v-if="form.projectPath" class="pane-count">已选项目：{{ form.projectPath }}</span>
        </div>

        <!-- 搜索结果：紧凑 chips，点选即定为映射目标 -->
        <div v-if="results.length" class="svcm-chips">
          <button
            v-for="p in results"
            :key="p.projectId"
            class="svcm-chip"
            :class="{ picked: p.path === form.projectPath }"
            type="button"
            @click="pick(p)"
          >{{ p.path }}{{ p.path === form.projectPath ? ' ✓' : '' }}</button>
        </div>

        <div v-if="message" class="td-message" :class="message.cls">{{ message.text }}</div>
      </div>

      <!-- 映射列表 -->
      <div>
        <div v-if="loading" class="hd-empty">加载中…</div>
        <div v-else-if="!items.length" class="hd-empty">
          还没有服务映射<br /><small>未配置的服务会按名称自动搜索 GitLab 推断，歧义时对话里反问</small>
        </div>
        <div v-else class="pane-list">
          <div v-for="m in items" :key="m.service" class="db-item">
            <div class="db-item-main">
              <div class="db-item-head">
                <code>{{ m.service }}</code>
                <span class="db-host">→ {{ m.projectPath }}</span>
              </div>
              <div class="db-item-dbs">添加于 {{ m.createdAt }}</div>
            </div>
            <div class="db-item-ops">
              <button class="db-op del" type="button" @click="remove(m.service)">删除</button>
            </div>
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
const searching = ref(false)
const gitlabConfigured = ref(true)
const message = ref(null)
const results = ref([])
const form = ref({ service: '', keyword: '', projectPath: '' })

async function load() {
  loading.value = true
  try {
    const data = await api.get('/api/servicemap')
    items.value = data.items || []
    gitlabConfigured.value = data.gitlabConfigured !== false
  } catch (err) {
    toastError(err)
  } finally {
    loading.value = false
  }
}

async function search() {
  if (!form.value.keyword || searching.value) return
  searching.value = true
  message.value = null
  try {
    const res = await fetch(`/api/servicemap/search?keyword=${encodeURIComponent(form.value.keyword)}`)
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || '搜索失败')
    results.value = data.items || []
    if (!results.value.length) message.value = { text: '没有搜到相关项目，换个关键词试试', cls: 'err' }
  } catch (err) {
    message.value = { text: err.message || '搜索失败', cls: 'err' }
  } finally {
    searching.value = false
  }
}

function pick(p) {
  form.value.projectPath = p.path
  form.value.keyword = p.path
}

async function save() {
  if (saving.value) return
  saving.value = true
  message.value = null
  try {
    const res = await fetch('/api/servicemap', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ service: form.value.service, projectPath: form.value.projectPath }),
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || '保存失败')
    message.value = { text: `已保存 ${form.value.service} → ${form.value.projectPath}`, cls: 'ok' }
    form.value = { service: '', keyword: '', projectPath: '' }
    results.value = []
    await load()
  } catch (err) {
    message.value = { text: err.message || '保存失败', cls: 'err' }
  } finally {
    saving.value = false
  }
}

async function remove(service) {
  if (!window.confirm(`确定删除 ${service} 的映射？`)) return
  items.value = items.value.filter((m) => m.service !== service)
  try {
    await fetch(`/api/servicemap/${encodeURIComponent(service)}`, { method: 'DELETE' })
  } catch { /* 静默 */ }
  await load()
}

onMounted(load)
</script>
