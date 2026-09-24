<template>
  <div class="pg-group">
    <div class="pg-tabs" role="tablist" aria-label="工具与自动化视图">
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
      <ToolsDrawer v-if="active === 'tools'" embedded />
      <McpPane v-else-if="active === 'mcp'" />
      <McpExposePane v-else-if="active === 'expose'" />
      <ScheduleDrawer v-else-if="active === 'morning'" embedded />
    </KeepAlive>
  </div>
</template>

<script setup>
/* 工具与自动化分组：工具管理 / MCP 服务 / 定时任务 合并为一个工作台入口。
   前两者都是「Agent 能调用什么」的来源，定时任务是「什么时候自动跑」，同属让 Agent 干活的配置。
   抽屉类面板传 embedded 走无遮罩的嵌入模式，关闭按钮由样式隐藏（标题让位给 tab） */
import { ref, watch } from 'vue'
import ToolsDrawer from './ToolsDrawer.vue'
import McpPane from './McpPane.vue'
import McpExposePane from './McpExposePane.vue'
import ScheduleDrawer from './ScheduleDrawer.vue'

const props = defineProps({
  /* 外部指定的初始子视图（tools/mcp/morning） */
  sub: { type: String, default: 'tools' },
})

const tabs = [
  { key: 'tools', label: '工具管理' },
  { key: 'mcp', label: 'MCP 服务' },
  { key: 'expose', label: '对外服务' },
  { key: 'morning', label: '定时任务' },
]
const active = ref(tabs.some((t) => t.key === props.sub) ? props.sub : 'tools')
watch(() => props.sub, (v) => {
  if (tabs.some((t) => t.key === v)) active.value = v
})
</script>
