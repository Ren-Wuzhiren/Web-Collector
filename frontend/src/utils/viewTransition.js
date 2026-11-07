// [设计] View Transitions 只包住普通左键点击的站内跳转；修饰键、中键等交给 router-link 默认行为。
// 减少动态效果或浏览器不支持时直接跳转，不生成过渡。
export function shouldUseViewTransition(event) {
  if (typeof document === 'undefined' || typeof document.startViewTransition !== 'function') {
    return false
  }
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return false
  return !(event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey)
}

export function navigateWithTransition(router, to) {
  // 回调返回 router.push 的 Promise，过渡会等待路由切换完成后再截取新页面。
  return document.startViewTransition(() => router.push(to))
}
