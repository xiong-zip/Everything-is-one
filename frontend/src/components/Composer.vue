<template>
  <div class="composer-wrap">
    <div class="composer">
      <!-- @ 工具选择下拉：命中 @前缀时悬浮在输入框上方，点选/回车插入 @工具名 -->
      <div v-if="toolMenu.open" class="tool-mention">
        <div class="tool-mention-list" role="listbox" aria-label="选择工具">
          <div v-if="!toolMenu.items.length" class="tool-mention-empty">没有匹配的工具</div>
          <button
            v-for="(t, i) in toolMenu.items"
            :key="t.name"
            type="button"
            role="option"
            class="tool-mention-item"
            :class="{ active: i === toolMenu.index }"
            @mousedown.prevent
            @click="pickTool(i)"
          >
            <code class="al-mono">{{ t.name }}</code>
            <span class="tool-mention-desc">{{ t.description }}</span>
            <span v-if="t.requiresConfirm" class="mcp-chip warn">写操作</span>
          </button>
        </div>
        <div class="tool-mention-tip">↑↓ 选择 · Enter/Tab 插入 · Esc 关闭 · @ 点名的工具将直达执行，不经 AI 规划</div>
      </div>
      <div class="composer-box">
        <label class="sr-only" for="commandInput">输入任务指令</label>
        <textarea
          id="commandInput"
          ref="inputEl"
          v-model="command"
          rows="1"
          :placeholder="'输入任何任务…（@ 可点名工具直达执行）'"
          @keydown="onKeydown"
          @input="onInput"
        ></textarea>
        <input
          ref="fileEl"
          class="sr-only"
          type="file"
          accept=".md,.txt,.csv,.json,.log,.html,.htm,.pdf,.docx,.xlsx"
          multiple
          @change="onFilesPicked"
        />
        <button
          class="btn btn-ghost attach-btn"
          :class="{ uploading }"
          type="button"
          :title="uploading ? '正在上传…' : '上传文档到个人知识库（md/txt/csv/json/log/html/pdf/docx/xlsx）'"
          aria-label="上传文档到知识库"
          :disabled="uploading"
          @click="fileEl && fileEl.click()"
        >{{ uploading ? '⏳' : '📎' }}</button>
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
        <div class="composer-left">
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
import { showToast } from '../composables/useToast'

const props = defineProps({
  busy: { type: Boolean, default: false },
  examples: { type: Array, default: () => [] },
  prefill: { type: Object, default: () => ({ command: '', nonce: 0 }) },
  confirmMode: { type: Boolean, default: false },
  dbProfiles: { type: Array, default: () => [] },
  dbActive: { type: String, default: '' },
})

const emit = defineEmits(['send', 'stop', 'toggle-confirm', 'db-active', 'kb-uploaded'])

function typeName(t) {
  return { dameng: '达梦', mysql: 'MySQL', postgresql: 'PostgreSQL', oracle: 'Oracle' }[t] || t
}

const command = ref('')
const inputEl = ref(null)
const fileEl = ref(null)
const uploading = ref(false)

/* 📎 上传到个人知识库：逐个上传，toast 汇报每个结果；完成后通知父组件刷新 */
async function onFilesPicked(e) {
  const files = [...(e.target.files || [])]
  e.target.value = '' // 允许重复选同一文件
  if (!files.length) return
  uploading.value = true
  let okCount = 0
  for (const f of files) {
    try {
      const fd = new FormData()
      fd.append('file', f)
      const res = await fetch('/api/kb/files', { method: 'POST', body: fd })
      const data = await res.json()
      if (!res.ok) throw new Error(data.error || '上传失败')
      okCount++
      showToast(`已入库 ${data.filename} · ${data.charCount} 字 / ${data.chunkCount} 块${data.replaced ? '（替换旧版本）' : ''}`)
    } catch (err) {
      showToast(`${f.name}：${err.message || '上传失败'}`, 'err')
    }
  }
  uploading.value = false
  if (okCount > 0) emit('kb-uploaded')
}

/* ---------- @ 工具点名：输入 @ 前缀唤起下拉，选中即插入 @工具名（引擎侧直达执行） ---------- */
const toolsCache = ref(null)
const toolMenu = ref({ open: false, query: '', start: -1, index: 0, items: [] })

async function loadTools() {
  if (toolsCache.value) return toolsCache.value
  try {
    const res = await fetch('/api/tools')
    const data = await res.json()
    toolsCache.value = Array.isArray(data) ? data.filter((t) => !t.disabled) : []
  } catch {
    toolsCache.value = []
  }
  return toolsCache.value
}

/** 光标前是否正在输入 @词；命中则返回 @ 的起点，否则关闭菜单 */
function updateToolMenu() {
  const el = inputEl.value
  if (!el) return
  const caret = el.selectionStart ?? 0
  const before = command.value.slice(0, caret)
  const m = before.match(/@([a-z0-9.-]*)$/)
  if (!m) {
    toolMenu.value.open = false
    return
  }
  const query = m[1]
  toolMenu.value = {
    open: true,
    query,
    start: caret - query.length - 1,
    index: 0,
    items: (toolsCache.value || []).filter((t) => t.name.includes(query)).slice(0, 8),
  }
}

/** 选中第 i 项：把「@已输入片段」替换为「@工具名 」并聚焦回输入框 */
function pickTool(i) {
  const item = toolMenu.value.items[i]
  if (!item || !inputEl.value) return
  const el = inputEl.value
  const caret = el.selectionStart ?? command.value.length
  const insert = '@' + item.name + ' '
  command.value = command.value.slice(0, toolMenu.value.start) + insert + command.value.slice(caret)
  toolMenu.value.open = false
  nextTick(() => {
    el.focus()
    const pos = toolMenu.value.start + insert.length
    el.setSelectionRange(pos, pos)
  })
}

function onInput(e) {
  autoResize(e)
  if (toolsCache.value) {
    updateToolMenu()
  } else {
    // 首次输入 @ 才拉工具清单，平时不给 /api/tools 添流量
    if ((e.target.value || '').includes('@')) loadTools().then(updateToolMenu)
  }
}

function send() {
  const text = command.value.trim()
  if (!text || props.busy) return
  emit('send', text)
  // 发送后清空输入框并复位高度与滚动条
  command.value = ''
  if (inputEl.value) {
    inputEl.value.style.height = 'auto'
    inputEl.value.style.overflowY = 'hidden'
  }
}

function onKeydown(e) {
  // @ 下拉打开时接管键盘：↑↓ 换项，Enter/Tab 插入，Esc 关闭（不触发发送）
  if (toolMenu.value.open && toolMenu.value.items.length) {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      const n = toolMenu.value.items.length
      toolMenu.value.index = (toolMenu.value.index + (e.key === 'ArrowDown' ? 1 : n - 1)) % n
      return
    }
    if (e.key === 'Enter' || e.key === 'Tab') {
      e.preventDefault()
      pickTool(toolMenu.value.index)
      return
    }
    if (e.key === 'Escape') {
      e.preventDefault()
      toolMenu.value.open = false
      return
    }
  }
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    send()
  }
}

/* 高度跟随内容增长，上限取 CSS 的 max-height（3 行）；只有超出上限才出现滚动条 */
function autoResize(e) {
  const el = e.target
  el.style.height = 'auto'
  const max = parseFloat(getComputedStyle(el).maxHeight) || 88
  el.style.height = Math.min(el.scrollHeight, max) + 'px'
  el.style.overflowY = el.scrollHeight > max ? 'auto' : 'hidden'
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
