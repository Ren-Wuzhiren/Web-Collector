import { mkdir } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test } from '@playwright/test'

const TOKEN_KEY = 'webcollector-token'
const TOKEN = 'm8-admin-ui-token'
const PROJECT_ROOT = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
)
const VISUAL_OUTPUT_DIR = path.join(PROJECT_ROOT, '.taste-skill', 'm8')

function apiResponse(data, message = 'success') {
  return {
    data,
    message,
    requestId: 'm8-admin-e2e',
  }
}

async function installUser(page, role) {
  await page.goto('/login')
  await page.evaluate(
    ([key, token]) => localStorage.setItem(key, token),
    [TOKEN_KEY, TOKEN],
  )
  await page.route('**/api/v1/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse({
        id: 7,
        username: role === 'ADMIN' ? 'demo' : 'reader',
        email: 'demo@example.com',
        role,
        createdAt: '2026-11-05T00:00:00Z',
      })),
    })
  })
}

async function installEmptyCatalog(page) {
  await page.route('**/api/v1/collections', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse([])),
    })
  })
  await page.route('**/api/v1/tags', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse([])),
    })
  })
  await page.route('**/api/v1/links', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse([])),
    })
  })
}

function failedJobPage(items, nextBeforeId = null) {
  return apiResponse({
    items,
    nextBeforeId,
  })
}

function failedJob(overrides = {}) {
  return {
    jobId: 41,
    linkId: 18,
    ownerUsername: 'reader',
    sourceHost: 'example.com',
    attemptCount: 3,
    maxAttempts: 3,
    errorCode: 'CAPTURE_TIMEOUT',
    createdAt: '2026-11-05T01:00:00Z',
    finishedAt: '2026-11-05T01:02:00Z',
    ...overrides,
  }
}

async function installAdminData(page, {
  overview = {
    totalUsers: 8,
    totalLinks: 21,
    totalAssets: 56,
    pendingJobs: 2,
    runningJobs: 1,
    succeededJobs: 17,
    failedJobs: 4,
  },
  pages = [failedJobPage([failedJob()])],
  retryResponse = {
    sourceJobId: 41,
    job: {
      id: 99,
      linkId: 18,
      status: 'PENDING',
      attemptCount: 0,
      nextAttemptAt: '2026-11-05T02:00:00Z',
    },
  },
} = {}) {
  let overviewCalls = 0
  let failedCalls = 0
  let pageIndex = 0

  await page.route('**/api/v1/admin/overview', async (route) => {
    overviewCalls += 1
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse(overview)),
    })
  })
  await page.route('**/api/v1/admin/capture-jobs/failed**', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.fallback()
      return
    }
    failedCalls += 1
    const payload = pages[Math.min(pageIndex, pages.length - 1)]
    pageIndex += 1
    await route.fulfill({
      status: typeof payload === 'number' ? payload : 200,
      contentType: 'application/json',
      body: JSON.stringify(
        typeof payload === 'number'
          ? { code: 'INTERNAL_ERROR', message: '测试管理数据不可用' }
          : payload,
      ),
    })
  })
  await page.route(
    '**/api/v1/admin/capture-jobs/*/retry',
    async (route) => {
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(retryResponse, '已创建新的抓取任务')),
      })
    },
  )

  return {
    counts: () => ({ overviewCalls, failedCalls }),
  }
}

