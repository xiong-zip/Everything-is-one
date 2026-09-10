<template>
  <div class="rt-lines">
    <div v-for="(l, i) in lines" :key="i" class="rt-line" :class="l.type">{{ l.text }}</div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

/* 报告类纯文本的结构化排版：识别标题行 / 日期行 / 计划段，其余按正文渲染 */
const props = defineProps({
  text: { type: String, default: '' },
})

const lines = computed(() =>
  (props.text || '')
    .split('\n')
    .map((t) => t.trim())
    .filter(Boolean)
    .map((text) => ({ text, type: typeOf(text) }))
)

function typeOf(l) {
  if (/^【.+?】/.test(l)) {
    return l.includes('计划') ? 'plan-head' : 'head'
  }
  if (/^\d{4}-\d{2}-\d{2}（周[一二三四五六日]）/.test(l)) {
    return 'date'
  }
  if (/^\d+\s*[.、]/.test(l)) {
    return 'plan-item'
  }
  return 'body'
}
</script>
