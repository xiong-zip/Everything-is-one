<template>
  <div class="rt-md" v-html="html"></div>
</template>

<script setup>
import { computed } from 'vue'
import { mdToHtml } from '../utils/markdown'

/* 内容以 Markdown 预览呈现；报告类文本的日期行 / 【标题】行 / 计划项附加专属样式 */
const props = defineProps({
  text: { type: String, default: '' },
})

const html = computed(() => mdToHtml(props.text, { paraClass }))

function paraClass(t) {
  if (/^\d{4}-\d{2}-\d{2}（周[一二三四五六日]）/.test(t)) {
    return 'rt-date'
  }
  if (/^【.+?】/.test(t)) {
    return t.includes('计划') ? 'rt-plan-head' : 'rt-head'
  }
  return ''
}
</script>
