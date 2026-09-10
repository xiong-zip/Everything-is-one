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
            <span>{{ stateText }}{{ step.durationMs ? ' · ' + fmtDuration(step.durationMs) : '' }}</span>
          </span>
        </div>
        <div class="step-detail">
          <div v-for="(t, i) in step.tools" :key="'t' + i" class="tool-call">
            <span class="tc-name">{{ t.name }}</span>
            <span class="tc-args">({{ argsText(t.args) }})</span>
          </div>
          <div v-if="step.reasons.length" class="reasoning">
            <!-- 已完成且未展开：只显示结论行 + 展开入口 -->
            <template v-if="step.state === 'done' && !step.reasonExpanded">
              <div class="reason-line reason-conclusion">
                <span class="bullet">▸</span>
                <span class="txt">{{ lastReason }}</span>
                <button class="reason-toggle" type="button" @click="step.reasonExpanded = true">
                  思考过程（{{ step.reasons.length }} 条）<span aria-hidden="true">▾</span>
                </button>
              </div>
            </template>
            <!-- 执行中或已展开：显示全部推理行 -->
            <template v-else>
              <div v-for="(r, i) in step.reasons" :key="'r' + i" class="reason-line">
                <span class="bullet">▸</span>
                <span class="txt">{{ r.shown }}</span>
              </div>
              <button
                v-if="step.state === 'done'"
                class="reason-toggle reason-toggle-inline"
                type="button"
                @click="step.reasonExpanded = false"
              >收起思考过程 <span aria-hidden="true">▴</span></button>
            </template>
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

function fmtDuration(ms) {
  return ms < 1000 ? ms + 'ms' : (ms / 1000).toFixed(1) + 's'
}

const lastReason = computed(() => {
  const rs = props.step.reasons
  return rs.length ? rs[rs.length - 1].shown : ''
})

function argsText(args) {
  if (args == null) return ''
  if (typeof args === 'string') return args
  return Object.entries(args).map(([k, v]) => `${k}: ${v}`).join(', ')
}
</script>
