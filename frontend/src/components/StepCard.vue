<template>
  <div class="step" :class="step.state">
    <div class="step-rail">
      <span class="step-node">{{ icon }}</span>
      <span class="step-line"></span>
    </div>
    <div class="step-body">
      <div class="step-card" :class="{ dim: step.state === 'pending' }">
        <div class="step-head">
          <span class="step-tag">{{ step.tag }}</span>
          <span class="step-title">{{ step.title }}</span>
          <span class="step-state">
            <span class="dot"></span>
            <span>{{ stateText }}</span>
          </span>
        </div>
        <div class="step-detail">
          <div v-for="(t, i) in step.tools" :key="'t' + i" class="tool-call">
            <span class="tc-name">{{ t.name }}</span>
            <span class="tc-args">({{ argsText(t.args) }})</span>
          </div>
          <div v-if="step.reasons.length" class="reasoning">
            <div v-for="(r, i) in step.reasons" :key="'r' + i" class="reason-line">
              <span class="bullet">▸</span>
              <span>{{ r.shown }}</span>
            </div>
          </div>
          <ResultCard v-if="step.result" :result="step.result" />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import ResultCard from './ResultCard.vue'

const props = defineProps({
  step: { type: Object, required: true },
})

const icon = computed(() =>
  props.step.kind === 'tool' ? '⚙' : props.step.kind === 'think' ? '✎' : '✍'
)

const stateText = computed(() =>
  props.step.state === 'running' ? '执行中' : props.step.state === 'done' ? '完成' : '等待中'
)

function argsText(args) {
  if (args == null) return ''
  if (typeof args === 'string') return args
  return Object.entries(args).map(([k, v]) => `${k}: ${v}`).join(', ')
}
</script>
