import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const TEST_DIR = path.dirname(fileURLToPath(import.meta.url))
const SCRIPT_PATH = path.resolve(TEST_DIR, '..', 'seed-demo-account.ps1')
const POWERSHELL = process.env.POWERSHELL_EXE || 'pwsh'
const PASSWORD = 'Demo-Test-Password-9a!'
const TOKEN = 'demo-test-token'

function createState(overrides = {}) {
  return {
    ready: true,
    registrationDisabled: false,
    failJobUrl: null,
    failCollectionName: null,
    user: null,
    collections: [],
    tags: [],
    links: [],
    jobs: new Map(),
    requests: [],
    ...overrides,
  }
}

function json(response, status, payload) {
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
  })
  response.end(JSON.stringify(payload))
}

function success(response, data, status = 200, message = 'success') {
  json(response, status, {
    data,
    message,
    requestId: 'seed-test-request',
  })
}

function failure(response, status, code, message) {
  json(response, status, {
    code,
    message,
    details: {},
    requestId: 'seed-test-request',
  })
}

async function readJson(request) {
  const chunks = []
  for await (const chunk of request) chunks.push(chunk)
  if (!chunks.length) return {}
  return JSON.parse(Buffer.concat(chunks).toString('utf8'))
}

function authorized(request) {
  return request.headers.authorization === `Bearer ${TOKEN}`
}

function publicLink(link) {
  return {
    id: link.id,
    collectionId: link.collectionId,
    originalUrl: link.originalUrl,
    title: link.title,
    description: link.description,
    createdAt: '2026-11-05T00:00:00Z',
    updatedAt: '2026-11-05T00:00:00Z',
  }
}

function completedJob(link, failed) {
  return {
    id: link.jobId,
    linkId: link.id,
    status: failed ? 'FAILED' : 'SUCCEEDED',
    attemptCount: failed ? 1 : 1,
    maxAttempts: 3,
    errorCode: failed ? 'CAPTURE_EXECUTION_FAILED' : null,
    errorMessage: failed ? '模拟抓取失败' : null,
    nextAttemptAt: null,
    startedAt: '2026-11-05T00:00:00Z',
    finishedAt: '2026-11-05T00:01:00Z',
    assets: failed
      ? []
      : ['HTML', 'PDF', 'SCREENSHOT'].map((format, index) => ({
          id: index + 1,
          format,
          mimeType: 'application/octet-stream',
          fileSizeBytes: 100,
          createdAt: '2026-11-05T00:01:00Z',
        })),
  }
}

function ensureInbox(state) {
  if (state.collections.some((collection) => collection.isInbox)) return
  state.collections.push({
    id: state.collections.length + 1,
    name: 'Inbox',
    description: '',
    isInbox: true,
    linkCount: 0,
    createdAt: '2026-11-05T00:00:00Z',
  })
}

