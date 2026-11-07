import { randomUUID } from 'node:crypto'
import { expect, request as playwrightRequest, test } from '@playwright/test'

const API_BASE_URL = (process.env.E2E_API_URL || 'http://127.0.0.1:8080')
  .replace(/\/+$/, '')
const TOKEN_KEY = 'webcollector-token'

let fixture

function createIdentity(prefix) {
  const suffix = `${Date.now().toString(36)}${randomUUID().replaceAll('-', '').slice(0, 8)}`

  return {
    username: `${prefix}-${suffix}`.slice(0, 32),
    email: `${prefix}-${suffix}@example.com`,
    password: `M7-${randomUUID()}-9a`,
  }
}

function authorization(token) {
  return {
    Authorization: `Bearer ${token}`,
  }
}

async function readBody(response, label) {
  const body = await response.json().catch(() => null)
  expect(response.ok(), `${label}: HTTP ${response.status()} ${JSON.stringify(body)}`).toBe(true)
  return body
}

async function registerAndLogin(api, identity, label) {
  const registerResponse = await api.post('/api/v1/auth/register', {
    data: identity,
  })
  await readBody(registerResponse, `${label} 注册`)

  const loginResponse = await api.post('/api/v1/auth/login', {
    data: {
      username: identity.username,
      password: identity.password,
    },
  })
  const loginBody = await readBody(loginResponse, `${label} 登录`)
  const token = loginBody?.data?.token

  expect(token, `${label} 登录响应缺少 token`).toBeTruthy()
  return {
    ...identity,
    token,
  }
}

async function createSecurityFixture(api) {
  const suffix = randomUUID().replaceAll('-', '').slice(0, 12)
  const owner = await registerAndLogin(
    api,
    createIdentity('m7-owner'),
    '用户 A',
  )
  const stranger = await registerAndLogin(
    api,
    createIdentity('m7-stranger'),
    '用户 B',
  )

  const collectionsResponse = await api.get('/api/v1/collections', {
    headers: authorization(owner.token),
  })
  const collectionsBody = await readBody(collectionsResponse, '读取用户 A 集合')
  const inbox = collectionsBody?.data?.find((collection) => collection.isInbox)
  expect(inbox, '用户 A 缺少默认收件箱').toBeTruthy()

  const tagResponse = await api.post('/api/v1/tags', {
    headers: authorization(owner.token),
    data: {
      name: `M7私密标签-${suffix}`,
    },
  })
  const tagBody = await readBody(tagResponse, '创建用户 A 标签')
  const tag = tagBody.data

  const title = `M7越权证据-${suffix}`
  const description = `只属于用户 A 的描述-${suffix}`
  const linkResponse = await api.post('/api/v1/links', {
    headers: authorization(owner.token),
    data: {
      url: `https://example.invalid/m7-failure-${suffix}`,
      collectionId: inbox.id,
      title,
      description,
      tagIds: [tag.id],
    },
  })
  const linkBody = await readBody(linkResponse, '创建用户 A 链接')

  return {
    owner,
    stranger,
    tag,
    title,
    description,
    link: linkBody.data.link,
  }
}

async function cleanupFixture(api, currentFixture) {
  if (!currentFixture) return

  await api
    .delete(`/api/v1/links/${currentFixture.link.id}`, {
      headers: authorization(currentFixture.owner.token),
    })
    .catch(() => null)
  await api
    .delete(`/api/v1/tags/${currentFixture.tag.id}`, {
      headers: authorization(currentFixture.owner.token),
    })
    .catch(() => null)
}

async function authenticatePage(page, token) {
  await page.goto('/login')
  await page.evaluate(
    ([key, value]) => localStorage.setItem(key, value),
    [TOKEN_KEY, token],
  )
}

async function openOwnedDetail(page, token, linkId, suffix = '') {
  await authenticatePage(page, token)
  await page.goto(`/links/${linkId}${suffix}`)
}

async function mockJson(route, status, payload) {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(payload),
  })
}

function apiError(code, message) {
  return {
    code,
    message,
    details: {},
    requestId: 'e2e-request',
  }
}

