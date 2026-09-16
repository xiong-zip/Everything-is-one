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

      <!-- 变更关联：嫌疑提交排名 -->
      <div v-else-if="result.type === 'changes'" class="changes-block">
        <div v-if="r.failureTime" class="chg-meta">
          故障 {{ r.failureTime }} · 回溯 {{ r.windowHours }} 小时
          <template v-if="r.failureService"> · 失败点服务 <b>{{ r.failureService }}</b></template>
        </div>
        <div v-for="m in r.services || []" :key="m.service" class="chg-map">
          {{ m.service }} → {{ m.project }}（{{ m.source }}）
        </div>
        <div v-if="!(r.commits || []).length" class="list-row">
          <span class="n">—</span><span>窗口内没有相关提交（可扩大回溯小时数重试）</span>
        </div>
        <div v-for="c in r.commits || []" :key="c.rank" class="chg-row" :class="{ top: c.rank === 1 }">
          <span class="chg-rank" :class="'r' + Math.min(c.rank, 3)">#{{ c.rank }}</span>
          <div class="chg-main">
            <div class="chg-head">
              <span class="chg-score">{{ c.score }} 分</span>
              <code class="chg-sha">{{ c.sha }}</code>
              <span class="chg-author">{{ c.author }}</span>
              <span class="chg-time">{{ c.time }}</span>
            </div>
            <div class="chg-title">{{ c.title }}</div>
            <div class="chg-tags">
              <span v-for="t in c.reasons || []" :key="t" class="chg-tag">{{ t }}</span>
              <span class="chg-proj">{{ c.project }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 知识库命中 -->
      <div v-else-if="result.type === 'kb'" class="kb-block">
        <div v-if="r.query || r.fileCount != null || r.file" class="kb-meta">
          <template v-if="r.query">检索「{{ r.query }}」· 命中 {{ r.matchCount }} 块</template>
          <template v-else-if="r.file">{{ r.file }} · {{ r.chunkCount }} 块</template>
          <template v-else>知识库 {{ r.fileCount }} 个文件</template>
        </div>
        <div v-for="(h, i) in r.hits || []" :key="i" class="kb-row">
          <div class="kb-row-head">
            <span class="kb-row-file">{{ h.file }}</span>
            <span class="kb-row-seq">块 {{ h.seq }}</span>
            <span class="kb-row-score">{{ h.score }} 分</span>
          </div>
          <div class="kb-row-body">{{ h.excerpt }}</div>
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
