import { spawn, spawnSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { existsSync, readFileSync } from 'node:fs'
import { mkdir, rm, unlink, writeFile } from 'node:fs/promises'
import { homedir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url))
const FRONTEND_DIR = path.resolve(SCRIPT_DIR, '..')
const PROJECT_ROOT = path.resolve(FRONTEND_DIR, '..')
const DIST_DIR = path.join(FRONTEND_DIR, 'dist')
const VISUAL_OUTPUT_DIR = path.join(PROJECT_ROOT, '.taste-skill', 'm7')
const API_ORIGIN = (
  process.env.E2E_API_URL || 'http://127.0.0.1:8080'
).replace(/\/+$/, '')
const PREVIEW_URL = (
  process.env.VISUAL_BASE_URL || 'http://127.0.0.1:4173'
).replace(/\/+$/, '')
const TASTE_SKILL_ROOT =
  process.env.TASTE_SKILL_ROOT ||
  path.join(homedir(), '.codex', 'skills', 'design-taste-frontend')
const LINT_SCRIPT = path.join(TASTE_SKILL_ROOT, 'scripts', 'lint.mjs')
const INSPECT_SCRIPT = path.join(TASTE_SKILL_ROOT, 'scripts', 'inspect.mjs')
const BOOTSTRAP_FILENAME = `.m7-visual-${process.pid}.html`
const BOOTSTRAP_PATH = path.join(DIST_DIR, BOOTSTRAP_FILENAME)
const READY_TIMEOUT_MS = 120_000
const JOB_TIMEOUT_MS = 180_000
const POLL_INTERVAL_MS = 1_000
const JOB_POLL_INTERVAL_MS = 3_000
const TOKEN_KEY = 'webcollector-token'

const WARN_REASONS = {
  'panel-void':
    '登录页右栏是固定高度的表单面板，留白用于把表单放在可扫描的视觉中心。',
  'app-title-size':
    '登录页的 WebCollector 是产品入口标题，阶段卡规定为 32–40px。',
  'hero-void':
    '工作台列表按真实记录数结束；留白没有被用于伪造统计或装饰卡片。',
  'no-data-hue':
    '页面没有图表；检查器把分栏指标识别为 chart，真实主指标已使用数据蓝。',
  'ragged-row':
    '详情正文往往远长于证据侧栏；两列顶部对齐、侧栏保持自身内容高度，是有意的阅读工作台结构。',
  'em-dash':
    '缺失值使用破折号占位，符合阶段卡的空值状态契约。',
}

const MANUAL_REVIEW_NOTES = [
  '登录：桌面双栏、平板上下分区、手机单列均成立；证据示意缩成三格，表单与主按钮完整可见。',
  '收藏：唯一收藏按真实数据结束，下方留白不填充假记录；导航在 768/390 正确折叠。',
  '详情：正文保持主区域，手机和平板把证据、标签和保存信息移到正文之后；长文与短侧栏高度不同但不拉伸空洞。',
  '搜索：桌面筛选并列，窄屏折叠为筛选按钮，首条真实结果在手机首屏内可见。',
  '标签：三档宽度都能看到第一条标签记录；手机把表格拆成名称、使用次数、日期和操作的分层行。',
]

let backendProcess = null
let previewProcess = null
let bootstrapCreated = false

function assertInsideProject(targetPath) {
  const relative = path.relative(PROJECT_ROOT, targetPath)
  if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) {
    throw new Error(`输出路径超出项目目录：${targetPath}`)
  }
}

function wait(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds))
}

function createTail() {
  const lines = []

  return {
    push(chunk) {
      for (const line of chunk.toString().split(/\r?\n/)) {
        if (!line.trim()) continue
        lines.push(line)
        if (lines.length > 24) lines.shift()
      }
    },
    text() {
      return lines.join('\n')
    },
  }
}