async function handleRequest(state, request, response) {
  const url = new URL(request.url, 'http://127.0.0.1')
  state.requests.push({
    method: request.method,
    path: `${url.pathname}${url.search}`,
  })

  if (request.method === 'GET' && url.pathname === '/api/v1/health/ready') {
    if (!state.ready) {
      json(response, 503, {
        code: 'SERVICE_UNAVAILABLE',
        message: 'MySQL/Redis 未就绪',
        details: { mysql: 'DOWN', redis: 'UP' },
        requestId: 'seed-test-request',
      })
      return
    }
    success(response, { mysql: 'UP', redis: 'UP' })
    return
  }

  if (request.method === 'POST' && url.pathname === '/api/v1/auth/register') {
    if (state.registrationDisabled) {
      failure(response, 403, 'REGISTRATION_DISABLED', '当前环境未开放注册')
      return
    }
    const body = await readJson(request)
    if (state.user) {
      failure(response, 409, 'USERNAME_ALREADY_EXISTS', '用户名已存在')
      return
    }
    state.user = {
      id: 1,
      username: body.username,
      email: body.email,
      password: body.password,
    }
    ensureInbox(state)
    success(response, {
      id: state.user.id,
      username: state.user.username,
      email: state.user.email,
      createdAt: '2026-11-05T00:00:00Z',
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
      expiresAt: '2026-11-05T02:00:00Z',
      user: {
        id: state.user.id,
        username: state.user.username,
        email: state.user.email,
        createdAt: '2026-11-05T00:00:00Z',
      },
    }, 200, '登录成功')
    return
  }

  if (!authorized(request)) {
    failure(response, 401, 'AUTH_REQUIRED', '请先登录')
    return
  }

  if (request.method === 'GET' && url.pathname === '/api/v1/collections') {
    success(response, state.collections)
    return
  }

  if (request.method === 'POST' && url.pathname === '/api/v1/collections') {
    const body = await readJson(request)
    if (state.collections.some((collection) => collection.name.toLowerCase() === body.name.toLowerCase())) {
      failure(response, 409, 'COLLECTION_NAME_ALREADY_EXISTS', '集合名称已存在')
      return
    }
    if (body.name === state.failCollectionName) {
      failure(response, 500, 'INTERNAL_ERROR', '模拟集合创建失败')
      return
    }
    const collection = {
      id: state.collections.length + 1,
      name: body.name,
      description: body.description,
      isInbox: false,
      linkCount: 0,
      createdAt: '2026-11-05T00:00:00Z',
    }
    state.collections.push(collection)
    success(response, collection, 201, '集合创建成功')
    return
  }

  if (request.method === 'GET' && url.pathname === '/api/v1/tags') {
    success(response, state.tags)
    return
  }

  if (request.method === 'POST' && url.pathname === '/api/v1/tags') {
    const body = await readJson(request)
    if (state.tags.some((tag) => tag.name.toLowerCase() === body.name.toLowerCase())) {
      failure(response, 409, 'TAG_NAME_ALREADY_EXISTS', '标签名称已存在')
      return
    }
    const tag = {
      id: state.tags.length + 1,
      name: body.name,
      linkCount: 0,
      createdAt: '2026-11-05T00:00:00Z',
    }
    state.tags.push(tag)
    success(response, tag, 201, '标签创建成功')
    return
  }

  if (request.method === 'GET' && url.pathname === '/api/v1/links') {
    success(response, state.links.map(publicLink))
    return
  }

  if (request.method === 'GET' && url.pathname === '/api/v1/assets') {
    const linkId = Number(url.searchParams.get('linkId'))
    const link = state.links.find((item) => item.id === linkId)
    const jobState = link ? state.jobs.get(link.jobId) : null
    if (!link || !jobState) {
      failure(response, 404, 'NOT_FOUND', '资源不存在')
      return
    }
    success(response, completedJob(link, jobState.failed).assets)
    return
  }

  if (request.method === 'POST' && url.pathname === '/api/v1/links') {
    const body = await readJson(request)
    if (state.links.some((link) => link.originalUrl === body.url)) {
      failure(response, 409, 'LINK_ALREADY_EXISTS', '该链接已经保存')
      return
    }
    const id = state.links.length + 1
    const link = {
      id,
      jobId: id,
      collectionId: body.collectionId,
      originalUrl: body.url,
      title: body.title,
      description: body.description,
    }
    state.links.push(link)
    state.jobs.set(link.jobId, {
      failed: state.failJobUrl === body.url,
    })
    success(response, {
      link: publicLink(link),
      job: {
        id: link.jobId,
        linkId: link.id,
        status: 'PENDING',
        attemptCount: 0,
        maxAttempts: 3,
        errorCode: null,
        errorMessage: null,
        nextAttemptAt: null,
        startedAt: null,
        finishedAt: null,
        assets: [],
      },
    }, 201, '链接保存成功')
    return
  }

  const jobMatch = url.pathname.match(/^\/api\/v1\/jobs\/(\d+)$/)
  if (request.method === 'GET' && jobMatch) {
    const jobId = Number(jobMatch[1])
    const link = state.links.find((item) => item.jobId === jobId)
    const jobState = state.jobs.get(jobId)
    if (!link || !jobState) {
      failure(response, 404, 'NOT_FOUND', '资源不存在')
      return
    }
    success(response, completedJob(link, jobState.failed))
    return
  }

  const readableMatch = url.pathname.match(/^\/api\/v1\/links\/(\d+)\/readable$/)
  if (request.method === 'GET' && readableMatch) {
    const link = state.links.find((item) => item.id === Number(readableMatch[1]))
    if (!link) {
      failure(response, 404, 'NOT_FOUND', '资源不存在')
      return
    }
    success(response, {
      link: publicLink(link),
      textContent: `Readable content for ${link.originalUrl}`,
    })
    return
  }

  if (request.method === 'POST' && url.pathname === '/api/v1/auth/logout') {
    success(response, null, 200, '退出成功')
    return
  }

  failure(response, 404, 'NOT_FOUND', `测试夹具未实现：${request.method} ${url.pathname}`)
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
  const address = server.address()
  return {
    state,
    baseUrl: `http://127.0.0.1:${address.port}`,
    close: () => new Promise((resolve, reject) => {
      server.close((error) => (error ? reject(error) : resolve()))
    }),
  }
}

