<template>
  <header class="chat-header">
    <div class="chat-header-inner">
      <div class="brand">
        <button class="brand-mark" type="button" title="任务历史" aria-label="打开任务历史" @click="$emit('history')">
          <svg viewBox="0 0 32 32" width="20" height="20" fill="none">
            <circle cx="16" cy="16" r="6.5" stroke="currentColor" stroke-width="2"/>
            <circle cx="16" cy="16" r="2" fill="currentColor"/>
            <path d="M16 2.5v5M16 24.5v5M2.5 16h5M24.5 16h5M6.4 6.4l3.5 3.5M22.1 22.1l3.5 3.5M25.6 6.4l-3.5 3.5M9.9 22.1l-3.5 3.5" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
        </button>
        <span class="brand-name">AgentFlow</span>
        <span class="brand-sub">AI 任务助手</span>
      </div>
      <div class="header-tools">
        <span class="llm-badge" :class="llm.cls">{{ llm.text }}</span>
        <button v-if="hasRuns" class="clear-btn" type="button" @click="$emit('clear')">清空对话</button>
      </div>
    </div>
  </header>
</template>

<script setup>
/* 工作台入口已移到左下角输入区（见 Composer），顶栏只保留任务历史与清空对话 */
defineProps({
  llm: { type: Object, required: true },
  hasRuns: { type: Boolean, default: false },
})
defineEmits(['clear', 'history'])
</script>