test.describe('M8 管理员运营页面', () => {
  // [测试意图] 验证普通用户看不到管理入口，直接访问管理地址会回到收藏页。
  test('普通用户不能进入管理区域', async ({ page }) => {
    await installUser(page, 'USER')
    await installEmptyCatalog(page)

    await page.goto('/admin')

    await expect(page).toHaveURL(/\/links$/)
    await expect(page.getByRole('link', { name: '管理' })).toHaveCount(0)
  })

  // [测试意图] 验证管理员可以看到导航、聚合指标和隐私安全的失败任务字段。
  test('管理员可以查看运营概览和失败任务', async ({ page }) => {
    await installUser(page, 'ADMIN')
    await installAdminData(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto('/admin')

    await expect(page.getByRole('heading', { name: '运营管理' })).toBeVisible()
    await expect(page.getByRole('link', { name: '管理' })).toBeVisible()
    await expect(page.getByTestId('admin-total-users')).toContainText('8')
    await expect(page.getByTestId('admin-total-links')).toContainText('21')
    await expect(page.getByTestId('admin-total-assets')).toContainText('56')
    await expect(page.getByText('example.com', { exact: true })).toBeVisible()
    await expect(page.getByText('CAPTURE_TIMEOUT', { exact: true })).toBeVisible()
    await expect(page.locator('body')).not.toContainText('https://')
    await expect(page.getByRole('button', { name: '重试' })).toBeVisible()
  })

  // [测试意图] 验证重试前有资产清理提示，确认后刷新概览和失败列表。
  test('管理员确认后重试并刷新数据', async ({ page }) => {
    await installUser(page, 'ADMIN')
    const data = await installAdminData(page)
    await page.goto('/admin')

    const retryRequest = page.waitForRequest((request) => {
      const url = new URL(request.url())
      return (
        request.method() === 'POST' &&
        url.pathname === '/api/v1/admin/capture-jobs/41/retry'
      )
    })
    await page.getByRole('button', { name: '重试' }).click()
    await expect(page.getByText(/会清理失败任务的部分证据/)).toBeVisible()
    await page.getByRole('button', { name: '确认重试' }).click()
    await retryRequest

    await expect.poll(() => data.counts().overviewCalls).toBeGreaterThan(1)
    await expect.poll(() => data.counts().failedCalls).toBeGreaterThan(1)
    await expect(page.getByText('新的抓取任务已创建')).toBeVisible()
  })

  // [测试意图] 验证游标“加载更多”只追加下一页并保留已有失败任务。
  test('失败任务支持加载更多', async ({ page }) => {
    await installUser(page, 'ADMIN')
    await installAdminData(page, {
      pages: [
        failedJobPage([
          failedJob({ jobId: 41, sourceHost: 'example.com' }),
        ], 41),
        failedJobPage([
          failedJob({
            jobId: 40,
            linkId: 17,
            ownerUsername: 'another-reader',
            sourceHost: 'developer.mozilla.org',
            errorCode: 'DNS_ERROR',
          }),
        ]),
      ],
    })
    await page.goto('/admin')

    await page.getByRole('button', { name: '加载更多' }).click()

    await expect(page.getByText('example.com', { exact: true })).toBeVisible()
    await expect(page.getByText('developer.mozilla.org', { exact: true })).toBeVisible()
    await expect(page.getByText('DNS_ERROR', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '加载更多' })).toHaveCount(0)
  })

  // [测试意图] 验证管理查询失败时保留可恢复错误状态。
  test('管理数据失败后可以重试', async ({ page }) => {
    await installUser(page, 'ADMIN')
    const data = await installAdminData(page, {
      pages: [500, failedJobPage([failedJob()])],
    })
    await page.goto('/admin')

    await expect(page.getByText('管理数据没有加载出来')).toBeVisible()
    await page.getByRole('button', { name: '重新加载' }).click()

    await expect(page.getByText('CAPTURE_TIMEOUT', { exact: true })).toBeVisible()
    await expect.poll(() => data.counts().failedCalls).toBeGreaterThan(1)
  })

  // [测试意图] 验证没有失败任务时展示明确的空状态。
  test('失败任务为空时显示空状态', async ({ page }) => {
    await installUser(page, 'ADMIN')
    await installAdminData(page, {
      pages: [failedJobPage([])],
    })
    await page.goto('/admin')

    await expect(
      page.getByText('当前没有需要处理的失败任务', { exact: true }),
    ).toBeVisible()
    await expect(page.getByRole('button', { name: '重试' })).toHaveCount(0)
  })

  // [测试意图] 验证管理员页在桌面、平板和手机宽度下不发生页面横向溢出。
  test('管理员页适配三档宽度', async ({ page }) => {
    await installUser(page, 'ADMIN')
    await installAdminData(page)
    await mkdir(VISUAL_OUTPUT_DIR, { recursive: true })

    for (const viewport of [
      { width: 1440, height: 900 },
      { width: 768, height: 1024 },
      { width: 390, height: 844 },
    ]) {
      await page.setViewportSize(viewport)
      await page.goto('/admin')

      await expect(
        page.getByRole('heading', { name: '运营管理' }),
      ).toBeVisible()
      await expect(page.getByRole('button', { name: '重试' })).toBeVisible()
      await expect
        .poll(() =>
          page.evaluate(
            () => document.documentElement.scrollWidth <= document.documentElement.clientWidth,
          ),
        )
        .toBe(true)
      await page.screenshot({
        path: path.join(
          VISUAL_OUTPUT_DIR,
          `admin-${viewport.width}.png`,
        ),
        fullPage: true,
      })
    }
  })
})
