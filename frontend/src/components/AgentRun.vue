<template>
  <div class="agent-card">
    <div class="agent-bar">
      <span class="agent-avatar" aria-hidden="true">✦</span>
      <span class="agent-name">Agent 执行</span>
      <span class="agent-status" :class="run.status.cls" role="status" aria-live="polite">● {{ run.status.text }}</span>
    </div>

    <div class="agent-body">
      <!-- 意图分析 -->
      <div v-if="run.intent" class="intent-box">
        <span class="intent-label">意图</span>
        <span class="intent-summary">{{ run.intent.summary }}</span>
        <span v-for="(e, i) in run.intent.entities" :key="i" class="entity-chip">{{ e }}</span>
      </div>

      <!-- 阶段进度 -->
      <div class="phases" aria-label="执行阶段">
        <template v-for="(name, key, i) in phaseList" :key="key">
          <div class="phase" :class="run.phases[key]">
            <span class="phase-dot" aria-hidden="true"></span>
            <span>{{ name }}</span>
          </div>
          <span v-if="i < 3" class="phase-arrow" aria-hidden="true">→</span>
        </template>
      </div>

      <!-- 步骤时间线 -->
      <ol class="timeline">
        <StepCard v-for="s in visibleSteps" :key="s.index" :step="s" />
      </ol>

      <!-- 最终答案 -->
      <div class="final-panel" v-if="run.final.visible">
        <div class="fp-head">
          <span class="fp-ico" aria-hidden="true">◈</span>
          <div>
            <h4>任务汇总</h4>
            <p>{{ run.final.summary }}</p>
          </div>
        </div>
        <div class="fp-output">{{ run.final.output }}</div>
        <div class="fp-meta">
          <span v-for="(m, i) in run.final.meta" :key="i">{{ m }}</span>
        </div>
        <div class="fp-actions">
          <button class="btn btn-ghost copy-btn" type="button" @click="copyFinal">
            {{ copied ? '✓ 已复制' : '复制结果' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import StepCard from './StepCard.vue'

const props = defineProps({
  run: { type: Object, required: true },
})

const phaseList = { understand: '意图分析', plan: '任务规划', execute: '逐步执行', merge: '结果汇总' }
const visibleSteps = computed(() => props.run.steps.filter(Boolean))
const copied = ref(false)

async function copyFinal() {
  const text = props.run.final.output
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    const ta = document.createElement('textarea')
    ta.value = text
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    ta.remove()
  }
  copied.value = true
  setTimeout(() => (copied.value = false), 1600)
}
</script>
