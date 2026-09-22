<template>
  <div class="pg-group">
    <div class="pg-tabs" role="tablist" aria-label="模型视图">
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
      <LlmPane v-if="active === 'llm'" embedded />
      <UsagePane v-else-if="active === 'usage'" />
    </KeepAlive>
  </div>
</template>

<script setup>
/* 模型分组：模型接入 / 效能与成本 合并为一个工作台入口。
   档案增删改后顶栏徽标经 window 的 af-llm-changed 事件刷新（组件内直接派发，与分组层级无关） */
import { ref, watch } from 'vue'
import LlmPane from './LlmPane.vue'
import UsagePane from './UsagePane.vue'

const props = defineProps({
  /* 外部指定的初始子视图（llm/usage） */
  sub: { type: String, default: 'llm' },
})

const tabs = [
  { key: 'llm', label: '模型接入' },
  { key: 'usage', label: '效能与成本' },
]
const active = ref(tabs.some((t) => t.key === props.sub) ? props.sub : 'llm')
watch(() => props.sub, (v) => {
  if (tabs.some((t) => t.key === v)) active.value = v
})
</script>
