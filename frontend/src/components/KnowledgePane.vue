<template>
  <div class="pg-group">
    <div class="pg-tabs" role="tablist" aria-label="知识与记忆视图">
      <button
        v-for="t in tabs"
        :key="t.key"
        class="pg-tab"
        type="button"
        role="tab"
        :class="{ active: active === t.key }"
        :aria-selected="active === t.key"
        @click="active = t.key"
      >{{ t.label }}</button>
    </div>

    <KeepAlive>
      <KbPane v-if="active === 'kb'" @run="$emit('run', $event)" />
      <MemoryPane v-else-if="active === 'memory'" />
    </KeepAlive>
  </div>
</template>

<script setup>
/* 知识分组：知识库（上传文档，供检索）/ 记忆（跨会话偏好事实）合并为一个工作台入口。
   两者都是「个人知识沉淀」，子面板各自缓存，切 tab 不丢状态 */
import { ref, watch } from 'vue'
import KbPane from './KbPane.vue'
import MemoryPane from './MemoryPane.vue'

const props = defineProps({
  /* 外部指定的初始子视图（kb/memory） */
  sub: { type: String, default: 'kb' },
})
defineEmits(['run'])

const tabs = [
  { key: 'kb', label: '知识库' },
  { key: 'memory', label: '记忆' },
]
const active = ref(tabs.some((t) => t.key === props.sub) ? props.sub : 'kb')
watch(() => props.sub, (v) => {
  if (tabs.some((t) => t.key === v)) active.value = v
})
</script>
