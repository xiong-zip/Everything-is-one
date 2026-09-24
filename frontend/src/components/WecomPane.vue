<template>
  <div class="wb-pane pane-col">
    <div class="hd-head">
      <h3>企业微信</h3>
      <span class="hd-actions pane-count">{{ status?.capabilityTotal || 0 }} 个方法 · {{ serviceCount }} 个服务</span>
    </div>

    <div class="pane-body">
      <p class="gl-hint">
        Agent 经部署机上的 <b>wecom-cli</b> 操作企业微信（文档、消息、待办、邮件等，内置 wecom.* 工具）。
        授权在<b>部署机终端</b>执行 <code class="al-mono">wecom-cli auth init</code> 扫码完成，
        凭据只留在本机加密存储，这里不收任何 Secret。
      </p>

      <!-- 状态卡 -->
      <div class="pane-card">
        <div class="wecom-status-row">
          <span class="al-badge" :class="authBadge.cls">{{ authBadge.text }}</span>
          <span v-if="status?.cliVersion" class="mcp-chip mono">{{ status.cliVersion }}</span>
          <span v-if="status && !status.enabled" class="mcp-chip warn">未启用</span>
          <span v-if="status?.lastRefreshAt" class="al-item-sub">能力清单更新于 {{ status.lastRefreshAt }}</span>
        </div>
        <div v-if="status?.lastError" class="mcp-error">最近一次失败：{{ status.lastError }}</div>
        <div v-if="!installed" class="wecom-guide">
          部署机还未安装 wecom-cli：需要 Node.js 18+，然后执行
          <code class="al-mono">npm install -g @wecom/cli</code>
        </div>
        <div v-else-if="!authorized" class="wecom-guide">
          在部署机终端执行 <code class="al-mono">wecom-cli auth init</code> 扫码登录，完成后点「检查授权」
        </div>
        <div class="mcp-form-actions">
          <button class="btn btn-primary" type="button" :disabled="busy" @click="checkAuth">
            {{ busy === 'auth' ? '检查中…' : '检查授权' }}
          </button>
          <button class="btn btn-ghost" type="button" :disabled="busy === 'refresh'" @click="refreshCaps">
            {{ busy === 'refresh' ? '刷新中…（要跑几十个子进程）' : '刷新能力清单' }}
          </button>
        </div>
        <div v-if="message" class="td-message" :class="message.cls">{{ message.text }}</div>
      </div>

      <!-- 能力清单 -->
      <div>
        <div v-if="loading" class="hd-empty">加载中…</div>
        <div v-else-if="!services.length" class="hd-empty">
          能力清单为空<small>点上方「刷新能力清单」从 wecom-cli 帮助输出解析可用服务与方法</small>
        </div>
        <div v-else class="pane-list">
          <div v-for="svc in services" :key="svc.name" class="db-item">
            <div class="db-item-main">
              <div class="mcp-item-top">
                <b class="mcp-name">{{ svc.name }}</b>
                <span class="mcp-chip">{{ svc.methods.length }} 个方法</span>
              </div>
              <div class="mcp-tools" :class="{ open: expanded === svc.name }">
                <div v-for="m in visibleMethods(svc)" :key="m.method" class="mcp-tool">
                  <div class="mcp-tool-head"><code class="al-mono">{{ svc.name }} {{ m.method }}</code></div>
                  <div class="mcp-tool-desc">{{ m.description || '（CLI 未提供说明）' }}</div>
                </div>
                <button v-if="svc.methods.length > COLLAPSED" class="btn btn-ghost mcp-more" type="button" @click="toggle(svc.name)">
                  {{ expanded === svc.name ? '收起' : `展开全部 ${svc.methods.length} 个方法` }}
                </button>
              </div>
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

const COLLAPSED = 4

const status = ref(null)
const loading = ref(false)
const busy = ref('')
const expanded = ref(null)
const message = ref(null)

const services = computed(() => {
  const caps = status.value?.capabilities || {}
  return Object.entries(caps).map(([name, methods]) => ({ name, methods }))
})
const serviceCount = computed(() => services.value.length)
const installed = computed(() => status.value && status.value.authStatus !== 'cli-missing')
const authorized = computed(() => status.value?.authStatus === 'authorized')
const authBadge = computed(() => {
  if (!status.value) return { text: '…', cls: 'skipped' }
  if (status.value.authStatus === 'authorized') return { text: '已授权', cls: 'done' }
  if (status.value.authStatus === 'cli-missing') return { text: '未安装 wecom-cli', cls: 'skipped' }
  return { text: '未授权', cls: 'skipped' }
})

function visibleMethods(svc) {
  return expanded.value === svc.name ? svc.methods : svc.methods.slice(0, COLLAPSED)
}

function toggle(name) {
  expanded.value = expanded.value === name ? null : name
}

async function load() {
  loading.value = true
  try {
    status.value = await api.get('/api/wecom/status')
  } catch (err) {
    toastError(err)
  } finally {
    loading.value = false
  }
}

async function checkAuth() {
  busy.value = 'auth'
  message.value = null
  try {
    const res = await api.post('/api/wecom/auth/check')
    message.value = res.authStatus === 'authorized'
      ? { text: '授权有效，wecom.* 工具可用', cls: 'ok' }
      : { text: '仍未授权：请在部署机终端执行 wecom-cli auth init 扫码', cls: 'err' }
    await load()
  } catch (err) {
    message.value = { text: err.message || '检查失败', cls: 'err' }
  } finally {
    busy.value = ''
  }
}

async function refreshCaps() {
  busy.value = 'refresh'
  message.value = null
  try {
    const res = await api.post('/api/wecom/capabilities/refresh')
    message.value = res.discovered
      ? { text: `发现 ${res.services} 个服务、${res.discovered} 个方法`, cls: 'ok' }
      : { text: res.error || '未发现任何方法', cls: 'err' }
    await load()
  } catch (err) {
    message.value = { text: err.message || '刷新失败', cls: 'err' }
  } finally {
    busy.value = ''
  }
}

onMounted(load)
</script>
