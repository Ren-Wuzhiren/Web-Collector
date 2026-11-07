const relativeFormatter = new Intl.RelativeTimeFormat('zh-CN', {
  numeric: 'auto',
})

export function formatDomain(url) {
  try {
    return new URL(url).hostname.replace(/^www\./, '')
  } catch {
    return url || '来源未知'
  }
}

export function formatRelativeTime(value) {
  if (!value) return '时间未知'

  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '时间未知'

  const elapsedSeconds = Math.round((date.getTime() - Date.now()) / 1000)
  const absoluteSeconds = Math.abs(elapsedSeconds)

  if (absoluteSeconds < 60) return '刚刚'
  if (absoluteSeconds < 3600) {
    return relativeFormatter.format(Math.round(elapsedSeconds / 60), 'minute')
  }
  if (absoluteSeconds < 86400) {
    return relativeFormatter.format(Math.round(elapsedSeconds / 3600), 'hour')
  }
  if (absoluteSeconds < 7 * 86400) {
    return relativeFormatter.format(Math.round(elapsedSeconds / 86400), 'day')
  }

  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(date)
}

export function formatClock(value) {
  if (!value) return ''

  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value))
}
