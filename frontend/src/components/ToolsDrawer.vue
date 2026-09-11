<template>
  <div :class="embedded ? 'wb-pane' : 'drawer-mask'" @click.self="!embedded && $emit('close')">
    <aside :class="['history-drawer', 'tools-drawer', { 'wb-embed': embedded }]" role="dialog" aria-label="工具管理">
      <div class="hd-head">
        <h3>工具管理</h3>
        <div class="hd-actions">
          <button class="hd-close" type="button" aria-label="关闭" @click="$emit('close')">✕</button>
        </div>
      </div>

      <!-- OpenAPI 导入 -->
      <div class="td-import">
        <div class="td-import-label">从 OpenAPI / Swagger 文档导入工具（仅 GET 只读接口）</div>
        <div class="td-import-row">
          <input
            v-model="specUrl"
            class="td-input"
            type="url"
            placeholder="http://内网服务/v3/api-docs 或 /swagger.json"
            @keydown.enter="importOpenApi"
          />
          <button class="btn btn-primary td-import-btn" type="button" :disabled="importing || !specUrl.trim()" @click="importOpenApi">
            {{ importing ? '导入中…' : '导入' }}
          </button>
        </div>
        <div v-if="message" class="td-message" :class="message.cls">{{ message.text }}</div>
      </div>

      <!-- 工具列表：默认折叠，只显示名称与标签，展开才看描述 -->
      <div class="hd-list">
        <div v-if="loading" class="hd-empty">加载中…</div>
        <div v-else-if="!tools.length" class="hd-empty">还没有注册的工具</div>
        <details v-for="t in tools" :key="t.name" class="td-item">
          <summary class="td-summary">
            <span class="td-main">
              <span class="td-name">
                <code>{{ t.name }}</code>
                <span v-if="t.dynamic" class="td-tag dynamic">动态</span>
                <span v-if="t.requiresConfirm" class="td-tag warn">写操作</span>
              </span>
            </span>
            <button
              v-if="t.dynamic"
              class="hd-del"
              type="button"
              aria-label="删除该工具"
              @click.prevent.stop="removeTool(t.name)"
            >✕</button>
          </summary>
          <div class="td-detail">
            <div class="td-desc">{{ t.description }}</div>
          </div>
        </details>
      </div>
    </aside>
  </div>
</template>

<script setup>
/* embedded=true：作为工作台窗口内的面板渲染，去掉遮罩与固定侧栏宽度 */
defineProps({ embedded: { type: Boolean, default: false } })

import { onMounted, ref } from 'vue'

defineEmits(['close'])

const tools = ref([])
const loading = ref(false)
const specUrl = ref('')
const importing = ref(false)
const message = ref(null)

async function load() {
  loading.value = true
  try {
    const res = await fetch('/api/tools')
    if (res.ok) tools.value = await res.json()
  } catch { /* 静默 */ } finally {
    loading.value = false
  }
}

async function importOpenApi() {
  const url = specUrl.value.trim()
  if (!url || importing.value) return
  importing.value = true
  message.value = null
  try {
    const res = await fetch('/api/tools/openapi', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url }),
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || '导入失败')
    message.value = { text: `导入成功：${data.imported} 个工具（新增 ${data.created} 个），已可被任务规划调用`, cls: 'ok' }
    specUrl.value = ''
    await load()
  } catch (err) {
    message.value = { text: err.message || '导入失败，请检查文档地址', cls: 'err' }
  } finally {
    importing.value = false
  }
}

async function removeTool(name) {
  tools.value = tools.value.filter((t) => t.name !== name)
  try {
    await fetch(`/api/tools/${encodeURIComponent(name)}`, { method: 'DELETE' })
  } catch { /* 静默 */ }
}

onMounted(load)
</script>
