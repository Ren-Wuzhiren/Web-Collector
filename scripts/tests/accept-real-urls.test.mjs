import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdtemp, readFile, writeFile } from 'node:fs/promises'
import { createServer } from 'node:http'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const TEST_DIR = path.dirname(fileURLToPath(import.meta.url))
const PROJECT_ROOT = path.resolve(TEST_DIR, '..', '..')
const SCRIPT_PATH = path.resolve(PROJECT_ROOT, 'scripts', 'accept-real-urls.ps1')
const POWERSHELL = process.env.POWERSHELL_EXE || 'pwsh'
const TOKEN = 'm9-secret-token'
const DATASET_PATH = path.resolve(
  PROJECT_ROOT,
  'scripts',
  'acceptance',
  'm9-real-urls.json',
)

function json(response, status, payload, headers = {}) {
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    ...headers,
  })
  response.end(JSON.stringify(payload))
}

function success(response, data, status = 200, message = 'success') {
  json(response, status, {
    data,
    message,
    requestId: 'm9-test-request',
  })
}

function failure(response, status, code, message, headers = {}) {
  json(response, status, {
    code,
    message,
    details: {},
    requestId: 'm9-test-request',
  }, headers)
}

async function readJson(request) {
  const chunks = []
  for await (const chunk of request) chunks.push(chunk)
  if (!chunks.length) return {}
  return JSON.parse(Buffer.concat(chunks).toString('utf8'))
}

function jobAssets(jobId, includeAssets) {
  if (!includeAssets) return []
  return ['HTML', 'PDF', 'SCREENSHOT'].map((format, index) => ({
    id: jobId * 10 + index,
    format,
    mimeType: format === 'HTML'
      ? 'text/html'
      : format === 'PDF'
        ? 'application/pdf'
        : 'image/png',
    fileSizeBytes: 128,
    createdAt: '2026-11-08T00:00:00Z',
  }))
}

function createState(overrides = {}) {
  return {
    user: null,
    collections: [],
    jobs: new Map(),
    nextJobId: 1,
    requests: [],
    rateLimitCreateOnce: false,
    pendingJobUrls: new Set(),
    failedJobUrls: new Set(),
    missingAssetUrls: new Set(),
    unreadableAssetUrls: new Set(),
    duplicateUrls: new Set(),
    ...overrides,
  }
}

function jobPayload(job) {
  return {
    id: job.id,
    linkId: job.linkId,
    status: job.status,
    attemptCount: job.status === 'PENDING' ? 0 : 1,
    maxAttempts: 3,
    errorCode: job.errorCode,
    errorMessage: job.errorMessage,
    nextAttemptAt: null,
    startedAt: job.status === 'PENDING' ? null : '2026-11-08T00:00:00Z',
    finishedAt: job.status === 'PENDING' ? null : '2026-11-08T00:01:00Z',
    assets: job.assets,
  }
}

