/* 极简 Markdown 渲染器（无第三方依赖，内网友好）
   支持：标题 / 表格 / 无序有序列表 / 引用 / 粗体 / 行内代码 / 分隔线。
   所有原文先做 HTML 转义再转换，可安全 v-html。 */

function esc(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

function inline(s) {
  return esc(s)
    .replace(/`([^`]+)`/g, '<code class="md-code">$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
}

function splitRow(row) {
  return row.replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim())
}

const isBlockStart = (t) =>
  !t ||
  t.startsWith('|') ||
  /^#{1,6}\s/.test(t) ||
  /^[-*]\s+/.test(t) ||
  /^\d+[.、)]\s+/.test(t) ||
  /^(-{3,}|\*{3,})$/.test(t) ||
  t.startsWith('>')

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

  while (i < lines.length) {
    const t = lines[i].trim()

    if (!t) {
      i++
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
      out.push(`<blockquote class="md-quote">${buf.map(inline).join('<br/>')}</blockquote>`)
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
    while (i < lines.length && !isBlockStart(lines[i].trim())) {
      buf.push(lines[i].trim())
      i++
    }
    out.push(`<p class="md-p${cls ? ' ' + cls : ''}">${buf.map(inline).join('<br/>')}</p>`)
  }

  return out.join('')
}
