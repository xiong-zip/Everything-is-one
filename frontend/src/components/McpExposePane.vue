<template>
  <div class="wb-pane pane-col">
    <div class="hd-head">
      <h3>对外 MCP 服务</h3>
      <span class="hd-actions pane-count">暴露 {{ status?.toolCount || 0 }} 个工具</span>
    </div>

    <div class="pane-body">
      <p class="gl-hint">
        把 AgentFlow 的工具（查链路、健康巡检、知识库检索等）以 <b>MCP 服务</b>形式暴露给外部客户端——
        典型用法：企业微信管理后台的<b>智能机器人 → MCP 配置</b>里填入下方地址，之后在企微里与机器人对话即可调用这些工具。
        写操作工具在外部通道<b>无法人工放行，一律拒绝执行</b>（readOnlyHint 已如实标注）。
      </p>

      <!-- 接入信息卡 -->
      <div class="pane-card">
        <div class="wecom-status-row">
          <span class="al-badge" :class="status?.enabled ? 'done' : 'skipped'">{{ status?.enabled ? '已启用' : '未启用' }}</span>
          <span v-if="status?.tokenConfigured" class="mcp-chip ok">已配令牌</span>
          <span v-else class="mcp-chip warn">未配令牌 · 仅本机可访问</span>
        </div>
        <label class="mcp-field mcp-field-wide">
          <span class="mcp-label">接入地址（Streamable HTTP，粘贴到企微机器人的 MCP 配置）</span>
          <div class="wecom-url-row">
            <input class="db-input al-mono" readonly :value="endpointUrl" @focus="$event.target.select()" />
            <button class="btn btn-ghost" type="button" @click="copyUrl">{{ copied ? '已复制' : '复制' }}</button>
          </div>
        </label>
        <div v-if="!status?.tokenConfigured" class="wecom-guide">
          内网/公网使用前，在 .env 配置 <code class="al-mono">AGENTFLOW_MCP_SERVER_TOKEN</code> 并重启；
          客户端请求头带 <code class="al-mono">Authorization: Bearer &lt;令牌&gt;</code>
        </div>
        <div class="mcp-form-actions">
          <button class="btn btn-ghost" type="button" :disabled="loading" @click="load">
            {{ loading ? '刷新中…' : '刷新' }}
          </button>
          <button class="btn btn-ghost" type="button" @click="clearCalls">清空调用记录</button>
        </div>
      </div>

      <!-- 最近调用 -->
      <div>
        <div v-if="!calls.length" class="hd-empty">还没有外部调用<small>接入后这里可以看到谁在调、调了什么、成没成</small></div>
        <div v-else class="pane-list">
          <div v-for="(c, i) in calls" :key="i" class="db-item">
            <div class="db-item-main">
              <div class="mcp-item-top">
                <code class="al-mono">{{ c.tool || c.method }}</code>
                <span class="al-badge" :class="c.ok ? 'done' : 'skipped'">{{ c.ok ? '成功' : '失败' }}</span>
                <span class="mcp-chip mono">{{ c.client }}</span>
              </div>
              <div class="al-item-sub">{{ c.ts }}<span v-if="c.error"> · {{ c.error }}</span></div>
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

const status = ref(null)
const loading = ref(false)
const copied = ref(false)

const calls = computed(() => status.value?.recentCalls || [])
const endpointUrl = computed(() => {
  if (!status.value?.endpoint) return ''
  const origin = typeof window !== 'undefined' ? window.location.origin : 'http://localhost:8888'
  return origin + status.value.endpoint
})

async function load() {
  loading.value = true
  try {
    status.value = await api.get('/api/mcp/expose/status')
  } catch (err) {
    toastError(err)
  } finally {
    loading.value = false
  }
}

async function copyUrl() {
  try {
    await navigator.clipboard.writeText(endpointUrl.value)
    copied.value = true
    setTimeout(() => { copied.value = false }, 1500)
  } catch { /* 剪贴板不可用时静默，用户可手动选中复制 */ }
}

async function clearCalls() {
  if (!window.confirm('清空全部对外调用记录？')) return
  try {
    await api.del('/api/mcp/expose/calls')
    await load()
  } catch (err) {
    toastError(err)
  }
}

onMounted(load)
</script>
