import { randomUUID } from 'node:crypto'
import { expect, test } from '@playwright/test'

function createUniqueIdentity() {
  const suffix = `${Date.now().toString(36)}${randomUUID().replaceAll('-', '').slice(0, 8)}`
  const username = `m7-${suffix}`.slice(0, 32)

  return {
    username,
    email: `${username}@example.com`,
    password: `P-${randomUUID()}`,
  }
}

// [测试意图] 防止 E2E 误连 Vite 开发服务器，确保浏览器检查的是生产构建资源。
test('浏览器 E2E 使用生产 Preview 而不是开发服务器', async ({
  baseURL,
  page,
}) => {
  expect(baseURL).toBe('http://127.0.0.1:4173')

  await page.goto('/login')
  const scriptSources = await page
    .locator('script[src]')
    .evaluateAll((scripts) => scripts.map((script) => script.src))
  expect(scriptSources.join(' ')).not.toContain('/@vite/client')
})

test('保存 example.com 后可以核验证据、搜索并重新阅读', async ({
  context,
  page,
}) => {
  const identity = createUniqueIdentity()

  await page.goto('/login')
  await page.getByRole('button', { name: '注册', exact: true }).click()
  await page.locator('input[name="username"]').fill(identity.username)
  await page.locator('input[name="email"]').fill(identity.email)
  await page.locator('input[name="password"]').fill(identity.password)
  await page.getByRole('button', { name: '创建账号', exact: true }).click()

  await expect(page.getByRole('heading', { name: '登录' })).toBeVisible()
  await page.locator('input[name="password"]').fill(identity.password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/links$/)

  await page.getByTestId('open-save-drawer').click()
  await page.locator('#save-url').fill('https://example.com')
  await page.locator('#save-title').fill('Example Domain')
  await page.getByTestId('save-submit').click()

  await expect(page).toHaveURL(/\/links\/\d+\?job=\d+$/)
  const savedLinkUrl = page.url()
  const savedPath = new URL(savedLinkUrl).pathname
  const savedLinkId = savedPath.split('/').at(-1)

  const jobStatus = page.getByTestId('job-status')
  await expect(jobStatus).toBeVisible()
  await expect(jobStatus).toHaveAttribute('data-status', 'SUCCEEDED', {
    timeout: 120_000,
  })
  await expect(page).not.toHaveURL(/[?&]job=/)

  await expect(
    page.getByRole('heading', { name: 'Example Domain', exact: true }),
  ).toBeVisible()
  await expect(page.getByTestId('readable-text')).toContainText(
    'This domain is for use in documentation examples',
  )

  const htmlEvidence = page.getByTestId('evidence-html')
  const pdfEvidence = page.getByTestId('evidence-pdf')
  const screenshotEvidence = page.getByTestId('evidence-screenshot')
  await expect(htmlEvidence.getByRole('button', { name: '下载' })).toBeVisible()
  await expect(pdfEvidence.getByRole('button', { name: '打开' })).toBeVisible()
  await expect(
    screenshotEvidence.getByRole('button', { name: '预览' }),
  ).toBeVisible()

  const screenshot = page
    .getByTestId('screenshot-preview')
    .getByRole('img')
  await expect(screenshot).toBeVisible()
  await expect
    .poll(() =>
      screenshot.evaluate((image) => image.complete && image.naturalWidth > 0),
    )
    .toBe(true)

  const downloadPromise = page.waitForEvent('download')
  await htmlEvidence.getByRole('button', { name: '下载' }).click()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toMatch(/\.html$/i)

  const pdfResponsePromise = page.waitForResponse((response) => {
    const request = response.request()
    return (
      request.method() === 'GET' &&
      /\/api\/v1\/assets\/\d+$/.test(new URL(response.url()).pathname) &&
      response.headers()['content-type']?.includes('application/pdf')
    )
  })
  const popupPromise = context.waitForEvent('page')
  await pdfEvidence.getByRole('button', { name: '打开' }).click()
  const [pdfResponse, pdfPage] = await Promise.all([
    pdfResponsePromise,
    popupPromise,
  ])
  expect(pdfResponse.ok()).toBe(true)
  expect(pdfPage.isClosed()).toBe(false)
  await pdfPage.close()

  await page.goto('/search')
  await page.locator('#search-query').fill('Example Domain')
  await page.getByRole('button', { name: '搜索档案', exact: true }).click()

  const searchResult = page
    .locator('.result-title')
    .filter({ hasText: 'Example Domain' })
  await expect(searchResult).toBeVisible()
  await searchResult.click()

  await expect(page).toHaveURL(new RegExp(`/links/${savedLinkId}$`))
  await expect(page.getByTestId('readable-text')).toContainText(
    'This domain is for use in documentation examples',
  )

  await page.getByRole('button', { name: '返回收藏', exact: true }).click()
  await expect(page).toHaveURL(/\/links$/)
  await expect(
    page.getByRole('link', { name: /打开收藏 Example Domain/ }),
  ).toBeVisible()
})
