/* 极简 Markdown 渲染器（无第三方依赖，内网友好）
   支持：标题 / 表格 / 无序有序列表 / 引用 / 围栏代码块 / 粗体 / 斜体 / 删除线 /
   行内代码 / 行内链接 / 分隔线。
   所有原文先做 HTML 转义再转换，可安全 v-html。 */

function esc(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

function inline(s) {
  return esc(s)
    .replace(/`([^`]+)`/g, '<code class="md-code">$1</code>')
    // 非贪婪并允许内容含单个 *：**P95*延迟** 这类也能整体加粗，而不是把星号原样吐出来
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/(^|[^*\w])\*([^*\s][^*]*?)\*/g, '$1<em>$2</em>')
    .replace(/~~([^~]+)~~/g, '<del>$1</del>')
    .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a class="md-a" href="$2" target="_blank" rel="noopener">$1</a>')
}

/* 多行块（段落 / 引用）的解析辅助：先把各行拼成一整段再走 inline，
   让 **加粗**、*斜体* 这类行内标记可以跨行配对；换行先换成 \x00 占位
   （esc 与各正则都不会碰它），解析完再还原成 <br/>。 */
function inlineJoined(lines) {
  return inline(lines.join('\x00')).replace(/\x00/g, '<br/>')
}

function splitRow(row) {
  return row.replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim())
}

/* 4 空格 / tab 起头 = 缩进式代码块（CommonMark 约定）；dedent 只剥一层缩进 */
const isIndented = (raw) => /^( {4,}|\t)/.test(raw)
const dedent = (l) => l.replace(/^\t/, '').replace(/^ {1,4}/, '')

const isBlockStart = (raw) => {
  const t = raw.trim()
  return !t ||
    isIndented(raw) ||
    t.startsWith('|') ||
    t.startsWith('```') ||
    t.startsWith('~~~') ||
    /^#{1,6}\s/.test(t) ||
    /^[-*]\s+/.test(t) ||
    /^\d+[.、)]\s+/.test(t) ||
    /^(-{3,}|\*{3,})$/.test(t) ||
    t.startsWith('>')
}

/**
 * @param {string} src markdown 原文
 * @param {{ paraClass?: (text: string) => string }} opts paraClass 可给普通段落附加样式类
 */
export function mdToHtml(src, opts = {}) {
  if (!src) return ''
  const paraClass = opts.paraClass || (() => '')
  const lines = String(src).replace(/\r\n/g, '\n').split('\n')
  const out = []
  let i = 0
  let lastBlank = true // 文档开头视同空行：允许第一行就是缩进代码块

  while (i < lines.length) {
    const raw = lines[i]
    const t = raw.trim()

    if (!t) {
      i++
      lastBlank = true
      continue
    }
    const prevBlank = lastBlank
    lastBlank = false

    // 围栏代码块：``` 或 ~~~ 开围栏，围栏后整行剩余都当语言标注
    // （LLM 偶尔写「```sql 查询示例」，按旧的单词正则会让整个代码块失效）
    const fence = t.match(/^(```|~~~)\s*(.*)$/)
    if (fence) {
      const marker = fence[1]
      const lang = fence[2].trim()
      i++
      const buf = []
      while (i < lines.length && !lines[i].trim().startsWith(marker)) {
        buf.push(lines[i])
        i++
      }
      i++ // 跳过闭合围栏（没有闭合也不死循环，走到末尾自然结束）
      out.push(
        `<pre class="md-pre"${lang ? ` data-lang="${esc(lang)}"` : ''}><code>${esc(buf.join('\n'))}</code></pre>`,
      )
      continue
    }

    // 缩进式代码块：空行之后连续的 4 空格 / tab 缩进行（SQL 的 -- 注释、shell 的 # 注释
    // 常以这种形式出现，不识别就会把 # 当标题、-- 当正文散开）。
    // 只在空行后启动（与 CommonMark 一致：缩进代码块不能中断段落、也不吞列表延续行）；
    // 内容原样保留，仅剥掉一层缩进。
    if (prevBlank && isIndented(raw)) {
      const buf = []
      while (i < lines.length) {
        const l = lines[i]
        if (isIndented(l)) {
          buf.push(dedent(l).replace(/\s+$/, ''))
          i++
          continue
        }
        // 块内空行：后面还是缩进行才属于本代码块
        if (!l.trim() && i + 1 < lines.length && isIndented(lines[i + 1])) {
          buf.push('')
          i++
          continue
        }
        break
      }
      out.push(`<pre class="md-pre"><code>${esc(buf.join('\n'))}</code></pre>`)
      continue
    }

    // 表格：| 表头 | … 下一行是 |---|---| 分隔
    if (t.startsWith('|') && i + 1 < lines.length && /^\|[\s:|-]+\|?$/.test(lines[i + 1].trim())) {
      const head = splitRow(t)
      i += 2
      const rows = []
      while (i < lines.length && lines[i].trim().startsWith('|')) {
        rows.push(splitRow(lines[i].trim()))
        i++
      }
      out.push(
        '<table class="md-table"><thead><tr>' +
          head.map((c) => `<th>${inline(c)}</th>`).join('') +
          '</tr></thead><tbody>' +
          rows.map((r) => '<tr>' + r.map((c) => `<td>${inline(c)}</td>`).join('') + '</tr>').join('') +
          '</tbody></table>',
      )
      continue
    }

    // 标题（卡片语境下整体降两级：# → h3）
    const h = t.match(/^(#{1,6})\s+(.*)$/)
    if (h) {
      const lv = Math.min(h[1].length + 2, 6)
      out.push(`<h${lv} class="md-h md-h${lv}">${inline(h[2])}</h${lv}>`)
      i++
      continue
    }

    // 分隔线
    if (/^(-{3,}|\*{3,})$/.test(t)) {
      out.push('<hr class="md-hr" />')
      i++
      continue
    }

    // 引用块
    if (t.startsWith('>')) {
      const buf = []
      while (i < lines.length && lines[i].trim().startsWith('>')) {
        buf.push(lines[i].trim().replace(/^>\s?/, ''))
        i++
      }
      out.push(`<blockquote class="md-quote">${inlineJoined(buf)}</blockquote>`)
      continue
    }

    // 无序列表
    if (/^[-*]\s+/.test(t)) {
      const items = []
      while (i < lines.length && /^[-*]\s+/.test(lines[i].trim())) {
        items.push(`<li>${inline(lines[i].trim().replace(/^[-*]\s+/, ''))}</li>`)
        i++
      }
      out.push(`<ul class="md-list">${items.join('')}</ul>`)
      continue
    }

    // 有序列表
    if (/^\d+[.、)]\s+/.test(t)) {
      const items = []
      while (i < lines.length && /^\d+[.、)]\s+/.test(lines[i].trim())) {
        items.push(`<li>${inline(lines[i].trim().replace(/^\d+[.、)]\s+/, ''))}</li>`)
        i++
      }
      out.push(`<ol class="md-list md-ol">${items.join('')}</ol>`)
      continue
    }

    // 普通段落：连续普通行合并（保留换行），可由 paraClass 附加样式类
    const cls = paraClass(t)
    const buf = [t]
    i++
    while (i < lines.length && !isBlockStart(lines[i])) {
      buf.push(lines[i].trim())
      i++
    }
    out.push(`<p class="md-p${cls ? ' ' + cls : ''}">${inlineJoined(buf)}</p>`)
  }

  return out.join('')
}
