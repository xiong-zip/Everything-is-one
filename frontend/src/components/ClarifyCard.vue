<template>
  <div class="clarify-card">
    <div class="cl-head">
      <span class="cl-badge">未命中</span>
      <span class="cl-question">{{ clarify.question }}</span>
    </div>

    <template v-if="!clarify.done">
      <button
        v-for="(o, i) in clarify.options"
        :key="i"
        class="cl-option"
        type="button"
        @click="$emit('run', o.action)"
      >
        <span class="cl-label">{{ o.label }}</span>
        <span class="cl-go" aria-hidden="true">↻ 重跑</span>
      </button>

      <div class="cl-custom">
        <input
          v-model="customText"
          class="cl-input"
          type="text"
          placeholder="都不是？输入你要找的内容…"
          aria-label="自定义搜索内容"
          @keydown.enter="submitCustom"
        />
        <button class="btn btn-primary cl-send" type="button" :disabled="!customText.trim()" @click="submitCustom">执行</button>
      </div>

      <div class="cl-foot">
        <button class="cl-end" type="button" @click="clarify.done = true">不用了，结束</button>
      </div>
    </template>
    <div v-else class="cl-ended">已结束选择</div>
  </div>
</template>

<script setup>
import { ref } from 'vue'

const props = defineProps({
  clarify: { type: Object, required: true },
})
const emit = defineEmits(['run'])

const customText = ref('')

function submitCustom() {
  const text = customText.value.trim()
  if (text) emit('run', text)
}
</script>
