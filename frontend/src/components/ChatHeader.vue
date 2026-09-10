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
        <details ref="wbRef" class="wb-menu">
          <summary class="clear-btn wb-summary" title="数据库 / GitLab / 效能 / 晨报 / 工具">
            工作台<span class="wb-caret" aria-hidden="true">▾</span>
          </summary>
          <div class="wb-list" role="menu">
            <button class="wb-item" type="button" role="menuitem" @click="wbGo('db')">
              <span class="wb-ico" aria-hidden="true">🗄</span>数据库连接
            </button>
            <button class="wb-item" type="button" role="menuitem" @click="wbGo('gitlab')">
              <span class="wb-ico" aria-hidden="true">🦊</span>GitLab 账户
            </button>
            <button class="wb-item" type="button" role="menuitem" @click="wbGo('stats')">
              <span class="wb-ico" aria-hidden="true">▦</span>效能热力图
            </button>
            <button class="wb-item" type="button" role="menuitem" @click="wbGo('morning')">
              <span class="wb-ico" aria-hidden="true">⏰</span>晨报机器人
            </button>
            <button class="wb-item" type="button" role="menuitem" @click="wbGo('tools')">
              <span class="wb-ico" aria-hidden="true">⚙</span>工具管理
            </button>
          </div>
        </details>
        <button v-if="hasRuns" class="clear-btn" type="button" @click="$emit('clear')">清空对话</button>
      </div>
    </div>
  </header>
</template>

<script setup>
import { ref } from 'vue'

defineProps({
  llm: { type: Object, required: true },
  hasRuns: { type: Boolean, default: false },
})
const emit = defineEmits(['clear', 'history', 'tools', 'morning', 'stats', 'db', 'gitlab'])

const wbRef = ref(null)

/* 菜单项点击后关闭下拉并转发事件 */
function wbGo(name) {
  if (wbRef.value) wbRef.value.removeAttribute('open')
  emit(name)
}
</script>
