<template>
  <div :class="embedded ? 'wb-pane' : 'drawer-mask'" @click.self="!embedded && $emit('close')">
    <aside :class="['history-drawer', 'llm-drawer', { 'wb-embed': embedded }]" role="dialog" aria-label="模型接入">
      <div class="hd-head">
        <h3>模型接入</h3>
        <div class="hd-actions">
          <button v-if="mode === 'list'" class="btn btn-primary lp-add" type="button" @click="openCreate">＋ 新增</button>
          <button class="hd-close" type="button" aria-label="关闭" @click="$emit('close')">✕</button>
        </div>
      </div>

      <div class="llm-body">
        <!-- ============ 列表模式：档案卡 + 一键切换 ============ -->
        <template v-if="mode === 'list'">
          <div class="lp-current">
            <template v-if="current.source === 'profile'">
              <span class="lp-current-dot" aria-hidden="true"></span>
              当前使用：<b>{{ current.activeName }}</b>（{{ current.model }}）
            </template>
            <template v-else>
              <span class="lp-current-dot env" aria-hidden="true"></span>
              当前使用：<b>.env 默认配置</b>（{{ current.model || '未配置' }}）
            </template>
          </div>

          <div v-if="profiles.length === 0" class="lp-empty">
            还没有配置档案，点右上角「＋ 新增」添加一套模型接入。
          </div>

          <div class="lp-list">
            <div
              v-for="p in profiles"
              :key="p.id"
              class="lp-card"
              :class="{ active: p.active }"
            >
              <div class="lp-card-head">
                <span class="lp-name">{{ p.name }}</span>
                <span class="lp-proto">{{ p.provider === 'anthropic' ? 'Anthropic 兼容' : 'OpenAI 兼容' }}</span>
                <span v-if="p.active" class="lp-badge">使用中</span>
              </div>
              <div class="lp-card-meta">
                <span class="lp-model">{{ p.model }}</span>
                <span class="lp-url">{{ p.baseUrl }}</span>
                <span v-if="p.apiKeyMasked" class="lp-key">key {{ p.apiKeyMasked }}</span>
              </div>
              <div class="lp-card-actions">
                <button class="btn btn-ghost lp-act" type="button" @click="openEdit(p)">编辑</button>
                <button class="btn btn-ghost btn-danger lp-act" type="button" :disabled="busyId === p.id" @click="remove(p)">删除</button>
              </div>
            </div>
          </div>

          <div class="gl-hint">
            这里维护模型档案（新增 / 编辑 / 删除）；<b>切换当前使用的模型请用页面顶部的模型下拉框</b>。
          </div>
        </template>

        <!-- ============ 表单模式：新增 / 编辑 ============ -->
        <template v-else>
          <div class="db-form-title">{{ editing ? '编辑档案：' + editing.name : '新增模型档案' }}</div>
          <div class="db-grid">
            <label class="db-field db-w2">
              <span>档案名称</span>
              <input v-model="form.name" class="db-input" placeholder="如：DeepSeek 官方 / 公司网关" />
            </label>
            <label class="db-field db-w2">
              <span>接口类型</span>
              <select v-model="form.provider" class="db-input">
                <option value="anthropic">Anthropic 兼容（Claude 等）</option>
                <option value="openai">OpenAI 兼容（DeepSeek / GPT / 网关等）</option>
              </select>
            </label>
            <label class="db-field db-w2">
              <span>请求地址</span>
              <input v-model="form.baseUrl" class="db-input" :placeholder="form.provider === 'anthropic' ? 'https://api.anthropic.com' : 'https://api.deepseek.com'" />
            </label>
            <label class="db-field">
              <span>API Key</span>
              <input v-model="form.apiKey" class="db-input" type="password" autocomplete="off" :placeholder="editing && editing.apiKeyMasked ? '已配置 ' + editing.apiKeyMasked + '，留空保持不变' : 'sk-…'" />
            </label>
            <label class="db-field">
              <span>模型名</span>
              <input v-model="form.model" class="db-input" :placeholder="form.provider === 'anthropic' ? 'claude-sonnet-4-5' : 'deepseek-v4-flash'" />
            </label>
          </div>

          <div class="db-form-actions">
            <button class="btn btn-ghost" type="button" @click="mode = 'list'">取消</button>
            <button class="btn btn-ghost" type="button" :disabled="testing" @click="testConn">{{ testing ? '测试中…' : '测试连接' }}</button>
            <button class="btn btn-primary" type="button" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
          </div>
        </template>

        <div v-if="message" class="td-message" :class="message.ok ? 'ok' : 'err'">{{ message.text }}</div>
        <div v-if="testDetail" class="llm-test-detail">{{ testDetail }}</div>

        <div v-if="mode === 'list'" class="gl-hint">
          <b>Anthropic 兼容</b>：POST {地址}/v1/messages，鉴权用 x-api-key 头。<br />
          <b>OpenAI 兼容</b>：POST {地址}/chat/completions，鉴权用 Bearer 头（DeepSeek、GPT、OneAPI 等均可）。
        </div>
      </div>
    </aside>
  </div>