async function handleRequest(state, request, response) {
  const url = new URL(request.url, 'http://127.0.0.1')
  state.requests.push({
    method: request.method,
    path: `${url.pathname}${url.search}`,
  })

  if (request.method === 'GET' && url.pathname === '/api/v1/health/ready') {
    success(response, { mysql: 'UP', redis: 'UP' })
    return
  }

  if (request.method === 'POST' && url.pathname === '/api/v1/auth/register') {
    const body = await readJson(request)
    state.user = {
      username: body.username,
      email: body.email,
      password: body.password,
    }
    state.collections.push({
      id: 1,
      name: 'Inbox',
      description: '',
      isInbox: true,
      linkCount: 0,
      createdAt: '2026-11-08T00:00:00Z',
    })
    success(response, {
      id: 1,
      username: body.username,
      email: body.email,
      createdAt: '2026-11-08T00:00:00Z',
    }, 201, '注册成功')
    return
  }

  if (request.method === 'POST' && url.pathname === '/api/v1/auth/login') {
    const body = await readJson(request)
    if (
      !state.user ||
      body.username !== state.user.username ||
      body.password !== state.user.password
    ) {
      failure(response, 401, 'AUTH_INVALID_CREDENTIALS', '用户名或密码错误')
      return
    }
    success(response, {
      token: TOKEN,
      tokenName: 'Authorization',
      expiresAt: '2026-11-08T02:00:00Z',
      user: {
        id: 1,
        username: state.user.username,
        email: state.user.email,
        createdAt: '2026-11-08T00:00:00Z',
      },
    })
    return
  }

  if (request.headers.authorization !== `Bearer ${TOKEN}`) {
    failure(response, 401, 'AUTH_REQUIRED', '请先登录')
    return
  }

  if (request.method === 'GET' && url.pathname === '/api/v1/collections') {
    success(response, state.collections)
    return
  }

  if (request.method === 'POST' && url.pathname === '/api/v1/links') {
    if (state.rateLimitCreateOnce) {
      state.rateLimitCreateOnce = false
      failure(
        response,
        429,
        'RATE_LIMITED',
        '创建收藏过于频繁',
        { 'Retry-After': '1' },
      )
      return
    }

    const body = await readJson(request)
    if (state.duplicateUrls.has(body.url)) {
      failure(response, 409, 'LINK_ALREADY_EXISTS', '该链接已经保存')
      return
    }

    const id = state.nextJobId++
    const status = state.pendingJobUrls.has(body.url)
      ? 'PENDING'
      : state.failedJobUrls.has(body.url)
        ? 'FAILED'
        : 'SUCCEEDED'
    const job = {
      id,
      linkId: id,
      url: body.url,
      status,
      errorCode: status === 'FAILED' ? 'CAPTURE_EXECUTION_FAILED' : null,
      errorMessage: status === 'FAILED' ? '模拟真实网页抓取失败' : null,
      assets: status === 'SUCCEEDED'
        ? jobAssets(id, !state.missingAssetUrls.has(body.url))
        : [],
    }
    state.jobs.set(id, job)

    success(response, {
      link: {
        id,
        collectionId: body.collectionId,
        originalUrl: body.url,
        title: '',
        description: '',
        createdAt: '2026-11-08T00:00:00Z',
        updatedAt: '2026-11-08T00:00:00Z',
      },
      job: jobPayload(job),
    }, 201, '链接保存成功')
    return
  }

  const jobMatch = url.pathname.match(/^\/api\/v1\/jobs\/(\d+)$/)
  if (request.method === 'GET' && jobMatch) {
    const job = state.jobs.get(Number(jobMatch[1]))
    if (!job) {
      failure(response, 404, 'NOT_FOUND', '任务不存在')
      return
    }
    success(response, jobPayload(job))
    return
  }

  const assetMatch = url.pathname.match(/^\/api\/v1\/assets\/(\d+)$/)
  if (request.method === 'GET' && assetMatch) {
    const assetId = Number(assetMatch[1])
    const job = [...state.jobs.values()].find((item) =>
      item.assets.some((asset) => asset.id === assetId),
    )
    if (!job) {
      failure(response, 404, 'NOT_FOUND', '资产不存在')
      return
    }
    if (state.unreadableAssetUrls.has(job.url)) {
      failure(response, 500, 'INTERNAL_ERROR', '模拟资产读取失败')
      return
    }
    response.writeHead(200, {
      'Content-Type': 'text/html; charset=utf-8',
      'X-Content-Type-Options': 'nosniff',
    })
    response.end(`<html><body>asset ${assetId}</body></html>`)
    return
  }

  if (request.method === 'POST' && url.pathname === '/api/v1/auth/logout') {
    success(response, null)
    return
  }

  failure(response, 404, 'NOT_FOUND', `未实现 ${request.method} ${url.pathname}`)
}

async function startFakeApi(overrides = {}) {
  const state = createState(overrides)
  const server = createServer((request, response) => {
    handleRequest(state, request, response).catch((error) => {
      failure(response, 500, 'INTERNAL_ERROR', error.message)
    })
  })
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  const { port } = server.address()
  return {
    state,
    baseUrl: `http://127.0.0.1:${port}`,
    close: () => new Promise((resolve, reject) => {
      server.close((error) => (error ? reject(error) : resolve()))
    }),
  }
}

async function createDataset(overrides = {}) {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'm9-real-urls-'))
  const cases = Array.from({ length: 30 }, (_, index) => {
    const caseNumber = index + 1
    return {
      id: `case-${caseNumber}`,
      category: caseNumber <= 26 ? 'fixture-positive' : 'fixture-negative',
      url: `https://fixture.test/case-${caseNumber}`,
      expectedOutcome: caseNumber <= 26
        ? 'CAPTURE_SUCCEEDED'
        : 'CAPTURE_FAILED',
    }
  })
  const datasetPath = path.join(directory, 'dataset.json')
  const outputPath = path.join(directory, 'results.json')
  await writeFile(
    datasetPath,
    JSON.stringify({ schemaVersion: 1, cases, ...overrides.dataset }, null, 2),
    'utf8',
  )
  return { cases, datasetPath, outputPath }
}

