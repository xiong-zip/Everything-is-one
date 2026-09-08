<template>
  <div class="app">
    <ChatHeader :llm="llm" :hasRuns="runs.length > 0" @clear="clearAll()" />

    <main class="chat-main" ref="scrollEl">
      <div class="chat-scroll">
        <div v-if="runs.length === 0" class="welcome">
          <div class="welcome-mark">
            <svg viewBox="0 0 32 32" width="32" height="32" fill="none">
              <circle cx="16" cy="16" r="6.5" stroke="currentColor" stroke-width="2"/>
              <circle cx="16" cy="16" r="2" fill="currentColor"/>
              <path d="M16 2.5v5M16 24.5v5M2.5 16h5M24.5 16h5M6.4 6.4l3.5 3.5M22.1 22.1l3.5 3.5M25.6 6.4l-3.5 3.5M9.9 22.1l-3.5 3.5" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            </svg>
          </div>
          <h1>你好，我是 <em>AgentFlow</em></h1>
          <p>告诉我任何任务——查天气、看股价、规划行程，或者写文案、写邮件。我会自动拆解、调用工具、逐步完成并汇总结果。</p>
          <div class="welcome-ideas">
            <button v-for="s in examples" :key="s.command" type="button" class="chip" @click="prefill = { command: s.command, nonce: Date.now() }">{{ s.short }}</button>
          </div>
        </div>

        <div v-for="run in runs" :key="run.id" class="turn">
          <div class="user-bubble-row">
            <div class="user-bubble">{{ run.command }}</div>
          </div>
          <AgentRun :run="run" />
        </div>
      </div>
    </main>

    <Composer :busy="busy" :examples="examples" :prefill="prefill" @send="runAgent" />
  </div>
</template>

<script setup>
import { nextTick, onMounted, ref, watch } from 'vue'
import ChatHeader from './components/ChatHeader.vue'
import AgentRun from './components/AgentRun.vue'
import Composer from './components/Composer.vue'
import { useAgent } from './composables/useAgent'

const API_BASE = '/api/agent'

const examples = ref([])
const llm = ref({ text: '● 连接中…', cls: '' })
const scrollEl = ref(null)
const prefill = ref({ command: '', nonce: 0 })

const { runs, busy, runAgent, clearAll } = useAgent()

/* 新一轮开始、步骤增加或汇总出现时，滚动到底部跟随最新进展 */
watch(
  () => runs.value.map((r) => `${r.steps.length}-${r.final.visible ? 1 : 0}`).join(','),
  async () => {
    await nextTick()
    if (scrollEl.value) scrollEl.value.scrollTop = scrollEl.value.scrollHeight
  }
)

onMounted(async () => {
  try {
    const res = await fetch(`${API_BASE}/scenarios`)
    if (res.ok) examples.value = await res.json()
  } catch { /* 后端不可用时静默降级 */ }

  try {
    const res = await fetch(`${API_BASE}/info`)
    if (res.ok) {
      const info = await res.json()
      llm.value = info.llmEnabled
        ? { text: `● ${info.model} 已接入`, cls: 'on' }
        : { text: '● 模拟模式 · 未配置 API Key', cls: 'off' }
    }
  } catch {
    llm.value = { text: '● 后端不可用', cls: '' }
  }
})
</script>
