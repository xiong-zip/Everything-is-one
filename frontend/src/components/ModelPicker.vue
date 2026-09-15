<template>
  <div class="mp" :class="{ open }">
    <button class="mp-pill" type="button" :title="'当前模型：' + currentLabel" @click.stop="toggle">
      <span class="mp-dot" :class="configured ? 'on' : 'off'" aria-hidden="true"></span>
      <span class="mp-label">{{ currentLabel }}</span>
      <span class="mp-chev" aria-hidden="true">▾</span>
    </button>

    <div v-if="open" class="mp-menu" role="menu" @click.stop>
      <div class="mp-menu-title">选择模型</div>
      <button
        v-for="p in profiles"
        :key="p.id"
        class="mp-item"
        :class="{ active: p.active }"
        type="button"
        role="menuitem"
        :disabled="switching"
        @click="select(p)"
      >
        <span class="mp-item-main">
          <span class="mp-item-name">{{ p.name }}</span>
          <span class="mp-item-model">{{ p.model }}</span>
        </span>
        <span v-if="p.active" class="mp-check" aria-hidden="true">✓</span>
        <span v-else-if="switchingId === p.id" class="mp-check" aria-hidden="true">…</span>
      </button>
      <div class="mp-menu-sep" aria-hidden="true"></div>
      <button
        class="mp-item"
        :class="{ active: source === 'env' }"
        type="button"
        role="menuitem"
        :disabled="switching"
        @click="selectEnv"
      >
        <span class="mp-item-main">
          <span class="mp-item-name">.env 默认配置</span>
          <span class="mp-item-model">{{ envModel || '未配置' }}</span>
        </span>
        <span v-if="source === 'env'" class="mp-check" aria-hidden="true">✓</span>
      </button>
      <div class="mp-menu-foot">档案在工作台 → 模型接入 里维护</div>
    </div>
  </div>
</template>

<script setup>
/* 主页面顶栏的模型下拉选择器：一键切换当前使用的模型（档案的增删改在工作台「模型接入」） */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const open = ref(false)
const profiles = ref([])
const source = ref('env')       // profile | env
const activeName = ref('')
const currentModel = ref('')
const envModel = ref('')
const configured = ref(false)
const switching = ref(false)
const switchingId = ref('')

const currentLabel = computed(() => {
  if (!configured.value) return '模拟模式'
  const name = source.value === 'profile' ? activeName.value : '.env 默认'
  return name && currentModel.value ? `${name} · ${currentModel.value}` : (currentModel.value || '未配置')
})

async function load() {
  try {
    const res = await fetch('/api/llm/profiles')
    if (res.ok) {
      const data = await res.json()
      profiles.value = data.profiles || []
      source.value = data.current?.source || 'env'
      activeName.value = data.current?.activeName || ''
      currentModel.value = data.current?.model || ''
      configured.value = !!data.current?.configured
    }
  } catch { /* 静默 */ }
  try {
    // .env 默认配置的模型名（与当前生效配置独立，恒为 .env 里的值）
    const res = await fetch('/api/llm/config')
    if (res.ok) envModel.value = (await res.json()).envModel || ''
  } catch { /* 静默 */ }
}

function toggle() {
  open.value = !open.value
  if (open.value) load()
}

async function select(p) {
  if (p.active || switching.value) return
  switching.value = true
  switchingId.value = p.id
  try {
    const res = await fetch(`/api/llm/profiles/${p.id}/activate`, { method: 'POST' })
    if (res.ok) {
      open.value = false
      await load()
      window.dispatchEvent(new CustomEvent('af-llm-changed'))
    }
  } catch { /* 静默 */ } finally {
    switching.value = false
    switchingId.value = ''
  }
}

async function selectEnv() {
  if (source.value === 'env' || switching.value) return
  switching.value = true
  try {
    const res = await fetch('/api/llm/active', { method: 'DELETE' })
    if (res.ok) {
      open.value = false
      await load()
      window.dispatchEvent(new CustomEvent('af-llm-changed'))
    }
  } catch { /* 静默 */ } finally {
    switching.value = false
  }
}

function onDocClick() {
  open.value = false
}
function onLlmChanged() {
  load()
}

onMounted(() => {
  load()
  document.addEventListener('click', onDocClick)
  window.addEventListener('af-llm-changed', onLlmChanged)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick)
  window.removeEventListener('af-llm-changed', onLlmChanged)
})
</script>
