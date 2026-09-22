<template>
  <div class="z-turn">
    <!-- 状态行：运行中 spinner + 状态文本；完成后收成一行可展开的过程开关 -->
    <div class="z-turn-head" :class="run.status.cls">
      <template v-if="!run.final.visible">
        <span v-if="run.status.cls === 'is-error'" class="z-err-dot" aria-hidden="true">✕</span>
        <span v-else class="z-spin" aria-hidden="true"></span>
        <span class="z-turn-status" role="status" aria-live="polite">{{ run.status.text }}</span>
      </template>
      <button
        v-else
        class="z-proc-toggle"
        type="button"
        :aria-expanded="run.processExpanded ? 'true' : 'false'"
        @click="run.processExpanded = !run.processExpanded"
      >
        <span class="z-done-dot" :class="{ cancelled: run.final.cancelled }" aria-hidden="true">{{ run.final.cancelled ? '⤼' : '✓' }}</span>
        <span>{{ run.final.cancelled ? '已停止' : '已完成' }} · {{ procSteps.length }} 个子任务</span>
        <span v-if="run.final.durationMs" class="z-proc-dur">{{ fmtDuration(run.final.durationMs) }}</span>
        <span class="z-step-chev" aria-hidden="true">{{ run.processExpanded ? '▾' : '▸' }}</span>
      </button>
      <span v-if="run.replayed" class="z-replay-badge">回放</span>
    </div>

    <!-- 执行过程：write 步之外的步骤（write 的产出直接作为回答渲染在下方） -->
    <div v-show="!run.final.visible || run.processExpanded" class="z-process">
      <div v-if="run.intent" class="z-intent">
        <span class="z-intent-label">意图</span>
        <span class="z-intent-summary">{{ run.intent.summary }}</span>
        <span v-for="(e, i) in run.intent.entities" :key="i" class="z-intent-chip">{{ e }}</span>
      </div>

      <!-- 计划确认卡（confirm 模式 / 写操作强制放行） -->
      <PlanApproval
        v-if="run.planProposal"
        :proposal="run.planProposal"
        @confirm="(steps) => $emit('plan-confirm', { run, steps })"
        @cancel-run="$emit('plan-cancel', run)"
      />

      <div class="z-steps">
        <StepCard v-for="s in procSteps" :key="s.index" :step="s" />
      </div>
    </div>

    <!-- 未命中候选卡：置于折叠区外，任务完成后仍可点击选项重跑 -->
    <ClarifyCard v-if="run.clarify" :clarify="run.clarify" @run="(action) => $emit('clarify-run', action)" />

    <!-- 回答：write 步流式生成时实时渲染，完成后为最终产出；直接铺在对话流里。
         候选澄清回合不渲染回答区——候选卡本身就是该回合的终点，点选后开新回合 -->
    <div v-if="answerText || (run.final.visible && !run.clarify)" class="z-answer" :class="{ streaming: !run.final.visible }">
      <ReportText v-if="answerText" :text="answerText" :streaming="!run.final.visible" />
      <span v-if="!run.final.visible && answerText" class="z-answer-cursor" aria-hidden="true"></span>
      <div v-if="run.final.visible && !answerText" class="z-answer-empty">
        {{ run.final.cancelled ? '停止时还没有产出内容，已完成的步骤可在上方回看' : '本次没有产出文本内容' }}
      </div>
      <div v-if="run.final.visible && answerText" class="z-answer-foot">
        <span v-for="(m, i) in run.final.meta" :key="i" class="z-answer-meta">{{ m }}</span>
        <span class="z-answer-actions">
          <button class="z-icon-btn" type="button" :title="copied ? '已复制' : '复制'" @click="copyFinal">{{ copied ? '✓ 已复制' : '⧉ 复制' }}</button>
          <button class="z-icon-btn" type="button" title="下载 .txt" @click="download('txt')">↓ txt</button>
          <button class="z-icon-btn" type="button" title="下载 .md" @click="download('md')">↓ md</button>
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import StepCard from './StepCard.vue'
import PlanApproval from './PlanApproval.vue'
import ReportText from './ReportText.vue'
import ClarifyCard from './ClarifyCard.vue'

const props = defineProps({
  run: { type: Object, required: true },
})
defineEmits(['plan-confirm', 'plan-cancel', 'clarify-run'])

/* 过程条目只含 tool / think 步：write 的流式产出直接作为回答在主线下方渲染 */
const procSteps = computed(() => props.run.steps.filter((s) => s && s.kind !== 'write'))

/* 回答文本：完成后用最终产出；运行中取 write 步的流式文本（尚无则不显示回答区） */
const answerText = computed(() => {
  if (props.run.final.visible) return props.run.final.output || ''
  const write = props.run.steps.find((s) => s && s.kind === 'write' && s.result)
  if (!write || !write.result.data) return ''
  const versions = write.result.data.versions || []
  return versions.length ? (versions[0].text || '') : ''
})

const copied = ref(false)

function fmtDuration(ms) {
  return ms < 1000 ? ms + 'ms' : (ms / 1000).toFixed(1) + 's'
}

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

/* ---------- 报告导出：.txt / .md 下载 ---------- */

function fileBase() {
  const d = new Date()
  const stamp = `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`
  const key = (props.run.final.summary || props.run.command || 'result')
    .slice(0, 16)
    .replace(/[\\/:*?"<>|\s]+/g, '')
  return `AgentFlow-${stamp}-${key}`
}

function download(ext) {
  const text = ext === 'md'
    ? `# ${props.run.command}\n\n${props.run.final.output}\n`
    : props.run.final.output
  const blob = new Blob([text], { type: `text/${ext === 'md' ? 'markdown' : 'plain'};charset=utf-8` })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `${fileBase()}.${ext}`
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(a.href)
}
</script>
