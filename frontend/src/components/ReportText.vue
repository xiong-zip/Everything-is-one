<template>
  <div class="rt-md" v-html="html"></div>
</template>

<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { mdToHtml } from '../utils/markdown'

/* 内容以 Markdown 预览呈现；报告类文本的日期行 / 【标题】行 / 计划项附加专属样式 */
const props = defineProps({
  text: { type: String, default: '' },
  /* 流式生成中：此时 text 每攒够一小段就会变一次，而全量重解析的代价随已输出长度线性增长
     （整段生成累计下来是平方级）。流式期间把重解析节流到固定间隔，结束后再补一次最终结果。 */
  streaming: { type: Boolean, default: false },
})

/* 150ms 足够跟上生成速度，又比逐块重解析省掉绝大部分重复计算 */
const STREAM_REPARSE_MS = 150

const html = ref(mdToHtml(props.text, { paraClass }))
let timer = 0

watch(
  () => [props.text, props.streaming],
  () => {
    if (!props.streaming) {
      // 流式结束（或本来就是回放）：立刻按最终文本解析一次，保证与完成态一致
      clearTimeout(timer)
      timer = 0
      html.value = mdToHtml(props.text, { paraClass })
      return
    }
    if (timer) return
    timer = setTimeout(() => {
      timer = 0
      html.value = mdToHtml(props.text, { paraClass })
    }, STREAM_REPARSE_MS)
  }
)

onBeforeUnmount(() => clearTimeout(timer))

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