function runScript(args, env = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(
      POWERSHELL,
      ['-NoProfile', '-NonInteractive', '-File', SCRIPT_PATH, ...args],
      {
        cwd: PROJECT_ROOT,
        env: { ...process.env, ...env },
        windowsHide: true,
      },
    )
    let stdout = ''
    let stderr = ''
    const timer = setTimeout(() => {
      child.kill()
      reject(new Error(`acceptance script timed out\nstdout:\n${stdout}\nstderr:\n${stderr}`))
    }, 30_000)
    child.stdout.on('data', (chunk) => {
      stdout += chunk.toString()
    })
    child.stderr.on('data', (chunk) => {
      stderr += chunk.toString()
    })
    child.once('error', (error) => {
      clearTimeout(timer)
      reject(error)
    })
    child.once('exit', (code) => {
      clearTimeout(timer)
      resolve({ code, stdout, stderr })
    })
  })
}

async function runAcceptance(api, dataset, extraArgs = []) {
  const hasJobTimeout = extraArgs.includes('-JobTimeoutSeconds')
  const hasPollInterval = extraArgs.includes('-PollIntervalSeconds')
  const timingArgs = [
    ...(hasJobTimeout ? [] : ['-JobTimeoutSeconds', '5']),
    ...(hasPollInterval ? [] : ['-PollIntervalSeconds', '1']),
  ]
  return runScript(
    [
      '-BaseUrl',
      api.baseUrl,
      '-DatasetFile',
      dataset.datasetPath,
      '-OutputFile',
      dataset.outputPath,
      ...timingArgs,
      ...extraArgs,
    ],
  )
}

async function readReport(dataset) {
  return JSON.parse(await readFile(dataset.outputPath, 'utf8'))
}

test('M9.1 dataset freezes thirty unique positive and negative cases', async () => {
  const dataset = JSON.parse(await readFile(DATASET_PATH, 'utf8'))

  assert.equal(dataset.schemaVersion, 1)
  assert.equal(dataset.cases.length, 30)

  const ids = dataset.cases.map((item) => item.id)
  const urls = dataset.cases.map((item) => item.url)
  assert.equal(new Set(ids).size, 30)
  assert.equal(new Set(urls).size, 30)

  const positive = dataset.cases.filter(
    (item) => item.expectedOutcome === 'CAPTURE_SUCCEEDED',
  )
  const negative = dataset.cases.filter(
    (item) => item.expectedOutcome === 'CAPTURE_FAILED',
  )
  assert.equal(positive.length, 26)
  assert.equal(negative.length, 4)

  const categories = new Set(dataset.cases.map((item) => item.category))
  for (const category of [
    'baseline',
    'Chinese docs',
    'GitHub',
    'image-heavy',
    'direct PDF',
    'direct image',
    'redirect',
    'HTTP error page',
    'timeout boundary',
    'redirect limit',
    'response limit',
    'metadata address',
  ]) {
    assert.ok(categories.has(category), `missing category: ${category}`)
  }
})

test('creates a passing report when positive and negative outcomes match', async (t) => {
  const api = await startFakeApi()
  t.after(() => api.close())
  const dataset = await createDataset()
  for (const item of dataset.cases.slice(26)) {
    api.state.failedJobUrls.add(item.url)
  }

  const result = await runAcceptance(api, dataset)

  assert.equal(result.code, 0, result.stderr)
  assert.match(result.stdout, /"passed":\s*true/)
  const report = await readReport(dataset)
  assert.equal(report.summary.total, 30)
  assert.equal(report.summary.positiveExpected, 26)
  assert.equal(report.summary.positiveSucceeded, 26)
  assert.equal(report.summary.positiveSuccessRate, 100)
  assert.equal(report.summary.negativeExpected, 4)
  assert.equal(report.summary.negativeMatched, 4)
  assert.equal(report.summary.passed, true)
  assert.equal(report.cases.every((item) => item.matched), true)
  assert.equal(report.cases.slice(0, 26).every((item) => item.assetReadable), true)
  assert.equal(report.cases.slice(26).every((item) => item.assetReadable === null), true)
  assert.equal(report.cases.slice(26).every((item) => item.jobStatus === 'FAILED'), true)
  assert.equal(`${result.stdout}\n${result.stderr}`.includes(TOKEN), false)
  assert.equal(JSON.stringify(report).toLowerCase().includes('authorization'), false)
})

