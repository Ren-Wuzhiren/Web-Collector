export const THEME_STORAGE_KEY = 'webcollector-theme'

const THEMES = new Set(['dark', 'light'])
const THEME_EVENT = 'webcollector:themechange'

function normalizeTheme(value) {
  return THEMES.has(value) ? value : 'dark'
}

export function getStoredTheme() {
  try {
    return normalizeTheme(localStorage.getItem(THEME_STORAGE_KEY))
  } catch {
    return 'dark'
  }
}

export function getCurrentTheme() {
  return normalizeTheme(document.documentElement.dataset.theme)
}

export function applyTheme(theme) {
  const nextTheme = normalizeTheme(theme)
  const root = document.documentElement

  root.dataset.theme = nextTheme
  root.classList.toggle('dark', nextTheme === 'dark')
  root.style.colorScheme = nextTheme

  return nextTheme
}

export function setTheme(theme) {
  const nextTheme = applyTheme(theme)

  try {
    localStorage.setItem(THEME_STORAGE_KEY, nextTheme)
  } catch {
    // 本地存储不可用时仍应用主题，只是无法跨刷新保留。
  }

  window.dispatchEvent(
    new CustomEvent(THEME_EVENT, { detail: { theme: nextTheme } }),
  )
  return nextTheme
}

export function toggleTheme() {
  return setTheme(getCurrentTheme() === 'dark' ? 'light' : 'dark')
}

export function onThemeChange(listener) {
  const handler = (event) => listener(event.detail.theme)
  window.addEventListener(THEME_EVENT, handler)
  return () => window.removeEventListener(THEME_EVENT, handler)
}
