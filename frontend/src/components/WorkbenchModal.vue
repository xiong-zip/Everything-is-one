<template>
  <div class="wb-mask" @click.self="$emit('close')">
    <section class="wb-window" role="dialog" aria-modal="true" aria-label="工作台">
      <!-- 左：菜单（五个工作区分组 + 底部独立的通用设置） -->
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

        <!-- 通用设置是浏览器偏好（主题），不是工作区面板，沉到底部与上面的分组分开 -->
        <div class="wb-nav-gap" aria-hidden="true"></div>
        <button
          class="wb-nav-item"
          :class="{ active: active === UTILITY.key }"
          type="button"
          @click="active = UTILITY.key"
        >
          <span class="wb-nav-ico" aria-hidden="true">{{ UTILITY.icon }}</span>
          <span class="wb-nav-label">{{ UTILITY.label }}</span>
        </button>
      </nav>

      <!-- 右：内容 -->
      <div class="wb-body">
        <button class="wb-close" type="button" aria-label="关闭工作台" title="关闭（Esc）" @click="$emit('close')">✕</button>
        <!-- 面板用 KeepAlive 缓存：原来 v-if 切换会整块卸载重建，每次回来都要重新拉一遍数据、
             滚动位置与筛选条件也全丢。max 限制常驻数量，未访问过的分组不会被提前创建。 -->
        <KeepAlive :max="MAX_CACHED_PANES">
          <ObsPane v-if="active === 'obs'" :sub="obsSub" @run="$emit('run', $event)" />
          <ToolkitPane v-else-if="active === 'toolkit'" :sub="toolkitSub" />
          <AccessPane v-else-if="active === 'access'" :sub="accessSub" @active-changed="$emit('db-active-changed', $event)" />
          <KnowledgePane v-else-if="active === 'knowledge'" :sub="knowledgeSub" @run="$emit('run', $event)" />
          <ModelPane v-else-if="active === 'model'" :sub="modelSub" />
          <SettingsPane v-else-if="active === 'general'" />
        </KeepAlive>
      </div>
    </section>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import ObsPane from './ObsPane.vue'
import ModelPane from './ModelPane.vue'
import KnowledgePane from './KnowledgePane.vue'
import SettingsPane from './SettingsPane.vue'
import ToolkitPane from './ToolkitPane.vue'
import AccessPane from './AccessPane.vue'

const props = defineProps({
  // 打开时默认展示的面板
  tab: { type: String, default: 'trace' },
})
const emit = defineEmits(['close', 'run', 'db-active-changed'])

/* 五个工作区分组。原先 9 个平铺菜单按域收敛：同域面板不再各占一个入口，
   改由分组内的二级 tab 切换（面板本身没变，只是入口少了） */
const tabs = [
  { key: 'obs', icon: '🔗', label: '可观测' },
  { key: 'toolkit', icon: '🧰', label: '工具与自动化' },
  { key: 'access', icon: '🔌', label: '外部接入' },
  { key: 'knowledge', icon: '📚', label: '知识与记忆' },
  { key: 'model', icon: '🤖', label: '模型与成本' },
]
const UTILITY = { key: 'general', icon: '✦', label: '通用设置' }

/* 分组 key → 二级子视图 key */
const GROUP_OF = {
  trace: 'obs', alarm: 'obs', svcs: 'obs',
  tools: 'toolkit', mcp: 'toolkit', expose: 'toolkit', morning: 'toolkit',
  db: 'access', gitlab: 'access', wecom: 'access',
  kb: 'knowledge', memory: 'knowledge',
  llm: 'model', usage: 'model',
}
const SUB_OF = {
  trace: 'trace', alarm: 'alarm', svcs: 'svcs',
  tools: 'tools', mcp: 'mcp', expose: 'expose', morning: 'morning',
  db: 'db', gitlab: 'gitlab', wecom: 'wecom',
  kb: 'kb', memory: 'memory',
  llm: 'llm', usage: 'usage',
}

/* 传入 key 可以是分组 key、子视图 key 或旧入口 key，统一解析到分组与默认子视图 */
function initialSub(group, fallback) {
  const sub = SUB_OF[props.tab]
  return sub && GROUP_OF[props.tab] === group ? sub : fallback
}
const obsSub = ref(initialSub('obs', 'trace'))
const toolkitSub = ref(initialSub('toolkit', 'tools'))
const accessSub = ref(initialSub('access', 'db'))
const knowledgeSub = ref(initialSub('knowledge', 'kb'))
const modelSub = ref(initialSub('model', 'llm'))
const KEYS = [...tabs.map((t) => t.key), UTILITY.key]
const active = ref(KEYS.includes(props.tab) ? props.tab : (GROUP_OF[props.tab] || 'obs'))

/* 常驻缓存的分组数上限：超过按最近使用淘汰。
   缓存单位是分组（每个含 2~3 个子面板，子面板在分组内各自 KeepAlive），
   所以这里按分组数设小一点，避免整套面板都留在内存里 */
const MAX_CACHED_PANES = 4

function onKeydown(e) {
  if (e.key === 'Escape') emit('close')
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>
