import { ref } from 'vue'

/**
 * 全局轻提示：任意组件调用 showToast(文本, 'ok'|'err')，
 * 由 App 里的 ToastHost 统一渲染在页面顶部，不绑定到某个面板。
 */
const toasts = ref([])
let seq = 0

export function showToast(text, type = 'ok') {
  const id = ++seq
  toasts.value.push({ id, text: String(text || ''), type })
  // 报错多留一会儿，正常提示短一些
  setTimeout(() => dismissToast(id), type === 'err' ? 6000 : 3000)
  return id
}

export function dismissToast(id) {
  toasts.value = toasts.value.filter((t) => t.id !== id)
}

export function useToasts() {
  return toasts
}
