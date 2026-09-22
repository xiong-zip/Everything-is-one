<template>
  <div class="pg-group">
    <!-- 二级 tab：三个同域面板合并展示，子面板自己的标题让位（见样式里的 hd-head 处理） -->
    <div class="pg-tabs" role="tablist" aria-label="可观测视图">
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

    <!-- 子面板各自缓存：切 tab 不丢状态、不重复拉数据；
         外层工作台的 KeepAlive 叠加在上面，整个分组被切走时告警轮询照样停 -->
    <KeepAlive>
      <TraceAnalysisPane v-if="active === 'trace'" @run="$emit('run', $event)" />
      <AlarmPane v-else-if="active === 'alarm'" />
      <ServiceMapPane v-else-if="active === 'svcs'" />
    </KeepAlive>
  </div>
</template>

<script setup>
/* 可观测分组：链路分析 / 告警值守 / 服务映射 三个 SigNoz 域面板合并为一个工作台入口 */
import { ref, watch } from 'vue'
import TraceAnalysisPane from './TraceAnalysisPane.vue'
import AlarmPane from './AlarmPane.vue'
import ServiceMapPane from './ServiceMapPane.vue'

const props = defineProps({
  /* 外部指定的初始子视图（trace/alarm/svcs），打开后由用户自行切换 */
  sub: { type: String, default: 'trace' },
})
defineEmits(['run'])

const tabs = [
  { key: 'trace', label: '链路分析' },
  { key: 'alarm', label: '告警值守' },
  { key: 'svcs', label: '服务映射' },
]
const active = ref(tabs.some((t) => t.key === props.sub) ? props.sub : 'trace')
watch(() => props.sub, (v) => {
  if (tabs.some((t) => t.key === v)) active.value = v
})
</script>
