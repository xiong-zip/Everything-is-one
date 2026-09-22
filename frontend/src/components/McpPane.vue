<template>
  <div class="wb-pane pane-col">
    <div class="hd-head">
      <h3>MCP 服务</h3>
      <span class="hd-actions pane-count">已接入 {{ servers.length }} 个 · {{ toolTotal }} 个工具</span>
    </div>

    <div class="pane-body">
      <p class="gl-hint">
        登记任意 <b>MCP（Model Context Protocol）</b> 服务后，它暴露的工具会<b>自动变成 Agent 可调用的工具</b>，
        和内置的 gitlab.query、k8s.query 一样参与任务规划，不需要为每个系统写代码。
        保存只落配置、不连网；点「刷新工具」才去拉取工具清单。
      </p>

      <!-- 新增 / 编辑 -->
      <div class="pane-card">
        <div class="pane-card-title">
          {{ form.originalName ? `编辑「${form.originalName}」` : '接入新服务' }}
        </div>
        <div class="mcp-form">
          <label class="mcp-field">
            <span class="mcp-label">名称（工具名前缀）</span>
            <input v-model="form.name" class="db-input" placeholder="如 grafana，工具将叫 grafana.xxx" />
          </label>
          <label class="mcp-field">
            <span class="mcp-label">MCP 地址（Streamable HTTP）</span>
            <input v-model="form.url" class="db-input" placeholder="如 http://grafana.internal:8080/mcp" />
          </label>
          <label class="mcp-field mcp-field-wide">
            <span class="mcp-label">
              请求头（每行一条 <code>键: 值</code>，用于鉴权；留空表示沿用原有请求头）
            </span>
            <textarea
              v-model="form.headers"
              class="db-input mcp-textarea"
              rows="2"
              :placeholder="headerPlaceholder"
            ></textarea>
          </label>
        </div>
        <label class="mcp-switch">
          <input v-model="form.confirmAll" type="checkbox" />
          <span>所有工具都走人工确认（远端工具行为未知，默认开启更安全）</span>
        </label>
        <label class="mcp-switch">
          <input v-model="form.enabled" type="checkbox" />
          <span>启用（关闭后其工具从 Agent 工具清单中移除，但配置与缓存保留）</span>
        </label>
        <div class="mcp-form-actions">
          <button class="btn btn-primary" type="button" :disabled="saving" @click="submit">
            {{ saving ? '保存中…' : '保存' }}
          </button>
          <button v-if="form.originalName" class="btn btn-ghost" type="button" @click="resetForm">取消编辑</button>
        </div>
        <div v-if="message" class="td-message" :class="message.cls">{{ message.text }}</div>
      </div>

      <!-- 服务列表 -->
      <div>
        <div v-if="loading" class="hd-empty">加载中…</div>
        <div v-else-if="!servers.length" class="hd-empty">
          还没有接入 MCP 服务<br /><small>先在上方登记地址并保存，再点该服务的「刷新工具」拉取清单</small>
        </div>
        <div v-else class="pane-list">
          <div v-for="s in servers" :key="s.name" class="db-item mcp-item">
            <div class="db-item-main">
              <div class="mcp-item-top">
                <b class="mcp-name">{{ s.name }}</b>
                <span class="al-badge" :class="s.enabled ? 'done' : 'skipped'">{{ s.enabled ? '已启用' : '已停用' }}</span>
                <span class="mcp-chip">{{ s.toolCount }} 个工具</span>
                <span v-if="s.confirmAll" class="mcp-chip warn">人工确认</span>
                <span v-for="h in s.headers" :key="h" class="mcp-chip mono">{{ h }}</span>
              </div>
              <div class="al-item-sub">
                <code class="al-mono">{{ s.url }}</code>
                <span v-if="s.lastRefreshAt">· 上次刷新 {{ s.lastRefreshAt }}</span>
                <span v-else>· 尚未刷新过工具清单</span>
              </div>
              <div v-if="s.lastError" class="mcp-error">最近一次失败：{{ s.lastError }}</div>
              <div v-if="s.tools?.length" class="mcp-tools" :class="{ open: expanded === s.name }">
                <div v-for="t in visibleTools(s)" :key="t.localName" class="mcp-tool">
                  <div class="mcp-tool-head">
                    <code class="al-mono">{{ t.localName }}</code>
                    <span v-if="t.readOnly === true" class="mcp-chip ok">只读</span>
                    <span v-else-if="t.readOnly === false" class="mcp-chip warn">可能写操作</span>
                    <span v-else class="mcp-chip">未声明读写</span>
                    <span v-if="!t.registered" class="mcp-chip warn">未注册</span>
                  </div>
                  <div class="mcp-tool-desc">{{ t.description || '（远端未提供说明）' }}</div>
                  <div class="mcp-tool-args">参数 {{ t.argsHint }}</div>
                </div>
                <button
                  v-if="s.tools.length > COLLAPSED"
                  class="btn btn-ghost mcp-more"
                  type="button"
                  @click="toggle(s.name)"
                >
                  {{ expanded === s.name ? '收起' : `展开全部 ${s.tools.length} 个工具` }}
                </button>
              </div>
            </div>
            <div class="db-item-ops mcp-ops">
              <button class="db-op" type="button" :disabled="busy === s.name" @click="refresh(s)">
                {{ busy === s.name ? '刷新中…' : '刷新工具' }}
              </button>
              <button class="db-op" type="button" @click="toggleEnabled(s)">{{ s.enabled ? '停用' : '启用' }}</button>
              <button class="db-op" type="button" @click="edit(s)">编辑</button>
              <button class="db-op del" type="button" @click="remove(s)">删除</button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { api, toastError } from '../api/client'