test('does not count a succeeded job without assets as a positive success', async (t) => {
  const api = await startFakeApi()
  t.after(() => api.close())
  const dataset = await createDataset()
  for (const item of dataset.cases.slice(26)) {
    api.state.failedJobUrls.add(item.url)
  }
  api.state.missingAssetUrls.add(dataset.cases[0].url)

  const result = await runAcceptance(api, dataset)

  assert.equal(result.code, 0, result.stderr)
  const report = await readReport(dataset)
  assert.equal(report.cases[0].jobStatus, 'SUCCEEDED')
  assert.equal(report.cases[0].assetReadable, false)
  assert.equal(report.cases[0].matched, false)
  assert.equal(report.summary.positiveSucceeded, 25)
  assert.equal(report.summary.passed, true)
})

test('does not count an unreadable asset as a positive success', async (t) => {
  const api = await startFakeApi()
  t.after(() => api.close())
  const dataset = await createDataset()
  for (const item of dataset.cases.slice(26)) {
    api.state.failedJobUrls.add(item.url)
  }
  api.state.unreadableAssetUrls.add(dataset.cases[0].url)

  const result = await runAcceptance(api, dataset)

  assert.equal(result.code, 0, result.stderr)
  const report = await readReport(dataset)
  assert.equal(report.cases[0].jobStatus, 'SUCCEEDED')
  assert.equal(report.cases[0].assetReadable, false)
  assert.match(report.cases[0].errorMessage, /资产/)
})

test('bounds a pending job with the configured timeout', async (t) => {
  const api = await startFakeApi()
  t.after(() => api.close())
  const dataset = await createDataset()
  for (const item of dataset.cases.slice(26)) {
    api.state.failedJobUrls.add(item.url)
  }
  api.state.pendingJobUrls.add(dataset.cases[0].url)

  const result = await runAcceptance(
    api,
    dataset,
    ['-JobTimeoutSeconds', '1', '-PollIntervalSeconds', '1'],
  )

  assert.equal(result.code, 0, result.stderr)
  const report = await readReport(dataset)
  assert.equal(report.cases[0].actualOutcome, 'POLL_TIMEOUT')
  assert.equal(report.cases[0].matched, false)
})

test('fails the aggregate gate when positive success drops below ninety percent', async (t) => {
  const api = await startFakeApi()
  t.after(() => api.close())
  const dataset = await createDataset()
  for (const item of dataset.cases.slice(26)) {
    api.state.failedJobUrls.add(item.url)
  }
  for (const item of dataset.cases.slice(0, 4)) {
    api.state.missingAssetUrls.add(item.url)
  }

  const result = await runAcceptance(api, dataset)

  assert.notEqual(result.code, 0)
  const report = await readReport(dataset)
  assert.equal(report.summary.positiveSucceeded, 22)
  assert.equal(report.summary.positiveSuccessRate, 84.6)
  assert.equal(report.summary.passed, false)
})

test('retries a rate-limited request using Retry-After', async (t) => {
  const api = await startFakeApi({ rateLimitCreateOnce: true })
  t.after(() => api.close())
  const dataset = await createDataset()
  for (const item of dataset.cases.slice(26)) {
    api.state.failedJobUrls.add(item.url)
  }

  const result = await runAcceptance(api, dataset)

  assert.equal(result.code, 0, result.stderr)
  assert.equal(
    api.state.requests.filter(
      (request) => request.method === 'POST' && request.path === '/api/v1/links',
    ).length,
    31,
  )
})

test('records a duplicate rejection as a client error', async (t) => {
  const api = await startFakeApi()
  t.after(() => api.close())
  const dataset = await createDataset()
  for (const item of dataset.cases.slice(26)) {
    api.state.failedJobUrls.add(item.url)
  }
  api.state.duplicateUrls.add(dataset.cases[0].url)

  const result = await runAcceptance(api, dataset)

  assert.equal(result.code, 0, result.stderr)
  const report = await readReport(dataset)
  assert.equal(report.cases[0].actualOutcome, 'CLIENT_ERROR')
  assert.equal(report.cases[0].createHttpStatus, 409)
  assert.equal(report.cases[0].matched, false)
})
