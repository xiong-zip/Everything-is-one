<template>
  <header class="chat-header">
    <div class="chat-header-inner">
      <div class="brand">
        <span class="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 32 32" width="20" height="20" fill="none">
            <circle cx="16" cy="16" r="6.5" stroke="currentColor" stroke-width="2"/>
            <circle cx="16" cy="16" r="2" fill="currentColor"/>
            <path d="M16 2.5v5M16 24.5v5M2.5 16h5M24.5 16h5M6.4 6.4l3.5 3.5M22.1 22.1l3.5 3.5M25.6 6.4l-3.5 3.5M9.9 22.1l-3.5 3.5" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
        </span>
        <span class="brand-name">AgentFlow</span>
        <span class="brand-sub">AI 任务助手</span>
      </div>
      <div class="header-tools">
        <span class="llm-badge" :class="llm.cls">{{ llm.text }}</span>
        <button
          class="clear-btn confirm-toggle"
          :class="{ on: confirmMode }"
          type="button"
          :title="confirmMode ? '确认模式已开启：任务规划后先确认再执行' : '确认模式已关闭：规划后直接执行'"
          @click="$emit('toggle-confirm')"
        >{{ confirmMode ? '✓ 确认模式' : '确认模式' }}</button>
        <button class="clear-btn history-btn" type="button" @click="$emit('stats')" title="效能热力图：我的 GitLab 提交分布">
          <svg viewBox="0 0 20 20" width="13" height="13" fill="none" aria-hidden="true" style="vertical-align:-2px;margin-right:3px">
            <rect x="3" y="3" width="3.4" height="3.4" rx="1" fill="currentColor" opacity="0.9"/>
            <rect x="8.3" y="3" width="3.4" height="3.4" rx="1" fill="currentColor" opacity="0.45"/>
            <rect x="13.6" y="3" width="3.4" height="3.4" rx="1" fill="currentColor" opacity="0.7"/>
            <rect x="3" y="8.3" width="3.4" height="3.4" rx="1" fill="currentColor" opacity="0.45"/>
            <rect x="8.3" y="8.3" width="3.4" height="3.4" rx="1" fill="currentColor"/>
            <rect x="13.6" y="13.6" width="3.4" height="3.4" rx="1" fill="currentColor" opacity="0.45"/>
            <rect x="8.3" y="13.6" width="3.4" height="3.4" rx="1" fill="currentColor" opacity="0.7"/>
            <rect x="3" y="13.6" width="3.4" height="3.4" rx="1" fill="currentColor" opacity="0.25"/>
          </svg>效能
        </button>
        <button class="clear-btn history-btn" type="button" @click="$emit('morning')" title="晨报机器人：定时生成昨日日报并推送">
          <svg viewBox="0 0 20 20" width="13" height="13" fill="none" aria-hidden="true" style="vertical-align:-2px;margin-right:3px">
            <path d="M10 3.5a4.8 4.8 0 0 1 4.8 4.8c0 3.2.7 4.4 1.2 5H4c.5-.6 1.2-1.8 1.2-5A4.8 4.8 0 0 1 10 3.5z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/>
            <path d="M8.3 15.8a1.8 1.8 0 0 0 3.4 0" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
          </svg>晨报
        </button>
        <button class="clear-btn history-btn" type="button" @click="$emit('tools')" title="工具管理：查看 / OpenAPI 导入 / 删除">
          <svg viewBox="0 0 20 20" width="13" height="13" fill="none" aria-hidden="true" style="vertical-align:-2px;margin-right:3px">
            <path d="M3 5.5A2.5 2.5 0 0 1 5.5 3h9A2.5 2.5 0 0 1 17 5.5v9a2.5 2.5 0 0 1-2.5 2.5h-9A2.5 2.5 0 0 1 3 14.5v-9z" stroke="currentColor" stroke-width="1.6"/>
            <path d="M7 3v14M3 7.5h4M3 12h4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
          </svg>工具
        </button>
        <button class="clear-btn history-btn" type="button" @click="$emit('history')">
          <svg viewBox="0 0 20 20" width="13" height="13" fill="none" aria-hidden="true" style="vertical-align:-2px;margin-right:3px">
            <path d="M10 4v5l3.5 2" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
            <circle cx="10" cy="10" r="7.2" stroke="currentColor" stroke-width="1.8"/>
          </svg>历史
        </button>
        <button v-if="hasRuns" class="clear-btn" type="button" @click="$emit('clear')">清空对话</button>
      </div>
    </div>
  </header>
</template>

<script setup>
defineProps({
  llm: { type: Object, required: true },
  hasRuns: { type: Boolean, default: false },
  confirmMode: { type: Boolean, default: false },
})
defineEmits(['clear', 'history', 'toggle-confirm', 'tools', 'morning', 'stats'])
</script>
