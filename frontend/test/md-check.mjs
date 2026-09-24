import { mdToHtml } from '../src/utils/markdown.js'

const cases = [
  // 本轮修复
  ['缩进代码块(4空格)', '配置如下：\n\n    # 主配置注释\n    --port=8080\n    debug=false\n\n其余说明'],
  ['缩进代码块(tab)', '代码：\n\n\t# 注释\n\t--flag'],
  ['缩进代码块含空行', '示例：\n\n    # part1\n\n    # part2\n\n结束'],
  ['围栏标注带多个词', '示例：\n```sql 查询示例\nSELECT 1 -- 注释\n```'],
  // 上一轮修复不回退
  ['跨行粗体', '总结：\n**第一行结论\n第二行结论**\n完毕'],
  ['含单星粗体', '**P95*延迟** 是关键指标'],
  ['跨行斜体', '重点是 *这里开始\n到这里结束* 的区间'],
  ['不成对星号原样', '2*3*4 与 ** 只有开头'],
  // 基础能力
  ['围栏SQL注释', '```sql\n-- 查询所有表\nSELECT * FROM t;\n```'],
  ['围栏bash注释', '```bash\n# 安装依赖\nnpm install\n```'],
  ['无闭合围栏', '开头\n```js\nlet a = 1'],
  ['标题', '## 二、分析'],
  ['分隔线', '上\n\n---\n\n下'],
  ['两减号非分隔线', '--flag 原样'],
  ['表格粗体链接', '| 名称 | 说明 |\n| --- | --- |\n| **cpu** | 见 [文档](https://a.com/b) |'],
  ['列表', '- **重点**：说明\n- 次要'],
  ['列表延续不误判为代码', '- 第一项\n  续行内容还是正文\n- 第二项'],
  ['引用', '> 引用一行'],
  ['段落内换行', '第一行\n第二行'],
]

let fail = 0
for (const [name, src] of cases) {
  const html = mdToHtml(src)
  console.log('== ' + name + ' ==\n' + html + '\n')
}
// 断言关键行为
const asserts = [
  ['缩进代码块成pre', mdToHtml('a\n\n    --x\n    # y').includes('md-pre') && mdToHtml('a\n\n    --x\n    # y').includes('--x')],
  ['缩进块#不当标题', !mdToHtml('a\n\n    # y').includes('md-h')],
  ['多词标注成pre', mdToHtml('```sql 查询示例\nSELECT 1\n```').includes('md-pre')],
  ['SQL注释原样', mdToHtml('```sql\n-- 注释\n```').includes('-- 注释')],
  ['列表续行非pre', !mdToHtml('- a\n  续行\n- b').includes('md-pre')],
  ['无空行缩进非代码', !mdToHtml('段落文字\n    继续').includes('md-pre')],
]
for (const [name, ok] of asserts) {
  console.log((ok ? 'PASS' : 'FAIL') + ' - ' + name)
  if (!ok) fail++
}
process.exit(fail ? 1 : 0)
