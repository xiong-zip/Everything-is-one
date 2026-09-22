<template>
  <div class="pg-group">
    <div class="pg-tabs" role="tablist" aria-label="外部接入视图">
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
      <DbDrawer v-if="active === 'db'" embedded @active-changed="$emit('active-changed', $event)" />
      <GitlabPane v-else-if="active === 'gitlab'" />
    </KeepAlive>
  </div>
</template>

<script setup>
/* 外部接入分组：数据库连接 / GitLab 效能 合并为一个工作台入口。
   两者都是对外部系统的接入配置（地址 + 凭证）及其上的效能视图，凭证在各面板内维护 */
import { ref, watch } from 'vue'
import DbDrawer from './DbDrawer.vue'
import GitlabPane from './GitlabPane.vue'

const props = defineProps({
  /* 外部指定的初始子视图（db/gitlab） */
  sub: { type: String, default: 'db' },
})
/* 默认连接切换要透传到工作台外层：输入区的库选择器与它联动 */
defineEmits(['active-changed'])

const tabs = [
  { key: 'db', label: '数据库连接' },
  { key: 'gitlab', label: 'GitLab 效能' },
]
const active = ref(tabs.some((t) => t.key === props.sub) ? props.sub : 'db')
watch(() => props.sub, (v) => {
  if (tabs.some((t) => t.key === v)) active.value = v
})
</script>
