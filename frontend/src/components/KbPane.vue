<template>
  <div class="wb-pane pane-col">
    <!-- ===== 详情视图：面包屑导航（根级可点返回列表） ===== -->
    <template v-if="detail">
      <div class="hd-head">
        <nav class="crumbs" aria-label="页面路径">
          <button class="crumb" type="button" @click="back">📚 知识库</button>
          <span class="crumb-sep" aria-hidden="true">›</span>
          <span class="crumb-cur" :title="detail.filename">{{ detail.filename }}</span>
        </nav>
      </div>

      <div class="pane-body">
        <div class="kb-detail-meta">
          <span class="kb-card-ico">{{ iconOf(detail.filename) }}</span>
          <div class="kb-detail-facts">
            <b>{{ detail.sizeText }}</b> · <b>{{ detail.charCount }}</b> 字 · <b>{{ detail.chunkCount }}</b> 块
            <span class="kb-detail-date">上传于 {{ detail.createdAt }}</span>
          </div>
          <div class="kb-detail-ops">
            <button class="btn btn-primary" type="button" @click="askAbout(detail)">
              ✦ 基于此文档提问
            </button>
            <button class="btn btn-ghost" type="button" :disabled="detailLoading" @click="reloadDetail">
              {{ detailLoading ? '加载中…' : '刷新全文' }}
            </button>
            <button class="btn btn-ghost" type="button" @click="remove(detail)">删除</button>
          </div>
        </div>

        <div class="kb-reader">
          <div class="kb-reader-head">全文（{{ detail.chunkCount }} 块 · 分块即检索粒度）</div>
          <pre v-if="detailLoading" class="kb-reader-body">加载中…</pre>
          <pre v-else class="kb-reader-body">{{ detailText || '（无内容）' }}</pre>
        </div>
      </div>
    </template>

    <!-- ===== 列表视图：工具栏 + 卡片墙 ===== -->
    <template v-else>
      <div class="hd-head">
        <h3>知识库</h3>
        <span class="hd-actions pane-count">共 {{ files.length }} 篇</span>
      </div>

      <div class="pane-body">
        <p class="gl-hint">
          上传文档建立<b>个人知识库</b>：解析为纯文本分块索引，聊天里问「<b>根据知识库，XXX</b>」即可基于文档作答（答案注明来源）。
          支持 md/txt/csv/json/log/html/pdf/docx/xlsx，同名重传即替换；点卡片查看全文。
        </p>

        <div class="kb-toolbar">
          <input
            ref="fileEl"
            class="sr-only"
            type="file"
            accept=".md,.txt,.csv,.json,.log,.html,.htm,.pdf,.docx,.xlsx"
            multiple
            @change="onFilesPicked"
          />
          <button class="btn btn-primary" type="button" :disabled="uploading" @click="fileEl && fileEl.click()">
            {{ uploading ? '上传中…' : '⬆ 上传文档' }}
          </button>
          <input
            v-model="searchQ"
            class="db-input kb-search-input"
            placeholder="试搜：输入问题看命中哪些片段"
            @keydown.enter.prevent="doSearch"
          />
          <button class="btn btn-ghost" type="button" :disabled="!searchQ.trim() || searching" @click="doSearch">
            {{ searching ? '搜索中…' : '试搜' }}
          </button>
        </div>
        <div v-if="message" class="td-message" :class="message.cls">{{ message.text }}</div>

        <!-- 向量检索状态：未配置提示关键词模式；已配置展示索引进度与重建入口 -->
        <div class="kb-vector" :class="{ on: vector.enabled }">
          <template v-if="vector.enabled">
            <span class="kb-vector-dot" aria-hidden="true"></span>
            <span>
              语义检索已启用 · 模型 <code>{{ vector.model }}</code> · 已索引
              <b>{{ vector.embeddedChunks || 0 }}</b>/{{ vector.totalChunks || 0 }} 块
              <template v-if="vector.embeddedChunks < vector.totalChunks">（缺失的部分走关键词打分）</template>
            </span>
            <button
              class="btn btn-ghost kb-vector-rebuild"
              type="button"
              :disabled="rebuilding"
              @click="rebuildVector"
            >{{ rebuilding ? '重建中…' : '重建索引' }}</button>
          </template>
          <template v-else>
            <span class="kb-vector-dot off" aria-hidden="true"></span>
            <span>当前为关键词检索；在 .env 配置 <code>AGENTFLOW_EMBED_URL</code> / <code>AGENTFLOW_EMBED_MODEL</code>（可选 <code>AGENTFLOW_EMBED_KEY</code>）并重启，即可启用「语义 + 关键词」混合检索</span>
          </template>
        </div>

        <!-- 试搜结果：点击命中可直接跳到对应文件详情 -->
        <div v-if="hits" class="kb-hits">
          <div class="kb-hits-head">
            {{ hits.matchCount != null ? `命中 ${hits.matchCount} 块，前 ${(hits.hits || []).length} 块：` : '' }}{{ hits.note || '' }}
          </div>
          <button v-for="(h, i) in hits.hits || []" :key="i" class="kb-hit" type="button" @click="openByName(h.file)">
            <div class="kb-hit-head">
              <code>{{ h.file }}</code><span>块 {{ h.seq }}</span><span class="kb-score">{{ h.score }} 分</span>
            </div>
            <div class="kb-hit-body">{{ h.excerpt }}</div>
          </button>
        </div>

        <!-- 卡片墙 -->
        <div v-if="loading" class="hd-empty">加载中…</div>
        <div v-else-if="!files.length" class="hd-empty">
          知识库还是空的<br /><small>上传部署手册、规范、FAQ 等文档后，聊天即可基于它们作答</small>
        </div>
        <div v-else class="kb-grid">
          <button v-for="f in files" :key="f.id" class="kb-card" type="button" @click="openDetail(f)">
            <span class="kb-card-ico">{{ iconOf(f.filename) }}</span>
            <span class="kb-card-name" :title="f.filename">{{ f.filename }}</span>
            <span class="kb-card-meta">{{ f.charCount }} 字 · {{ f.chunkCount }} 块</span>
            <span class="kb-card-foot">{{ f.sizeText }} · {{ f.createdAt }}</span>
          </button>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { api, toastError } from '../api/client'
