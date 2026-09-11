<template>
  <div class="step-result">
    <div class="result-card">
      <div class="rc-label">执行结果</div>

      <!-- 列表 -->
      <div v-if="result.type === 'list'" class="list-block">
        <div v-for="(item, i) in result.list || []" :key="i" class="list-row">
          <span class="n">{{ String(i + 1).padStart(2, '0') }}</span>
          <span>{{ item }}</span>
        </div>
      </div>

      <!-- 文案版本（_streaming：write 步正在流式生成，带光标；完成后结构化排版） -->
      <div v-else-if="result.type === 'copy'" class="copy-list">
        <div v-for="(v, i) in r.versions || []" :key="i" class="copy-item">
          <span class="tag">{{ v.tag }}</span>
          <span v-if="result._streaming" class="txt">{{ v.text }}<span
            v-if="i === (r.versions || []).length - 1"
            class="stream-cursor"
            aria-hidden="true"
          ></span></span>
          <ReportText v-else :text="v.text" class="txt-rt" />
        </div>
      </div>

      <!-- 通用提示 -->
      <div v-else class="note-box">
        <span class="note-ico">ⓘ</span>
        <span>{{ r.note || JSON.stringify(r) }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import ReportText from './ReportText.vue'

const props = defineProps({
  result: { type: Object, required: true },
})

const r = computed(() => props.result.data || {})
</script>
