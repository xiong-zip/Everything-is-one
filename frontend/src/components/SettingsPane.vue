<template>
  <div class="settings-pane">
    <h3 class="sp-title">通用设置</h3>
    <p class="sp-desc">偏好保存在本机浏览器里，立即生效、全站通用。</p>

    <section class="sp-section">
      <div class="sp-section-head">
        <span class="sp-section-name">主题</span>
        <span class="sp-section-hint">当前：{{ themes.find(t => t.key === theme)?.label }}</span>
      </div>
      <div class="sp-theme-grid">
        <button
          v-for="t in themes"
          :key="t.key"
          class="sp-theme-card"
          :class="{ active: theme === t.key }"
          type="button"
          @click="apply(t.key)"
        >
          <span class="sp-theme-preview" :data-theme="t.key" aria-hidden="true">
            <span class="sp-pt-bar" :style="{ background: t.colors[0] }"></span>
            <span class="sp-pt-body" :style="{ background: t.colors[1] }">
              <span class="sp-pt-line" :style="{ background: t.colors[2] }"></span>
              <span class="sp-pt-line short" :style="{ background: t.colors[3] }"></span>
              <span class="sp-pt-accent" :style="{ background: t.colors[4] }"></span>
            </span>
          </span>
          <span class="sp-theme-name">{{ t.label }}</span>
          <span class="sp-theme-desc">{{ t.desc }}</span>
          <span v-if="theme === t.key" class="sp-theme-check" aria-hidden="true">✓ 使用中</span>
        </button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref } from 'vue'

const themes = [
  {
    key: 'mint',
    label: '清新薄荷',
    desc: '浅色 · 薄荷青绿主色',
    colors: ['#d2f2e2', '#ffffff', '#dfece5', '#ecf4ef', '#2fbd87'],
  },
  {
    key: 'dark',
    label: '深色 IDE',
    desc: '炭黑底 · 钢蓝主色',
    colors: ['#223048', '#1b1d21', '#2b2f36', '#1f2126', '#4c8dff'],
  },
]

const theme = ref(document.documentElement.getAttribute('data-theme') || 'mint')

function apply(key) {
  theme.value = key
  document.documentElement.setAttribute('data-theme', key)
  localStorage.setItem('af-theme', key)
}
</script>
