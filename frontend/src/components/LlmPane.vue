<template>
  <div :class="embedded ? 'wb-pane' : 'drawer-mask'" @click.self="!embedded && $emit('close')">
    <aside :class="['history-drawer', 'llm-drawer', { 'wb-embed': embedded }]" role="dialog" aria-label="模型接入">
      <div class="hd-head">
        <h3>模型接入</h3>
        <div class="hd-actions">
          <button class="hd-close" type="button" aria-label="关闭" @click="$emit('close')">✕</button>
        </div>
      </div>

      <div class="llm-body">
        <div class="db-form-title">接口协议与凭据（保存后立即生效，无需重启）</div>
        <div class="db-grid">
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
            <input v-model="form.apiKey" class="db-input" type="password" autocomplete="off" :placeholder="apiKeyMasked ? '已配置 ' + apiKeyMasked + '，留空保持不变' : 'sk-…'" />
          </label>
          <label class="db-field">
            <span>模型名</span>
            <input v-model="form.model" class="db-input" :placeholder="form.provider === 'anthropic' ? 'claude-sonnet-4-5' : 'deepseek-v4-flash'" />
          </label>
        </div>

        <div class="db-form-actions">
          <button class="btn btn-ghost" type="button" :disabled="testing" @click="testConn">{{ testing ? '测试中…' : '测试连接' }}</button>
          <button class="btn btn-primary" type="button" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
          <button class="btn btn-ghost btn-danger" type="button" @click="reset">恢复默认</button>
        </div>

        <div v-if="message" class="td-message" :class="message.ok ? 'ok' : 'err'">{{ message.text }}</div>
        <div v-if="testDetail" class="llm-test-detail">{{ testDetail }}</div>

        <div class="gl-hint">
          <b>Anthropic 兼容</b>：POST {地址}/v1/messages，鉴权用 x-api-key 头（Claude 官方或自建网关）。<br />
          <b>OpenAI 兼容</b>：POST {地址}/chat/completions，鉴权用 Bearer 头（DeepSeek、GPT、OneAPI 等均可）。<br />
          未配置时回退 .env 的 DEEPSEEK_API_KEY 默认值；「恢复默认」清除界面配置回到该默认。
        </div>
      </div>
    </aside>
  </div>
</template>

<script setup>
/* embedded=true：工作台窗口内嵌渲染 */
defineProps({ embedded: { type: Boolean, default: false } })
defineEmits(['close'])

import { onMounted, reactive, ref } from 'vue'

const form = reactive({ provider: 'openai', baseUrl: '', apiKey: '', model: '' })
const apiKeyMasked = ref('')
const source = ref('env')
const message = ref(null) // {ok, text}
const testDetail = ref('')
const testing = ref(false)
const saving = ref(false)

async function load() {
  try {
    const res = await fetch('/api/llm/config')
    if (res.ok) {
      const cfg = await res.json()
      form.provider = cfg.provider || 'openai'
      form.baseUrl = cfg.baseUrl || ''
      form.model = cfg.model || ''
      apiKeyMasked.value = cfg.apiKeyMasked || ''
      source.value = cfg.source || 'env'
    }
  } catch { /* 静默 */ }
}

async function save() {
  saving.value = true
  message.value = null
  try {
    const res = await fetch('/api/llm/config', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...form }),
    })
    const data = await res.json().catch(() => ({}))
    if (res.ok) {
      message.value = { ok: true, text: '已保存并生效，当前模型：' + form.model }
      await load()
    } else {
      message.value = { ok: false, text: '保存失败：' + (data.error || data.message || res.status) }
    }
  } catch (e) {
    message.value = { ok: false, text: '保存失败：' + e.message }
  } finally {
    saving.value = false
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

async function reset() {
  if (!window.confirm('恢复 .env 默认配置？界面配置将被清除。')) return
  try {
    await fetch('/api/llm/config', { method: 'DELETE' })
    message.value = { ok: true, text: '已恢复默认配置' }
    await load()
  } catch (e) {
    message.value = { ok: false, text: '恢复失败：' + e.message }
  }
}

onMounted(load)
</script>
