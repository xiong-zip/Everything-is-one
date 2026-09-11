<template>
  <div class="wb-mask" @click.self="$emit('close')">
    <section class="wb-window" role="dialog" aria-modal="true" aria-label="工作台">
      <!-- 左：菜单 -->
      <nav class="wb-nav" aria-label="工作台菜单">
        <div class="wb-nav-head">工作台</div>
        <button
          v-for="t in tabs"
          :key="t.key"
          class="wb-nav-item"
          :class="{ active: active === t.key }"
          type="button"
          @click="active = t.key"
        >
          <span class="wb-nav-ico" aria-hidden="true">{{ t.icon }}</span>
          <span class="wb-nav-label">{{ t.label }}</span>
        </button>
      </nav>

      <!-- 右：内容 -->
      <div class="wb-body">
        <button class="wb-close" type="button" aria-label="关闭工作台" title="关闭（Esc）" @click="$emit('close')">✕</button>
        <TraceAnalysisPane v-if="active === 'trace'" @run="$emit('run', $event)" />
        <DbDrawer v-else-if="active === 'db'" embedded @active-changed="$emit('db-active-changed', $event)" />
        <GitlabDrawer v-else-if="active === 'gitlab'" embedded />
        <StatsDrawer v-else-if="active === 'stats'" embedded />
        <ScheduleDrawer v-else-if="active === 'morning'" embedded />
        <ToolsDrawer v-else-if="active === 'tools'" embedded />
      </div>
    </section>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import TraceAnalysisPane from './TraceAnalysisPane.vue'
import DbDrawer from './DbDrawer.vue'
import GitlabDrawer from './GitlabDrawer.vue'
import StatsDrawer from './StatsDrawer.vue'
import ScheduleDrawer from './ScheduleDrawer.vue'
import ToolsDrawer from './ToolsDrawer.vue'

const props = defineProps({
  // 打开时默认展示的面板
  tab: { type: String, default: 'trace' },
})
const emit = defineEmits(['close', 'run', 'db-active-changed'])

const tabs = [
  { key: 'trace', icon: '🔗', label: '链路分析' },
  { key: 'db', icon: '🗄', label: '数据库连接' },
  { key: 'gitlab', icon: '🦊', label: 'GitLab 账户' },
  { key: 'stats', icon: '▦', label: '效能热力图' },
  { key: 'morning', icon: '⏰', label: '晨报机器人' },
  { key: 'tools', icon: '⚙', label: '工具管理' },
]

const active = ref(tabs.some((t) => t.key === props.tab) ? props.tab : 'trace')

function onKeydown(e) {
  if (e.key === 'Escape') emit('close')
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>
