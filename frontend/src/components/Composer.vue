<template>
  <div class="composer-wrap">
    <div class="composer">
      <div class="composer-box">
        <label class="sr-only" for="commandInput">输入任务指令</label>
        <textarea
          id="commandInput"
          ref="inputEl"
          v-model="command"
          rows="1"
          placeholder="输入任何任务，如：查北京天气并写一首小诗 / 帮我写一封请假邮件…"
          @keydown="onKeydown"
          @input="autoResize"
        ></textarea>
        <button
          class="btn btn-primary send-btn"
          :class="{ running: busy }"
          :disabled="!busy && !command.trim()"
          type="button"
          @click="busy ? emit('stop') : send()"
        >
          <span class="send-ico" aria-hidden="true"></span>{{ busy ? '停止' : '执行' }}
        </button>
      </div>
      <div class="composer-meta">
        <div class="example-chips" role="list" aria-label="示例任务">
          <span class="chip-label">试试：</span>
          <button
            v-for="s in examples"
            :key="s.command"
            type="button"
            role="listitem"
            class="chip"
            :class="{ active: s.command === command.trim() }"
            @click="command = s.command"
          >{{ s.short }}</button>
        </div>
        <span class="composer-hint">Enter 执行 · Shift+Enter 换行 · 任务不限示例，任意指令均可</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { nextTick, ref, watch } from 'vue'

const props = defineProps({
  busy: { type: Boolean, default: false },
  examples: { type: Array, default: () => [] },
  prefill: { type: Object, default: () => ({ command: '', nonce: 0 }) },
})

const emit = defineEmits(['send', 'stop'])

const command = ref('')
const inputEl = ref(null)

function send() {
  const text = command.value.trim()
  if (!text || props.busy) return
  emit('send', text)
}

function onKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    send()
  }
}

function autoResize(e) {
  const el = e.target
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 140) + 'px'
}

/* 外部预填（欢迎页/示例点击） */
watch(
  () => props.prefill.nonce,
  async (n) => {
    if (n && props.prefill.command) {
      command.value = props.prefill.command
      await nextTick()
      if (inputEl.value) {
        inputEl.value.focus()
        inputEl.value.dispatchEvent(new Event('input'))
      }
    }
  }
)
</script>