import { showToast } from '../composables/useToast'

const emit = defineEmits(['run'])

const files = ref([])
const loading = ref(false)
const uploading = ref(false)
const fileEl = ref(null)
const message = ref(null)
const searchQ = ref('')
const searching = ref(false)
const hits = ref(null)
// 向量检索状态（未配置时 enabled=false，界面提示关键词模式）
const vector = ref({})
const rebuilding = ref(false)
// 详情视图状态
const detail = ref(null)
const detailText = ref('')
const detailLoading = ref(false)

function iconOf(filename) {
  const ext = (filename.split('.').pop() || '').toLowerCase()
  return {
    md: '📝', txt: '📄', csv: '📊', json: '🧾', log: '🧾',
    html: '🌐', htm: '🌐', pdf: '📕', docx: '📘', xlsx: '📊',
  }[ext] || '📄'
}

async function load() {
  loading.value = true
  try {
    files.value = (await api.get('/api/kb/files')).items || []
  } catch (err) {
    toastError(err)
  } finally {
    loading.value = false
  }
  loadVector()
}

async function loadVector() {
  try {
    vector.value = await api.get('/api/kb/vector')
  } catch {
    vector.value = { enabled: false }
  }
}

/* 重建向量索引：为缺失向量的分块补嵌入；换过模型时后端会先清空旧向量 */
async function rebuildVector() {
  if (rebuilding.value) return
  rebuilding.value = true
  try {
    const res = await api.post('/api/kb/vector/rebuild')
    showToast(`向量索引完成：新嵌入 ${res.embedded} 块（共 ${res.embeddedChunks}/${res.totalChunks} 块）`)
  } catch (err) {
    toastError(err)
  } finally {
    rebuilding.value = false
    loadVector()
  }
}

/* ---------- 详情 ---------- */

async function openDetail(f) {
  detail.value = f
  detailText.value = ''
  detailLoading.value = true
  try {
    const res = await fetch(`/api/kb/files/${f.id}`)
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || '读取失败')
    detailText.value = (data.chunks || []).map((c) => c.content).join('\n\n')
    detail.value = { ...detail.value, chunkCount: data.chunkCount, charCount: data.charCount }
  } catch (err) {
    detailText.value = '读取失败：' + (err.message || '')
  } finally {
    detailLoading.value = false
  }
}

function openByName(filename) {
  const f = files.value.find((x) => x.filename === filename)
  if (f) openDetail(f)
}

function reloadDetail() {
  if (detail.value) openDetail(detail.value)
}

function back() {
  detail.value = null
  detailText.value = ''
}

/* 关窗并把「根据知识库…」指令预填进聊天输入框（不直接执行） */
function askAbout(f) {
  const name = f.filename.replace(/\.(md|txt|csv|json|log|html?|pdf|docx|xlsx)$/i, '')
  emit('run', `根据知识库，讲讲《${name}》这份文档的核心内容和要点`)
}

/* ---------- 上传 / 试搜 / 删除 ---------- */

async function onFilesPicked(e) {
  const picked = [...(e.target.files || [])]
  e.target.value = ''
  if (!picked.length) return
  uploading.value = true
  message.value = null
  for (const f of picked) {
    try {
      const fd = new FormData()
      fd.append('file', f)
      const res = await fetch('/api/kb/files', { method: 'POST', body: fd })
      const data = await res.json()
      if (!res.ok) throw new Error(data.error || '上传失败')
      message.value = { text: `已入库 ${data.filename} · ${data.charCount} 字 / ${data.chunkCount} 块`, cls: 'ok' }
    } catch (err) {
      message.value = { text: `${f.name}：${err.message || '上传失败'}`, cls: 'err' }
    }
  }
  uploading.value = false
  await load()
}

async function doSearch() {
  if (!searchQ.value.trim() || searching.value) return
  searching.value = true
  hits.value = null
  try {
    const res = await fetch(`/api/kb/search?q=${encodeURIComponent(searchQ.value.trim())}`)
    hits.value = await res.json()
  } catch {
    message.value = { text: '搜索请求失败', cls: 'err' }
  } finally {
    searching.value = false
  }
}

async function remove(f) {
  if (!window.confirm(`确定删除 ${f.filename}？相关分块与索引一并删除。`)) return
  try {
    const res = await fetch(`/api/kb/files/${f.id}`, { method: 'DELETE' })
    if (!res.ok) throw new Error('删除失败')
    showToast(`已删除 ${f.filename}`)
  } catch (err) {
    message.value = { text: err.message || '删除失败', cls: 'err' }
  }
  detail.value = null
  await load()
}

onMounted(load)
</script>
