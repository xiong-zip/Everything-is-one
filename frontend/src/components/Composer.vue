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
        <div class="composer-right">
          <select
            v-if="dbProfiles.length"
            class="db-select"
            :value="dbActive"
            title="默认数据库连接：聊天中不点名数据库时自动使用它查询"
            aria-label="选择默认数据库连接"
            @change="$emit('db-active', $event.target.value)"
          >
            <option value="">🗄 数据库：未指定</option>
            <option v-for="p in dbProfiles" :key="p.name" :value="p.name">
              🗄 {{ p.name }} · {{ typeName(p.type) }}
            </option>
          </select>
          <button
            class="confirm-toggle-sm"
            :class="{ on: confirmMode }"
            type="button"
            :title="confirmMode ? '确认模式已开启：任务规划后先确认再执行' : '确认模式已关闭：规划后直接执行'"
            @click="$emit('toggle-confirm')"
          >{{ confirmMode ? '🛡 确认模式' : '确认模式' }}</button>
          <span class="composer-hint">Enter 执行 · Shift+Enter 换行</span>
        </div>
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
  confirmMode: { type: Boolean, default: false },
  dbProfiles: { type: Array, default: () => [] },
  dbActive: { type: String, default: '' },
})

const emit = defineEmits(['send', 'stop', 'toggle-confirm', 'db-active'])

function typeName(t) {
  return { dameng: '达梦', mysql: 'MySQL', postgresql: 'PostgreSQL', oracle: 'Oracle' }[t] || t
}

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