function apiSuccess(data) {
  return {
    data,
    message: 'success',
    requestId: 'e2e-request',
  }
}

function jobDetail(linkId, jobId, status, attemptCount, errorMessage = null) {
  return {
    id: jobId,
    linkId,
    status,
    attemptCount,
    maxAttempts: 3,
    errorCode: errorMessage ? 'CAPTURE_EXECUTION_FAILED' : null,
    errorMessage,
    nextAttemptAt: null,
    startedAt: '2026-11-05T00:00:00Z',
    finishedAt: status === 'SUCCEEDED' || status === 'FAILED'
      ? '2026-11-05T00:01:00Z'
      : null,
    assets: [],
  }
}

test.describe.serial('M7 越权与失败链路', () => {
  test.beforeAll(async () => {
    const api = await playwrightRequest.newContext({
      baseURL: API_BASE_URL,
      extraHTTPHeaders: {
        Accept: 'application/json',
      },
    })

    try {
      fixture = await createSecurityFixture(api)
      fixture.api = api
    } catch (error) {
      await api.dispose()
      throw error
    }
  })

  test.afterAll(async () => {
    if (!fixture?.api) return
    await cleanupFixture(fixture.api, fixture)
    await fixture.api.dispose()
  })

  // [测试意图] 验证真实后端下用户 B 无法读取用户 A 的详情，也不会触发受保护子资源请求。
  test('用户 B 直接访问用户 A 的详情时只看到统一 404', async ({
    page,
  }) => {
    await page.goto('/login')
    await page.locator('input[name="username"]').fill(fixture.stranger.username)
    await page.locator('input[name="password"]').fill(fixture.stranger.password)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(/\/links$/)

    const apiRequests = []
    page.on('request', (request) => {
      const url = new URL(request.url())
      if (url.pathname.startsWith('/api/v1/')) {
        apiRequests.push(`${url.pathname}${url.search}`)
      }
    })

    const detailResponsePromise = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return (
        response.request().method() === 'GET' &&
        url.pathname === `/api/v1/links/${fixture.link.id}`
      )
    })

    await page.goto(`/links/${fixture.link.id}`)
    expect((await detailResponsePromise).status()).toBe(404)

    await expect(
      page.getByRole('heading', { name: '没有找到这条收藏', exact: true }),
    ).toBeVisible()
    await expect(
      page.getByRole('button', { name: '返回收藏', exact: true }),
    ).toBeVisible()
    await expect(page.locator('body')).not.toContainText(fixture.title)
    await expect(page.locator('body')).not.toContainText(fixture.description)
    await expect(page.locator('body')).not.toContainText(fixture.tag.name)

    const protectedRequests = apiRequests.filter((requestUrl) => {
      const url = new URL(requestUrl, API_BASE_URL)
      return (
        url.pathname === `/api/v1/links/${fixture.link.id}/readable` ||
        url.pathname === `/api/v1/links/${fixture.link.id}/tags` ||
        url.pathname === '/api/v1/assets' ||
        url.pathname.startsWith('/api/v1/jobs/')
      )
    })
    expect(protectedRequests).toEqual([])
  })

  // [测试意图] 验证非法路径参数在客户端被拒绝，不向详情接口发送无效 id。
  test('非法链接 id 直接显示恢复态且不请求详情接口', async ({ page }) => {
    await authenticatePage(page, fixture.owner.token)

    const invalidRequests = []
    page.on('request', (request) => {
      const url = new URL(request.url())
      if (url.pathname.startsWith('/api/v1/links/')) {
        invalidRequests.push(url.pathname)
      }
    })

    await page.goto('/links/not-a-number')

    await expect(
      page.getByRole('heading', {
        name: '链接详情没有加载出来',
        exact: true,
      }),
    ).toBeVisible()
    await expect(page.getByText('链接编号无效。', { exact: true })).toBeVisible()
    expect(invalidRequests).toEqual([])
  })

  // [测试意图] 验证详情接口失败只显示整页恢复态，重试成功后恢复真实业务内容。
  test('详情接口失败后可以通过重试恢复整页', async ({ page }) => {
    const detailPattern = `**/api/v1/links/${fixture.link.id}`

    await page.route(detailPattern, async (route) => {
      if (route.request().method() !== 'GET') {
        await route.continue()
        return
      }
      await mockJson(route, 500, apiError('INTERNAL_ERROR', '测试中详情暂时不可用'))
    })
    await openOwnedDetail(page, fixture.owner.token, fixture.link.id)

    await expect(
      page.getByRole('heading', {
        name: '链接详情没有加载出来',
        exact: true,
      }),
    ).toBeVisible()
    await expect(
      page.getByText('测试中详情暂时不可用', { exact: true }),
    ).toBeVisible()

    await page.unroute(detailPattern)
    await page.getByRole('button', { name: '重试', exact: true }).click()

    await expect(
      page.getByRole('heading', { name: fixture.title, exact: true }),
    ).toBeVisible()
    await expect(
      page.getByRole('article', { name: '可读正文' }),
    ).toBeVisible()
  })

  // [测试意图] 验证正文块失败不覆盖证据和标签，并可单独重试为正文缺失状态。
  test('正文接口失败只影响正文区块', async ({ page }) => {
    const readablePattern = `**/api/v1/links/${fixture.link.id}/readable`

    await page.route(readablePattern, async (route) => {
      await mockJson(route, 500, apiError('INTERNAL_ERROR', '测试中正文不可用'))
    })
    await openOwnedDetail(page, fixture.owner.token, fixture.link.id)

    const readingRegion = page.getByRole('article', { name: '可读正文' })
    await expect(
      readingRegion.getByText('正文没有加载出来', { exact: true }),
    ).toBeVisible()
    await expect(
      page.getByRole('region', { name: '保存证据' }),
    ).toBeVisible()
    await expect(page.getByRole('region', { name: '标签' })).toBeVisible()

    await page.unroute(readablePattern)
    await readingRegion
      .getByRole('button', { name: '重新读取', exact: true })
      .click()
    await expect(
      readingRegion.getByRole('heading', { name: '正文尚未生成', exact: true }),
    ).toBeVisible()
  })

  // [测试意图] 验证资产接口失败不覆盖正文，重试后恢复空资产状态。
  test('证据接口失败只影响保存证据区块', async ({ page }) => {
    const assetsPattern = '**/api/v1/assets?linkId=*'

    await page.route(assetsPattern, async (route) => {
      await mockJson(route, 500, apiError('INTERNAL_ERROR', '测试中资产不可用'))
    })
    await openOwnedDetail(page, fixture.owner.token, fixture.link.id)

    const evidenceRegion = page.getByRole('region', { name: '保存证据' })
    await expect(
      evidenceRegion.getByText('证据列表没有加载出来', { exact: true }),
    ).toBeVisible()
    await expect(
      page.getByRole('article', { name: '可读正文' }),
    ).toBeVisible()
    await expect(page.getByRole('region', { name: '标签' })).toBeVisible()

    await page.unroute(assetsPattern)
    await evidenceRegion
      .getByRole('button', { name: '重试', exact: true })
      .click()
    await expect(
      evidenceRegion.getByText(/快照尚未生成/),
    ).toBeVisible()
  })

  // [测试意图] 验证标签资源不可见时使用不暗示接口未实现的文案，并可单独恢复。
  test('标签接口 404 只影响标签区块且使用不可见文案', async ({ page }) => {
    const tagsPattern = `**/api/v1/links/${fixture.link.id}/tags`

    await page.route(tagsPattern, async (route) => {
      await mockJson(route, 404, apiError('NOT_FOUND', '资源不存在'))
    })
    await openOwnedDetail(page, fixture.owner.token, fixture.link.id)

    const tagsRegion = page.getByRole('region', { name: '标签' })
    await expect(
      tagsRegion.getByText('标签当前不可见，正文与保存证据不受影响。', {
        exact: true,
      }),
    ).toBeVisible()
    await expect(
      page.getByRole('article', { name: '可读正文' }),
    ).toBeVisible()
    await expect(
      page.getByRole('region', { name: '保存证据' }),
    ).toBeVisible()

    await page.unroute(tagsPattern)
    await tagsRegion.getByRole('button', { name: '重试', exact: true }).click()
    await expect(tagsRegion.getByText(fixture.tag.name, { exact: true })).toBeVisible()
  })

  // [测试意图] 验证任务从运行中进入失败后展示真实错误、移除临时参数并停止轮询。
  test('任务进入 FAILED 后停止轮询并保留失败详情', async ({ page }) => {
    const jobId = 987654321
    const jobPattern = `**/api/v1/jobs/${jobId}`
    let calls = 0

    await page.route(jobPattern, async (route) => {
      calls += 1
      const status = calls === 1 ? 'RUNNING' : 'FAILED'
      const attemptCount = calls === 1 ? 1 : 3
      const errorMessage = calls === 1 ? null : '模拟页面无法访问'
      await mockJson(
        route,
        200,
        apiSuccess(jobDetail(fixture.link.id, jobId, status, attemptCount, errorMessage)),
      )
    })

    await openOwnedDetail(
      page,
      fixture.owner.token,
      fixture.link.id,
      `?job=${jobId}`,
    )

    const jobStatus = page.getByTestId('job-status')
    await expect(jobStatus).toHaveAttribute('data-status', 'RUNNING')
    await expect(jobStatus).toHaveAttribute('data-status', 'FAILED', {
      timeout: 10_000,
    })
    await expect(page.getByText('模拟页面无法访问', { exact: true })).toBeVisible()
    await expect(page.getByText('已尝试 3/3 次', { exact: true })).toBeVisible()
    await expect(page).not.toHaveURL(/[?&]job=/)

    await page.waitForTimeout(3_500)
    expect(calls).toBe(2)
  })

  // [测试意图] 验证任务接口 404 只影响任务面板，重试成功后恢复终态。
  test('任务接口 404 不覆盖正文和证据且可以重试', async ({ page }) => {
    const jobId = 987654322
    const jobPattern = `**/api/v1/jobs/${jobId}`
    let calls = 0

    await page.route(jobPattern, async (route) => {
      calls += 1
      if (calls === 1) {
        await mockJson(route, 404, apiError('NOT_FOUND', '资源不存在'))
        return
      }
      await mockJson(
        route,
        200,
        apiSuccess(jobDetail(fixture.link.id, jobId, 'SUCCEEDED', 2)),
      )
    })
    await openOwnedDetail(
      page,
      fixture.owner.token,
      fixture.link.id,
      `?job=${jobId}`,
    )

    const jobRegion = page.getByRole('region', { name: '当前任务' })
    await expect(jobRegion.getByText('资源不存在', { exact: true })).toBeVisible()
    await expect(
      page.getByRole('article', { name: '可读正文' }),
    ).toBeVisible()
    await expect(
      page.getByRole('region', { name: '保存证据' }),
    ).toBeVisible()

    await jobRegion.getByRole('button', { name: '重试', exact: true }).click()
    await expect(page.getByTestId('job-status')).toHaveAttribute(
      'data-status',
      'SUCCEEDED',
    )
  })

  // [测试意图] 验证详情接口 401 时清除本地 token 并回到登录页。
  test('详情接口 401 清除 token 并跳转登录', async ({ page }) => {
    const detailPattern = `**/api/v1/links/${fixture.link.id}`

    await page.route(detailPattern, async (route) => {
      if (route.request().method() !== 'GET') {
        await route.continue()
        return
      }
      await mockJson(route, 401, apiError('AUTH_REQUIRED', '请先登录'))
    })
    await openOwnedDetail(page, fixture.owner.token, fixture.link.id)

    await expect(page).toHaveURL(/\/login$/)
    await expect(
      page.getByRole('heading', { name: '登录', exact: true }),
    ).toBeVisible()
    await expect
      .poll(() => page.evaluate((key) => localStorage.getItem(key), TOKEN_KEY))
      .toBeNull()
    await expect(page.locator('body')).not.toContainText(fixture.title)
  })
})
