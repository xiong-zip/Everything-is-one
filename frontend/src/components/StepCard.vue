<template>
  <div class="z-step" :class="[step.state, { open: expanded }]">
    <!-- 单行条目：完成态点击整行展开/收起详情 -->
    <button class="z-step-row" type="button" :disabled="step.state === 'pending'" @click="toggle">
      <span class="z-step-icon" aria-hidden="true">
        <span v-if="step.state === 'running'" class="z-spin"></span>
        <template v-else>{{ step.state === 'done' ? '●' : '○' }}</template>
      </span>
      <span class="z-step-title">{{ step.title }}</span>
      <span v-if="toolsLine" class="z-step-tools">{{ toolsLine }}</span>
      <span v-if="step.state === 'done' && conclusion" class="z-step-note">{{ conclusion }}</span>
      <span class="z-step-meta">{{ metaText }}</span>
      <span v-if="step.state === 'done' && hasDetail" class="z-step-chev" aria-hidden="true">{{ expanded ? '▾' : '▸' }}</span>
    </button>

    <!-- 运行中：推理行流式输出，紧跟在条目下方（扁平、无边框） -->
    <div v-if="step.state === 'running'" class="z-step-live">
      <div v-for="(r, i) in step.reasons" :key="'r' + i" class="z-reason">
        <span class="z-reason-bullet" aria-hidden="true">·</span>
        <span class="z-reason-txt">{{ r.shown }}</span>
      </div>
      <ResultCard v-if="step.result" :result="step.result" flat />
    </div>

    <!-- 展开的详情：完整工具参数 + 执行结果 -->
    <div v-else-if="expanded" class="z-step-detail">
      <div v-for="(t, i) in step.tools" :key="'t' + i" class="z-tool">
        <span class="z-tool-name">{{ t.name }}</span>
        <code class="z-tool-args">{{ argsText(t.args) }}</code>
      </div>
      <div v-if="step.reasons.length" class="z-reasons">
        <div v-for="(r, i) in step.reasons" :key="'dr' + i" class="z-reason">
          <span class="z-reason-bullet" aria-hidden="true">·</span>
          <span class="z-reason-txt">{{ r.shown }}</span>
        </div>
      </div>
      <ResultCard v-if="step.result" :result="step.result" flat />
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import ResultCard from './ResultCard.vue'

const props = defineProps({
  step: { type: Object, required: true },
})

const expanded = ref(false)

function toggle() {
  if (props.step.state !== 'done') return
  expanded.value = !expanded.value
}

const hasDetail = computed(() => props.step.tools.length > 0 || props.step.reasons.length > 0 || !!props.step.result)

/* 单行里的工具签名：k8s.query(action: list, cluster: dev …)，过长截断 */
const toolsLine = computed(() => {
  const ts = props.step.tools
  if (!ts.length) return ''
  const t = ts[0]
  const args = argsText(t.args)
  const more = ts.length > 1 ? ` +${ts.length - 1}` : ''
  return `${t.name}(${args})${more}`
})

/* 思考步完成态：结论行直接放在条目里（灰色一行），详情里看全部推理 */
const conclusion = computed(() => {
  if (props.step.kind !== 'think' || props.step.state !== 'done') return ''
  const rs = props.step.reasons
  const last = rs.length ? rs[rs.length - 1].shown : ''
  return last.length > 80 ? last.slice(0, 80) + '…' : last
})

const metaText = computed(() => {
  if (props.step.state === 'running') return '执行中'
  if (props.step.state === 'pending') return '等待'
  return props.step.durationMs ? fmtDuration(props.step.durationMs) : '完成'
})

function fmtDuration(ms) {
  return ms < 1000 ? ms + 'ms' : (ms / 1000).toFixed(1) + 's'
}

function argsText(args) {
  if (args == null) return ''
  if (typeof args === 'string') return args
  return Object.entries(args).map(([k, v]) => `${k}: ${v}`).join(', ')
}
</script>
