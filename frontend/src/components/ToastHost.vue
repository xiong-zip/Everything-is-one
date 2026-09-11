<template>
  <!-- 全局提示：置于所有面板之上（工作台弹窗 z-index 70），不随面板开关销毁 -->
  <div class="toast-host" aria-live="polite">
    <transition-group name="toast">
      <div v-for="t in toasts" :key="t.id" class="toast" :class="t.type" role="status">
        <span class="toast-ico" aria-hidden="true">{{ t.type === 'err' ? '⚠' : '✓' }}</span>
        <span class="toast-text">{{ t.text }}</span>
        <button class="toast-close" type="button" aria-label="关闭提示" @click="dismissToast(t.id)">✕</button>
      </div>
    </transition-group>
  </div>
</template>

<script setup>
import { dismissToast, useToasts } from '../composables/useToast'

const toasts = useToasts()
</script>