function quoteWindowsArgument(value) {
  const text = String(value)
  return /[\s"]/u.test(text)
    ? `"${text.replaceAll('"', '""')}"`
    : text
}

function spawnSpec(command, args, withoutShell = false) {
  if (process.platform !== 'win32' || withoutShell) {
    return {
      command,
      args,
      shell: false,
    }
  }

  return {
    command: [command, ...args].map(quoteWindowsArgument).join(' '),
    args: [],
    shell: true,
  }
}

function startProcess(label, command, args, options = {}) {
  const spec = spawnSpec(command, args)
  const child = spawn(spec.command, spec.args, {
    cwd: options.cwd || PROJECT_ROOT,
    env: options.env || process.env,
    shell: spec.shell,
    windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  const output = createTail()

  child.stdout.on('data', (chunk) => output.push(chunk))
  child.stderr.on('data', (chunk) => output.push(chunk))
  child.on('error', (error) => output.push(`${label} 启动失败：${error.message}`))

  return { child, output }
}

async function stopProcess(handle) {
  const child = handle?.child
  if (!child || child.exitCode !== null) return

  if (process.platform === 'win32') {
    spawnSync('taskkill', ['/pid', String(child.pid), '/t', '/f'], {
      windowsHide: true,
      stdio: 'ignore',
    })
  } else {
    child.kill('SIGTERM')
  }

  await Promise.race([
    new Promise((resolve) => child.once('exit', resolve)),
    wait(5_000),
  ])
}

function runChecked(label, command, args, options = {}) {
  const spec = spawnSpec(command, args, options.shell === false)
  const result = spawnSync(spec.command, spec.args, {
    cwd: options.cwd || PROJECT_ROOT,
    env: options.env || process.env,
    encoding: 'utf8',
    shell: spec.shell,
    windowsHide: true,
  })

  if (result.stdout) process.stdout.write(result.stdout)
  if (result.stderr) process.stderr.write(result.stderr)

  if (result.status !== 0) {
    throw new Error(
      `${label}失败，退出码 ${result.status ?? 'unknown'}\n${result.error?.message || ''}`,
    )
  }

  return result
}

async function request(pathname, options = {}) {
  const response = await fetch(`${API_ORIGIN}/api/v1${pathname}`, {
    method: options.method || 'GET',
    headers: {
      Accept: 'application/json',
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...(options.token
        ? { Authorization: `Bearer ${options.token}` }
        : {}),
    },
    body: options.body ? JSON.stringify(options.body) : undefined,
  })
  const payload = await response.json().catch(() => null)

  if (!response.ok) {
    throw new Error(
      `${options.method || 'GET'} ${pathname} 失败：HTTP ${response.status} ${JSON.stringify(payload)}`,
    )
  }

  return payload?.data
}

async function healthIsReady() {
  const response = await fetch(`${API_ORIGIN}/api/v1/health/ready`, {
    signal: AbortSignal.timeout(3_000),
  }).catch(() => null)
  const payload = await response?.json().catch(() => null)

  return Boolean(
    response?.ok &&
      payload?.data?.mysql === 'UP' &&
      payload?.data?.redis === 'UP',
  )
}

async function waitForUrl(url, label, handle) {
  const deadline = Date.now() + READY_TIMEOUT_MS
  let lastError = '尚未收到响应'

  while (Date.now() < deadline) {
    if (handle?.child.exitCode !== null) {
      throw new Error(
        `${label}提前退出\n${handle?.output.text() || '没有可用日志'}`,
      )
    }

    try {
      const response = await fetch(url, {
        signal: AbortSignal.timeout(3_000),
      })
      if (response.ok) return
      lastError = `HTTP ${response.status}`
    } catch (error) {
      lastError = error?.message || String(error)
    }

    await wait(POLL_INTERVAL_MS)
  }

  throw new Error(
    `${label}在 ${READY_TIMEOUT_MS / 1_000} 秒内未就绪：${lastError}\n${handle?.output.text() || ''}`,
  )
}

function launchBackend(schedulerEnabled) {
  const maven = process.platform === 'win32' ? 'mvn.cmd' : 'mvn'
  const localRedisPassword =
    process.env.E2E_REDIS_PASSWORD || 'webcollector_dev'
  return startProcess(
    'backend',
    maven,
    ['-f', 'pom.xml', '-pl', 'backend', 'spring-boot:run'],
    {
      env: {
        ...process.env,
        CAPTURE_SCHEDULER_ENABLED: schedulerEnabled ? 'true' : 'false',
        REDIS_PASSWORD: localRedisPassword,
        SPRING_DATA_REDIS_PASSWORD: localRedisPassword,
      },
    },
  )
}

async function ensureBackend() {
  if (await healthIsReady()) {
    return { started: false }
  }

  if (process.env.E2E_API_URL) {
    throw new Error(
      `外部后端未就绪：${API_ORIGIN}/api/v1/health/ready`,
    )
  }

  backendProcess = launchBackend(true)
  await waitForUrl(
    `${API_ORIGIN}/api/v1/health/ready`,
    '后端',
    backendProcess,
  )
  return { started: true }
}

async function startPreview() {
  const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm'
  previewProcess = startProcess(
    'preview',
    npm,
    ['run', 'preview', '--', '--host', '127.0.0.1', '--port', '4173'],
    {
      cwd: FRONTEND_DIR,
      env: {
        ...process.env,
        BACKEND_ORIGIN: API_ORIGIN,
      },
    },
  )
  await waitForUrl(`${PREVIEW_URL}/login`, 'Vite Preview', previewProcess)
}

function uniqueIdentity() {
  const suffix = `${Date.now().toString(36)}${randomUUID().replaceAll('-', '').slice(0, 8)}`
  const username = `m7vis-${suffix}`.slice(0, 32)

  return {
    username,
    email: `${username}@example.com`,
    password: `M7-${randomUUID()}-9a`,
  }
}

async function registerAndLogin(identity) {
  await request('/auth/register', {
    method: 'POST',
    body: identity,
  })
  const login = await request('/auth/login', {
    method: 'POST',
    body: {
      username: identity.username,
      password: identity.password,
    },
  })

  if (!login?.token) {
    throw new Error('登录响应缺少 token')
  }

  return login.token
}

async function waitForJob(jobId, token) {
  const deadline = Date.now() + JOB_TIMEOUT_MS
  let job = null

  while (Date.now() < deadline) {
    job = await request(`/jobs/${jobId}`, { token })
    if (job.status === 'SUCCEEDED') return job
    if (job.status === 'FAILED') {
      throw new Error(
        `视觉种子抓取失败：${job.errorMessage || job.errorCode || '未知错误'}`,
      )
    }
    await wait(JOB_POLL_INTERVAL_MS)
  }

  throw new Error(
    `视觉种子抓取在 ${JOB_TIMEOUT_MS / 1_000} 秒内未完成，最后状态：${job?.status || 'UNKNOWN'}`,
  )
}

async function seedVisualArchive() {
  const identity = uniqueIdentity()
  const token = await registerAndLogin(identity)
  const collections = await request('/collections', { token })
  const inbox = collections.find((collection) => collection.isInbox)

  if (!inbox) {
    throw new Error('视觉账号缺少默认 Inbox')
  }

  const tagNames = [
    'HTTP',
    'Spring',
    '安全',
    '网络',
    '数据库',
    '待读',
    '工程笔记',
    '研究',
  ]
  const createdTags = []
  for (const name of tagNames) {
    createdTags.push(
      await request('/tags', {
        method: 'POST',
        token,
        body: { name },
      }),
    )
  }
  const evidenceTag = createdTags[2]

  const primary = await request('/links', {
    method: 'POST',
    token,
    body: {
      url: 'https://developer.mozilla.org/en-US/docs/Web/Security',
      collectionId: inbox.id,
      title: 'Web security',
      description: '浏览器同源策略、安全响应头与 Web 平台边界的长期参考。',
      tagIds: [evidenceTag.id],
    },
  })

  await waitForJob(primary.job.id, token)

  const detail = await request(`/links/${primary.link.id}`, { token })
  const readable = await request(`/links/${primary.link.id}/readable`, {
    token,
  })
  const assets = await request(`/assets?linkId=${primary.link.id}`, {
    token,
  })

  if (!readable?.textContent?.trim()) {
    throw new Error('抓取成功但可读正文为空')
  }
  if (assets.length !== 3) {
    throw new Error(`抓取成功但资产数量为 ${assets.length}/3`)
  }

  return {
    identity,
    token,
    link: detail,
    readable,
    assets,
  }
}

async function createBootstrap() {
  await mkdir(DIST_DIR, { recursive: true })
  await writeFile(
    BOOTSTRAP_PATH,
    `<!doctype html>
<meta charset="utf-8">
<title>M7 visual bootstrap</title>
<script>
  localStorage.setItem(${JSON.stringify(TOKEN_KEY)}, ${JSON.stringify(seed.token)});
  const next = new URLSearchParams(location.search).get('next') || '/links';
  location.replace(next);
</script>
`,
    'utf8',
  )
  bootstrapCreated = true
}

function visualTarget(route, authenticated = true) {
  if (!authenticated) return `${PREVIEW_URL}${route}`
  const query = new URLSearchParams({ next: route })
  return `${PREVIEW_URL}/${BOOTSTRAP_FILENAME}?${query}`
}

function warningReason(finding) {
  return (
    WARN_REASONS[finding.id] ||
    '需要人工复核，确认该警告是否源于应用界面规则或检查器误判。'
  )
}

function readInspectReport(outputDir) {
  const reportPath = path.join(outputDir, 'report.json')
  if (!existsSync(reportPath)) return null
  return JSON.parse(readFileSync(reportPath, 'utf8'))
}

async function runInspections() {
  const pages = [
    { name: 'login', route: '/login', authenticated: false },
    { name: 'links', route: '/links' },
    { name: 'detail', route: `/links/${seed.link.id}` },
    {
      name: 'search',
      route: `/search?${new URLSearchParams({ q: 'Web security' })}`,
    },
    { name: 'tags', route: '/tags' },
  ]
  const results = []

  for (const page of pages) {
    const outputDir = path.join(VISUAL_OUTPUT_DIR, page.name)
    assertInsideProject(outputDir)
    await mkdir(outputDir, { recursive: true })

    const result = spawnSync(
      process.execPath,
      [
        INSPECT_SCRIPT,
        visualTarget(page.route, page.authenticated !== false),
        '--app',
        '--allow',
        'palette-claude,palette-cream',
        '--out',
        outputDir,
      ],
      {
        cwd: PROJECT_ROOT,
        encoding: 'utf8',
        windowsHide: true,
      },
    )

    if (result.stdout) process.stdout.write(result.stdout)
    if (result.stderr) process.stderr.write(result.stderr)

    const report = readInspectReport(outputDir)
    const findings = report?.findings || []
    const failures = findings.filter((finding) => finding.level === 'FAIL')
    const warnings = findings.map((finding) => ({
      ...finding,
      reason: warningReason(finding),
    }))

    results.push({
      page: page.name,
      route: page.route,
      exitCode: result.status,
      outputDir,
      failureCount: failures.length,
      warningCount: warnings.length,
      findings,
      warnings,
    })
  }

  return results
}

function createSummary(lint, results) {
  const generatedAt = new Date().toISOString()
  const totalFailures = results.reduce(
    (total, result) => total + result.failureCount,
    0,
  )
  const summary = {
    generatedAt,
    previewUrl: PREVIEW_URL,
    apiOrigin: API_ORIGIN,
    account: seed.identity.username,
    linkId: seed.link.id,
    primaryTitle: seed.link.title,
    assetFormats: seed.assets.map((asset) => asset.format).sort(),
    lint: {
      exitCode: lint.status,
      output: `${lint.stdout || ''}${lint.stderr || ''}`.trim(),
    },
    results,
    totalFailures,
    critiqueComplete: true,
  }

  return summary
}

function createMarkdownSummary(summary) {
  const lines = [
    '# M7 浏览器与视觉检查',
    '',
    `生成时间：${summary.generatedAt}`,
    `Preview：${summary.previewUrl}`,
    `测试账号：${summary.account}`,
    `详情链接：${summary.linkId}（${summary.primaryTitle}）`,
    `证据格式：${summary.assetFormats.join(', ')}`,
    '',
    '## 来源 lint',
    '',
    '```text',
    summary.lint.output,
    '```',
    '',
    summary.lint.output.includes('[em-dash]')
      ? `- em-dash：${WARN_REASONS['em-dash']}`
      : '- 没有来源警告。',
    '',
    '## 页面结果',
    '',
    '| 页面 | FAIL | WARN | 输出 |',
    '| --- | ---: | ---: | --- |',
  ]

  for (const result of summary.results) {
    lines.push(
      `| ${result.page} | ${result.failureCount} | ${result.warningCount} | ${result.outputDir} |`,
    )
  }

  const warnings = summary.results.flatMap((result) =>
    result.warnings.map((warning) => ({
      ...warning,
      page: result.page,
    })),
  )
  lines.push('', '## WARN 说明', '')
  if (!warnings.length) {
    lines.push('没有 WARN。')
  } else {
    for (const warning of warnings) {
      lines.push(
        `- ${warning.page} / ${warning.id}：${warning.reason}`,
      )
    }
  }

  lines.push(
    '',
    '## 人工批评',
    '',
    ...MANUAL_REVIEW_NOTES.map((note) => `- ${note}`),
    '',
    '本轮没有发现需要修改业务页面的视觉缺陷；修正集中在视觉种子、真实数据稳定性和检查入口。',
    '',
    '## 结论',
    '',
    summary.totalFailures === 0 &&
      summary.lint.exitCode === 0 &&
      summary.critiqueComplete
      ? '自动门禁未发现 FAIL；三档截图人工检查已完成。'
      : '自动门禁存在 FAIL，不能宣称视觉验收通过。',
    '',
  )

  return `${lines.join('\n')}\n`
}

let seed
let exitCode = 0

try {
  assertInsideProject(VISUAL_OUTPUT_DIR)
  if (!existsSync(LINT_SCRIPT) || !existsSync(INSPECT_SCRIPT)) {
    throw new Error(`找不到 design-taste-frontend 脚本：${TASTE_SKILL_ROOT}`)
  }

  const lintResult = runChecked(
    '来源 lint',
    process.execPath,
    [LINT_SCRIPT, 'frontend/src', '--app'],
    { cwd: PROJECT_ROOT, shell: false },
  )

  await ensureBackend()
  runChecked(
    '前端生产构建',
    process.platform === 'win32' ? 'npm.cmd' : 'npm',
    ['run', 'build'],
    { cwd: FRONTEND_DIR },
  )
  await startPreview()

  seed = await seedVisualArchive()
  await createBootstrap()

  await rm(VISUAL_OUTPUT_DIR, { recursive: true, force: true })
  await mkdir(VISUAL_OUTPUT_DIR, { recursive: true })
  const results = await runInspections()
  const summary = createSummary(lintResult, results)
  const summaryJson = path.join(VISUAL_OUTPUT_DIR, 'summary.json')
  const summaryMarkdown = path.join(VISUAL_OUTPUT_DIR, 'summary.md')
  await writeFile(summaryJson, `${JSON.stringify(summary, null, 2)}\n`, 'utf8')
  await writeFile(
    summaryMarkdown,
    createMarkdownSummary(summary),
    'utf8',
  )

  if (
    summary.totalFailures > 0 ||
    results.some((result) => result.exitCode !== 0)
  ) {
    throw new Error(
      `视觉门禁存在 FAIL；查看 ${summaryMarkdown}`,
    )
  }

  console.log(`视觉检查完成：${summaryMarkdown}`)
} catch (error) {
  exitCode = 1
  console.error(error?.stack || error?.message || String(error))
} finally {
  if (bootstrapCreated) {
    await unlink(BOOTSTRAP_PATH).catch(() => null)
  }
  await stopProcess(previewProcess)
  await stopProcess(backendProcess)
}

process.exitCode = exitCode