</template>

<script setup>
/* embedded=true：工作台窗口内嵌渲染。多档案：列表一键切换，表单增改 */
defineProps({ embedded: { type: Boolean, default: false } })
defineEmits(['close'])

import { onMounted, reactive, ref } from 'vue'

const mode = ref('list') // list | form
const profiles = ref([])
const current = ref({ source: 'env', model: '', activeName: '' })
const editing = ref(null) // 编辑中的档案（null = 新增）
const busyId = ref('')
const message = ref(null) // {ok, text}
const testDetail = ref('')
const testing = ref(false)
const saving = ref(false)

const emptyForm = () => ({ name: '', provider: 'openai', baseUrl: '', apiKey: '', model: '' })
const form = reactive(emptyForm())

async function load() {
  try {
    const res = await fetch('/api/llm/profiles')
    if (res.ok) {
      const data = await res.json()
      profiles.value = data.profiles || []
      current.value = data.current || current.value
    }
  } catch { /* 静默 */ }
}

function openCreate() {
  Object.assign(form, emptyForm())
  editing.value = null
  message.value = null
  testDetail.value = ''
  mode.value = 'form'
}

function openEdit(p) {
  Object.assign(form, { name: p.name, provider: p.provider, baseUrl: p.baseUrl, apiKey: '', model: p.model })
  editing.value = p
  message.value = null
  testDetail.value = ''
  mode.value = 'form'
}

async function save() {
  saving.value = true
  message.value = null
  try {
    const res = await fetch(editing.value ? `/api/llm/profiles/${editing.value.id}` : '/api/llm/profiles', {
      method: editing.value ? 'PUT' : 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(form),
    })
    const data = await res.json().catch(() => ({}))
    if (res.ok) {
      message.value = { ok: true, text: editing.value ? '已保存' + (editing.value.active ? '并生效' : '') : '已新增，可到页面顶部的下拉框启用' }
      mode.value = 'list'
      await load()
      /* 通知顶栏徽标刷新 */
      window.dispatchEvent(new CustomEvent('af-llm-changed'))
    } else {
      message.value = { ok: false, text: '保存失败：' + (data.error || data.message || res.status) }
    }
  } catch (e) {
    message.value = { ok: false, text: '保存失败：' + e.message }
  } finally {
    saving.value = false
  }
}

async function remove(p) {
  if (!window.confirm(`删除档案「${p.name}」？${p.active ? '它正在使用中，删除后将回退 .env 默认配置。' : ''}`)) return
  busyId.value = p.id
  try {
    await fetch(`/api/llm/profiles/${p.id}`, { method: 'DELETE' })
    message.value = { ok: true, text: '已删除 ' + p.name }
    await load()
    window.dispatchEvent(new CustomEvent('af-llm-changed'))
  } catch (e) {
    message.value = { ok: false, text: '删除失败：' + e.message }
  } finally {
    busyId.value = ''
  }
}

async function testConn() {
  testing.value = true
  message.value = null
  testDetail.value = ''
  try {
    const res = await fetch('/api/llm/test', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...form }),
    })
    const data = await res.json()
    if (data.ok) {
      message.value = { ok: true, text: `连接成功（${data.ms}ms）` }
      testDetail.value = `模型 ${data.model} 回复：${(data.reply || '').trim().slice(0, 60)}`
    } else {
      message.value = { ok: false, text: '连接失败：' + (data.error || '未知错误') }
    }
  } catch (e) {
    message.value = { ok: false, text: '连接失败：' + e.message }
  } finally {
    testing.value = false
  }
}

onMounted(load)
</script>
