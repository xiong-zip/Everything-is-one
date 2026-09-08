<template>
  <div class="plan-approval">
    <div class="pa-head">
      <span class="pa-badge">待确认</span>
      <span class="pa-title">执行计划 · {{ proposal.steps.length }} 步<span v-if="skipped">（跳过 {{ skipped }} 步）</span></span>
      <span class="pa-state" :class="{ ok: proposal.confirmed }">{{ proposal.confirmed ? '已确认' : '等待确认' }}</span>
    </div>

    <div v-for="(s, i) in proposal.steps" :key="i" class="pa-step" :class="{ skip: s.skip }">
      <label v-if="waiting" class="pa-check" :title="s.skip ? '恢复该步骤' : '跳过该步骤'">
        <input v-model="s.skip" type="checkbox" />
      </label>
      <span v-else class="pa-mark">{{ s.skip ? '⤼' : '✓' }}</span>
      <div class="pa-main">
        <div class="pa-line">
          <span class="pa-kind" :class="s.kind">{{ kindIcon(s.kind) }} {{ s.tag }}</span>
          <span class="pa-step-title">{{ s.title }}</span>
          <span v-if="s.tool && s.tool.requiresConfirm" class="pa-warn">写操作 · 需确认</span>
          <span v-if="s.group > 0 && s.kind === 'tool'" class="pa-parallel">并行组 {{ s.group }}</span>
        </div>
        <div v-if="s.tool" class="pa-tool">
          <code>{{ s.tool.name }}</code>
          <span class="pa-tool-args">{{ argsText(s.tool.args) }}</span>
        </div>
        <textarea
          v-if="waiting && s.tool"
          v-model="s.argsJson"
          class="pa-args"
          rows="2"
          spellcheck="false"
          aria-label="编辑工具参数 JSON"
        ></textarea>
      </div>
    </div>

    <div v-if="waiting" class="pa-actions">
      <button class="btn btn-primary" type="button" @click="confirm">确认执行</button>
      <button class="btn btn-ghost" type="button" @click="$emit('cancel-run')">取消任务</button>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  proposal: { type: Object, required: true },
})
const emit = defineEmits(['confirm', 'cancel-run'])

const waiting = computed(() => props.proposal.waiting)
const skipped = computed(() => props.proposal.skipped || 0)

function kindIcon(kind) {
  return kind === 'tool' ? '⚙' : kind === 'think' ? '✎' : '✍'
}

function argsText(args) {
  if (args == null) return ''
  if (typeof args === 'string') return args
  return Object.entries(args).map(([k, v]) => `${k}: ${v}`).join(', ')
}

/* 参数 JSON 解析失败时保留原参数，不阻塞确认 */
function confirm() {
  const steps = props.proposal.steps.map((s) => {
    let args = (s.tool && s.tool.args) || {}
    if (s.argsJson != null) {
      try {
        args = JSON.parse(s.argsJson || '{}')
      } catch { /* 保留原参数 */ }
    }
    return {
      kind: s.kind,
      tag: s.tag,
      title: s.title,
      group: s.group || 0,
      skip: !!s.skip,
      tool: s.tool ? { name: s.tool.name, args } : null,
    }
  })
  emit('confirm', steps)
}
</script>
