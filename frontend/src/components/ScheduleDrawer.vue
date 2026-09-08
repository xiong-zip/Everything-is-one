<template>
  <div class="drawer-mask" @click.self="$emit('close')">
    <aside class="history-drawer schedule-drawer" role="dialog" aria-label="晨报机器人">
      <div class="hd-head">
        <h3>晨报机器人</h3>
        <div class="hd-actions">
          <button class="hd-close" type="button" aria-label="关闭" @click="$emit('close')">✕</button>
        </div>
      </div>

      <!-- 配置状态（只读，来自服务端环境变量） -->
      <div class="sd-conf">
        <div class="sd-row">
          <span class="sd-key">定时开关</span>
          <span class="sd-val" :class="status.enabled ? 'on' : 'off'">
            {{ status.enabled ? '已开启' : '未开启' }}
          </span>
        </div>
        <div class="sd-row">
          <span class="sd-key">执行时间</span>
          <span class="sd-val mono">{{ status.cron }}</span>
        </div>
        <div class="sd-row">
          <span class="sd-key">推送通道</span>
          <span class="sd-val" :class="status.webhookConfigured ? 'on' : 'off'">
            {{ status.webhookConfigured ? 'Webhook 已配置' : '未配置（只落库不推送）' }}
          </span>
        </div>
        <div class="sd-cmd">
          <span class="sd-key">执行指令</span>
          <div class="sd-cmd-text">{{ status.command }}</div>
        </div>
        <p class="sd-hint">以上在服务端 .env 中配置（AGENTFLOW_MORNING_REPORT / AGENTFLOW_MORNING_CRON / AGENTFLOW_NOTIFY_WEBHOOK），修改后重启生效。</p>
        <button class="btn btn-primary sd-run" type="button" :disabled="running" @click="runNow">
          {{ running ? '生成中…（约需 1 分钟）' : '立即试跑' }}
        </button>
        <div v-if="runResult" class="sd-result" :class="runResult.status">
          <div class="sd-result-head">
            试跑结果 · {{ { done: '成功', error: '异常', cancelled: '取消', running: '超时（仍在后台执行）' }[runResult.status] || runResult.status }}
            <template v-if="runResult.pushed"> · 已推送</template>
            <template v-else-if="status.webhookConfigured"> · 推送失败</template>
          </div>
          <pre class="sd-result-body">{{ runResult.output || runResult.summary || '（无输出）' }}</pre>
        </div>
      </div>

      <!-- 最近执行 -->
      <div class="hd-list">
        <div class="sd-section-title">最近执行</div>
        <div v-if="loading" class="hd-empty">加载中…</div>
        <div v-else-if="!(status.recentRuns || []).length" class="hd-empty">
          还没有自动执行记录<br /><small>开启定时开关或点「立即试跑」后，这里会显示执行历史</small>
        </div>
        <div v-for="(r, i) in status.recentRuns || []" :key="i" class="sd-item">
          <div class="hd-sub">
            <span class="hd-status" :class="r.status">{{ { done: '成功', error: '异常', cancelled: '取消', running: '进行中' }[r.status] || r.status }}</span>
            <span class="sd-trigger">{{ r.trigger === 'cron' ? '定时' : '手动' }}</span>
            <span v-if="r.pushed" class="sd-pushed">已推送</span>
            <span class="hd-time">{{ r.createdAt }}</span>
          </div>
          <div class="sd-item-summary">{{ r.summary || r.output || '（无摘要）' }}</div>
        </div>
      </div>
    </aside>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'

defineEmits(['close'])

const status = ref({})
const loading = ref(false)
const running = ref(false)
const runResult = ref(null)

async function load() {
  loading.value = true
  try {
    const res = await fetch('/api/schedule/status')
    if (res.ok) status.value = await res.json()
  } catch { /* 静默 */ } finally {
    loading.value = false
  }
}

async function runNow() {
  if (running.value) return
  running.value = true
  runResult.value = null
  try {
    const res = await fetch('/api/schedule/run-now', { method: 'POST' })
    runResult.value = await res.json()
    await load()
  } catch {
    runResult.value = { status: 'error', output: '请求失败，请检查后端服务' }
  } finally {
    running.value = false
  }
}

onMounted(load)
</script>
