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
        <details v-for="t in tools" :key="t.name" class="td-item" :class="{ off: t.disabled }">
          <summary class="td-summary">
            <span class="td-main">
              <span class="td-name">
                <code>{{ t.name }}</code>
                <span v-if="t.dynamic" class="td-tag dynamic">动态</span>
                <span v-if="t.requiresConfirm" class="td-tag warn">写操作</span>
                <span v-if="t.disabled" class="td-tag off-tag">已停用</span>
              </span>
              <!-- 折叠态也带一行描述：工具名是英文标识，不点开根本不知道是干什么的 -->
              <span class="td-desc-line" :title="t.description">{{ t.description }}</span>
            </span>
            <span class="td-ops">
              <button
                class="td-op"
                type="button"
                :title="t.disabled ? '重新启用：恢复进入规划提示词' : '停用：不进规划提示词，直接省 token'"
                @click.prevent.stop="toggleDisabled(t)"
              >{{ t.disabled ? '启用' : '停用' }}</button>
              <button
                v-if="t.dynamic"
                class="td-op"
                type="button"
                title="编辑名称与描述"
                @click.prevent.stop="startEdit(t)"
              >编辑</button>
              <button
                v-if="t.dynamic"
                class="hd-del"
                type="button"
                aria-label="删除该工具"
                @click.prevent.stop="removeTool(t.name)"
              >✕</button>
            </span>
          </summary>
          <div class="td-detail">
            <!-- 动态工具编辑：名称与描述都是规划 prompt 里给模型看的内容，改描述能提升工具选择准确度 -->
            <div v-if="editing === t.name" class="td-edit">
              <label class="td-edit-field">
                <span>工具名</span>
                <input v-model="editForm.name" class="td-input" />
              </label>
              <label class="td-edit-field">
                <span>描述（模型据此判断何时用这个工具）</span>
                <textarea v-model="editForm.description" class="td-input" rows="3"></textarea>
              </label>
              <div class="td-edit-actions">
                <button class="btn btn-primary" type="button" :disabled="saving" @click="saveEdit(t)">
                  {{ saving ? '保存中…' : '保存' }}
                </button>
                <button class="btn btn-ghost" type="button" @click="editing = null">取消</button>
              </div>
            </div>
            <template v-else>
              <div class="td-desc">{{ t.description }}</div>
              <div v-if="t.argsHint && t.argsHint !== '{}'" class="td-args">
                <span class="td-args-label">参数</span>
                <code>{{ t.argsHint }}</code>
              </div>
            </template>
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
import { api, toastError } from '../api/client'

defineEmits(['close'])

const tools = ref([])
const loading = ref(false)
const specUrl = ref('')
const importing = ref(false)
const message = ref(null)
const editing = ref(null)
const saving = ref(false)
const editForm = ref({ name: '', description: '' })

async function load() {
  loading.value = true
  try {
    tools.value = await api.get('/api/tools')
  } catch (err) {
    toastError(err)
  } finally {
    loading.value = false
  }
}

/* 停用/启用：停用后不进规划提示词（省 token、聚焦工具集），执行入口保留 */
async function toggleDisabled(t) {
  const next = !t.disabled
  t.disabled = next
  try {
    await api.put(`/api/tools/${encodeURIComponent(t.name)}/disabled`, { disabled: next })
    message.value = { text: next ? `${t.name} 已停用，不再进入规划提示词` : `${t.name} 已启用`, cls: 'ok' }
    await load()
  } catch (err) {
    t.disabled = !next
    toastError(err)
  }
}

function startEdit(t) {
  editing.value = t.name
  editForm.value = { name: t.name, description: t.description || '' }
}

async function saveEdit(t) {
  if (saving.value) return
  saving.value = true
  try {
    const data = await api.put(`/api/tools/${encodeURIComponent(t.name)}`, {
      name: editForm.value.name.trim(),
      description: editForm.value.description.trim(),
    })
    message.value = { text: `已保存${data.name !== t.name ? `，工具更名为 ${data.name}` : ''}`, cls: 'ok' }
    editing.value = null
    await load()
  } catch (err) {
    toastError(err)
  } finally {
    saving.value = false
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
  if (!window.confirm(`确定删除工具 ${name}？此操作不可撤销（内置工具请改用停用）。`)) return
  tools.value = tools.value.filter((t) => t.name !== name)
  try {
    await api.del(`/api/tools/${encodeURIComponent(name)}`)
  } catch (err) {
    toastError(err)
    await load()
  }
}

onMounted(load)
</script>
