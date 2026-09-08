<template>
  <div class="agent-card">
    <div class="agent-bar">
      <span class="agent-avatar" aria-hidden="true">✦</span>
      <span class="agent-name">Agent 执行</span>
      <span v-if="run.replayed" class="replay-badge">历史回放</span>
      <span class="agent-status" :class="run.status.cls" role="status" aria-live="polite">● {{ run.status.text }}</span>
    </div>

    <div class="agent-body">
      <!-- 执行过程：运行中完整展示，完成后默认折叠只留任务汇总，可点开回看 -->
      <button
        v-if="run.final.visible"
        class="process-toggle"
        type="button"
        :aria-expanded="run.processExpanded ? 'true' : 'false'"
        @click="run.processExpanded = !run.processExpanded"
      >
        {{ run.processExpanded ? '▾' : '▸' }} 执行过程 · {{ visibleSteps.length }} 个子任务
      </button>

      <div v-show="!run.final.visible || run.processExpanded" class="process-block">
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
      </div>

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
          <button class="btn btn-ghost" type="button" @click="download('txt')">下载 .txt</button>
          <button class="btn btn-ghost" type="button" @click="download('md')">下载 .md</button>
          <button class="btn btn-ghost" type="button" @click="printFinal">打印 / PDF</button>
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

/* ---------- 报告导出：.txt / .md 下载 + 打印（浏览器打印为 PDF） ---------- */

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

function printFinal() {
  const w = window.open('', '_blank', 'width=760,height=960')
  if (!w) return
  const esc = (s) => (s || '').replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]))
  w.document.title = fileBase()
  w.document.write(
    `<!doctype html><html><head><meta charset="utf-8"><title>${esc(w.document.title)}</title></head>` +
    `<body style="margin:0"><pre style="font-family:Consolas,'Courier New',monospace;font-size:13px;` +
    `line-height:1.9;white-space:pre-wrap;word-break:break-word;padding:36px 40px;">${esc(props.run.final.output)}</pre></body></html>`
  )
  w.document.close()
  w.focus()
  w.print()
}
</script>
