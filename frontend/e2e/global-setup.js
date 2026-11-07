const READY_TIMEOUT_MS = 30_000
const RETRY_DELAY_MS = 1_000
const REQUEST_TIMEOUT_MS = 3_000

function wait(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds))
}

function describeReadiness(payload) {
  if (!payload?.data) return '响应中没有 readiness 数据'
  return `mysql=${payload.data.mysql || 'UNKNOWN'}, redis=${payload.data.redis || 'UNKNOWN'}`
}

export default async function globalSetup() {
  const apiBaseUrl = (process.env.E2E_API_URL || 'http://127.0.0.1:8080')
    .replace(/\/+$/, '')
  const readinessUrl = `${apiBaseUrl}/api/v1/health/ready`
  const deadline = Date.now() + READY_TIMEOUT_MS
  let lastFailure = '尚未收到响应'

  while (Date.now() < deadline) {
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)

    try {
      const response = await fetch(readinessUrl, { signal: controller.signal })
      const payload = await response.json().catch(() => null)

      if (
        response.ok &&
        payload?.data?.mysql === 'UP' &&
        payload?.data?.redis === 'UP'
      ) {
        return
      }

      lastFailure = `HTTP ${response.status}; ${describeReadiness(payload)}`
    } catch (error) {
      lastFailure =
        error?.name === 'AbortError'
          ? `请求超过 ${REQUEST_TIMEOUT_MS / 1000} 秒`
          : error?.message || String(error)
    } finally {
      clearTimeout(timeout)
    }

    await wait(RETRY_DELAY_MS)
  }

  throw new Error(
    [
      `E2E 前置环境未就绪：${readinessUrl}`,
      `最后一次检查：${lastFailure}`,
      '请先确认本地 MySQL 13307、Redis 6380 可用；Playwright 会启动后端与 Vite。',
    ].join('\n'),
  )
}
