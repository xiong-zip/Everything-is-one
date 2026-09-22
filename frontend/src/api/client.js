/**
 * 后端调用统一入口。
 *
 * 存在的理由是「失败必须看得见」：原先 79 处裸 fetch 里有 61 处把异常吞掉（40 处带静默注释），
 * 请求失败与「本来就没有数据」在界面上完全一样——面板显示「还没有配置连接」，
 * 而真实原因可能是后端没起来。这里统一把失败转成异常抛出，由调用方决定提示方式。
 *
 * 只负责传输与错误规范化：不做重试、不弹提示、不缓存，避免把业务策略藏进基础设施。
 */
import { showToast } from '../composables/useToast'

/** 请求失败：status=0 表示压根没连上（后端不可达），其余为 HTTP 状态码 */
export class ApiError extends Error {
  constructor(message, status) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

async function errorMessage(res) {
  try {
    const data = await res.clone().json()
    const detail = data && (data.message || data.error)
    if (detail) return String(detail)
  } catch {
    // 响应体不是 JSON（网关错误页、空响应等），退回到状态码描述
  }
  return `请求失败（HTTP ${res.status}）`
}

async function request(path, { method = 'GET', body, signal } = {}) {
  const init = { method, signal }
  if (body !== undefined) {
    init.headers = { 'Content-Type': 'application/json' }
    init.body = JSON.stringify(body)
  }
  let res
  try {
    res = await fetch(path, init)
  } catch (err) {
    if (err && err.name === 'AbortError') throw err
    throw new ApiError('后端不可达', 0)
  }
  if (!res.ok) {
    throw new ApiError(await errorMessage(res), res.status)
  }
  if (res.status === 204) return null
  const type = res.headers.get('content-type') || ''
  return type.includes('application/json') ? res.json() : res.text()
}

export const api = {
  get: (path, opts) => request(path, opts),
  post: (path, body, opts) => request(path, { ...opts, method: 'POST', body }),
  put: (path, body, opts) => request(path, { ...opts, method: 'PUT', body }),
  del: (path, opts) => request(path, { ...opts, method: 'DELETE' }),
}

/** 统一的失败提示：把「后端不可达」与「业务报错」区分开，后者直接用后端给出的原因 */
export function toastError(err) {
  if (err && err.name === 'AbortError') return
  const detail = err && err.message ? err.message : '操作失败'
  showToast(err && err.status === 0 ? `${detail}：后端服务未启动或已断开` : detail, 'err')
}