/* 工具多的时候默认只列前几个，避免一个服务把整个面板撑满 */
const COLLAPSED = 4

const servers = ref([])
const loading = ref(false)
const saving = ref(false)
const busy = ref('')
const expanded = ref(null)
const message = ref(null)
const form = ref(blankForm())

const toolTotal = computed(() => servers.value.reduce((n, s) => n + (s.toolCount || 0), 0))
const headerPlaceholder = computed(() => {
  const cur = servers.value.find((s) => s.name === form.value.originalName)
  return cur?.headers?.length ? `已有：${cur.headers.join('、')}` : '如 Authorization: Bearer xxx'
})

function blankForm() {
  return { name: '', url: '', headers: '', confirmAll: true, enabled: true, originalName: '' }
}

function resetForm() {
  form.value = blankForm()
  message.value = null
}

function visibleTools(s) {
  if (expanded.value === s.name) return s.tools
  return s.tools.slice(0, COLLAPSED)
}

function toggle(name) {
  expanded.value = expanded.value === name ? null : name
}

/** 每行一条「键: 值」；返回 null 表示用户没填（沿用原有请求头，不覆盖） */
function parseHeaders(text) {
  const raw = (text || '').trim()
  if (!raw) return null
  const out = {}
  for (const line of raw.split('\n')) {
    const idx = line.indexOf(':')
    if (idx <= 0) continue
    const key = line.slice(0, idx).trim()
    const value = line.slice(idx + 1).trim()
    if (key && value) out[key] = value
  }
  return out
}

async function load() {
  loading.value = true
  try {
    servers.value = (await api.get('/api/mcp/servers')).servers || []
  } catch (err) {
    toastError(err)
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (saving.value) return
  if (!form.value.name.trim() || !form.value.url.trim()) {
    message.value = { text: '名称与地址都要填', cls: 'err' }
    return
  }
  saving.value = true
  message.value = null
  try {
    const payload = {
      name: form.value.name.trim(),
      url: form.value.url.trim(),
      confirmAll: form.value.confirmAll,
      enabled: form.value.enabled,
      originalName: form.value.originalName || undefined,
    }
    const headers = parseHeaders(form.value.headers)
    if (headers) payload.headers = headers
    const res = await fetch('/api/mcp/servers', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || '保存失败')
    resetForm()
    message.value = { text: '已保存。点该服务的「刷新工具」拉取工具清单。', cls: 'ok' }
    await load()
  } catch (err) {
    message.value = { text: err.message || '保存失败', cls: 'err' }
  } finally {
    saving.value = false
  }
}

async function refresh(s) {
  busy.value = s.name
  message.value = null
  try {
    const res = await fetch(`/api/mcp/servers/${encodeURIComponent(s.name)}/refresh`, { method: 'POST' })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || '刷新失败')
    message.value = {
      text: `「${s.name}」发现 ${data.discovered} 个工具，已注册 ${data.registered} 个。`
        + (data.notice ? ` ${data.notice}` : ''),
      cls: data.notice ? 'warn' : 'ok',
    }
    expanded.value = s.name
    await load()
  } catch (err) {
    message.value = { text: err.message || '刷新失败', cls: 'err' }
    await load()
  } finally {
    busy.value = ''
  }
}

async function toggleEnabled(s) {
  try {
    await fetch(`/api/mcp/servers/${encodeURIComponent(s.name)}/enabled`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled: !s.enabled }),
    })
  } catch { /* 静默 */ }
  await load()
}

function edit(s) {
  form.value = {
    name: s.name,
    url: s.url,
    headers: '',
    confirmAll: !!s.confirmAll,
    enabled: !!s.enabled,
    originalName: s.name,
  }
  message.value = null
}

async function remove(s) {
  if (!window.confirm(`删除 MCP 服务「${s.name}」？\n它接进来的 ${s.toolCount} 个工具会同时从 Agent 工具清单移除。`)) return
  try {
    await fetch(`/api/mcp/servers/${encodeURIComponent(s.name)}`, { method: 'DELETE' })
  } catch { /* 静默 */ }
  if (expanded.value === s.name) expanded.value = null
  await load()
}

onMounted(load)
</script>