function runSeed(baseUrl, password = PASSWORD, extraArgs = []) {
  return new Promise((resolve, reject) => {
    const child = spawn(
      POWERSHELL,
      [
        '-NoProfile',
        '-NonInteractive',
        '-File',
        SCRIPT_PATH,
        '-BaseUrl',
        baseUrl,
        ...extraArgs,
      ],
      {
        cwd: path.resolve(TEST_DIR, '..', '..'),
        env: {
          ...process.env,
          WEBCOLLECTOR_DEMO_PASSWORD: password,
        },
        windowsHide: true,
      },
    )
    let stdout = ''
    let stderr = ''
    const timer = setTimeout(() => {
      child.kill()
      reject(new Error(`seed script timed out\nstdout:\n${stdout}\nstderr:\n${stderr}`))
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

function assertSeeded(state) {
  assert.equal(state.user?.username, 'demo')
  assert.equal(state.collections.length, 3)
  assert.deepEqual(
    state.collections.map((collection) => collection.name).sort(),
    ['Inbox', '协议档案', '工程阅读'].sort(),
  )
  assert.equal(state.tags.length, 6)
  assert.equal(state.links.length, 6)
  assert.equal(state.jobs.size, 6)
}

test('fresh run creates the demo account and complete seed data', async (t) => {
  const api = await startFakeApi()
  t.after(() => api.close())

  const result = await runSeed(api.baseUrl)

  assert.equal(result.code, 0, result.stderr)
  assertSeeded(api.state)
  assert.match(result.stdout, /demo/)
  assert.match(result.stdout, /链接：新建 6，复用 0，成功 6，失败 0/)
  assert.ok(
    api.state.requests.some(
      (request) =>
        request.method === 'POST' && request.path === '/api/v1/auth/logout',
    ),
  )
  const combinedOutput = `${result.stdout}\n${result.stderr}`
  assert.doesNotMatch(combinedOutput, new RegExp(PASSWORD.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
  assert.doesNotMatch(combinedOutput, new RegExp(TOKEN))
})

test('second run reuses every resource without creating duplicates', async (t) => {
  const api = await startFakeApi()
  t.after(() => api.close())

  assert.equal((await runSeed(api.baseUrl)).code, 0)
  const firstCounts = {
    collections: api.state.collections.length,
    tags: api.state.tags.length,
    links: api.state.links.length,
    jobs: api.state.jobs.size,
  }

  const second = await runSeed(api.baseUrl)

  assert.equal(second.code, 0, second.stderr)
  assert.deepEqual(
    {
      collections: api.state.collections.length,
      tags: api.state.tags.length,
      links: api.state.links.length,
      jobs: api.state.jobs.size,
    },
    firstCounts,
  )
  assert.match(second.stdout, /复用/)
  assert.match(second.stdout, /链接：新建 0，复用 6，成功 6，失败 0/)
})

test('wrong password fails before adding demo resources', async (t) => {
  const api = await startFakeApi()
  t.after(() => api.close())

  assert.equal((await runSeed(api.baseUrl)).code, 0)
  const before = {
    collections: api.state.collections.length,
    tags: api.state.tags.length,
    links: api.state.links.length,
  }

  const result = await runSeed(api.baseUrl, 'Wrong-Demo-Password-9a!')

  assert.notEqual(result.code, 0)
  assert.match(`${result.stdout}\n${result.stderr}`, /密码|登录/)
  assert.deepEqual(
    {
      collections: api.state.collections.length,
      tags: api.state.tags.length,
      links: api.state.links.length,
    },
    before,
  )
})

test('non-loopback backend URL is rejected before a request', async () => {
  const result = await runSeed('http://example.com')

  assert.notEqual(result.code, 0)
  assert.match(`${result.stdout}\n${result.stderr}`, /localhost|127\.0\.0\.1|本机/)
})

test('unready dependencies fail with the readiness reason', async (t) => {
  const api = await startFakeApi({ ready: false })
  t.after(() => api.close())

  const result = await runSeed(api.baseUrl)

  assert.notEqual(result.code, 0)
  assert.match(`${result.stdout}\n${result.stderr}`, /MySQL|Redis|就绪/)
  assert.equal(api.state.user, null)
})

test('disabled registration fails clearly on a fresh database', async (t) => {
  const api = await startFakeApi({ registrationDisabled: true })
  t.after(() => api.close())

  const result = await runSeed(api.baseUrl)

  assert.notEqual(result.code, 0)
  assert.match(`${result.stdout}\n${result.stderr}`, /注册/)
  assert.equal(api.state.links.length, 0)
})

test('capture failure returns non-zero while preserving all created links', async (t) => {
  const failingUrl = 'https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/CORS'
  const api = await startFakeApi({ failJobUrl: failingUrl })
  t.after(() => api.close())

  const result = await runSeed(api.baseUrl)

  assert.notEqual(result.code, 0)
  assert.match(`${result.stdout}\n${result.stderr}`, /FAILED/)
  assert.match(`${result.stdout}\n${result.stderr}`, /CORS/)
  assert.equal(api.state.links.length, 6)
})

test('login session is closed when resource creation fails', async (t) => {
  const api = await startFakeApi({ failCollectionName: '工程阅读' })
  t.after(() => api.close())

  const result = await runSeed(api.baseUrl)

  assert.notEqual(result.code, 0)
  assert.match(`${result.stdout}\n${result.stderr}`, /集合创建失败/)
  assert.ok(
    api.state.requests.some(
      (request) =>
        request.method === 'POST' && request.path === '/api/v1/auth/logout',
    ),
  )
})
