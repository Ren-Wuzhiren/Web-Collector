import { expect, test } from '@playwright/test'

const APPROVED_LIGHT_THEME_TOKENS = {
  '--ground': '#f5f4ee',
  '--surface': '#fbfaf6',
  '--raised': '#efeee7',
  '--active': '#e8e6dc',
  '--border': '#dcd9cd',
  '--rule': '#e8e6dc',
  '--control': '#c9c5b7',
  '--control-hover': '#a9a597',
  '--text': '#141413',
  '--text-hover': '#3d3d3a',
  '--muted': '#5e5d59',
  '--faint': '#87867f',
  '--accent': '#c96442',
  '--accent-soft': '#f5e6de',
  '--accent-strong': '#a84f31',
  '--info': '#3f6fa6',
  '--info-soft': '#e6eef7',
  '--sage': '#5f7a48',
  '--positive': '#4f6b3a',
  '--positive-soft': '#e7eedd',
  '--negative': '#b3261e',
  '--negative-soft': '#f9e6e3',
  '--negative-border': '#ebc2bc',
  '--warning': '#8a5a12',
  '--warning-soft': '#faf0dc',
  '--selection': '#f0d9cc',
}

const APPROVED_DARK_THEME_TOKENS = {
  '--ground': '#141413',
  '--surface': '#1f1e1d',
  '--raised': '#2a2825',
  '--active': '#34312d',
  '--border': '#302e2a',
  '--rule': '#282623',
  '--control': '#4a463f',
  '--control-hover': '#6a645b',
  '--text': '#faf9f5',
  '--text-hover': '#ffffff',
  '--muted': '#b0aea6',
  '--faint': '#878680',
  '--accent': '#cb7b5e',
  '--accent-soft': '#38251f',
  '--accent-strong': '#e7a086',
  '--info': '#8fb5df',
  '--info-soft': '#1b2a39',
  '--sage': '#9ab782',
  '--positive': '#80c99a',
  '--positive-soft': '#1a2b21',
  '--negative': '#f28b82',
  '--negative-soft': '#351e1c',
  '--negative-border': '#6f3934',
  '--warning': '#e4b866',
  '--warning-soft': '#342a19',
  '--selection': '#402c24',
  '--art-peach': '#e5cab9',
  '--art-coral': '#cb7b5e',
  '--art-blue': '#769bc8',
  '--art-lavender': '#817fb8',
  '--art-mist': '#c0d0ca',
  '--art-sage': '#6f9788',
  '--art-ink': '#141413',
}

async function readThemeTokens(page, names) {
  return page.evaluate((tokenNames) => {
    const styles = getComputedStyle(document.documentElement)
    return Object.fromEntries(
      tokenNames.map((name) => [name, styles.getPropertyValue(name).trim()]),
    )
  }, names)
}

// [测试意图] 首次访问必须进入黑夜主题，锁定从参考图取样的背景、文字和档案色。
test('首次访问默认使用黑夜主题 token', async ({ page }) => {
  await page.goto('/login')

  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await expect(page.locator('html')).toHaveClass(/\bdark\b/)

  const actual = await readThemeTokens(
    page,
    Object.keys(APPROVED_DARK_THEME_TOKENS),
  )
  expect(actual).toEqual(APPROVED_DARK_THEME_TOKENS)
})

// [测试意图] 登录页可切换并持久化浅色主题，同时保留已验收的暖白 token。
test('登录页切换浅色主题并在刷新后保留', async ({ page }) => {
  await page.goto('/login')

  const toggle = page.getByRole('button', { name: '切换到浅色主题' })
  await expect(toggle).toHaveAttribute('aria-pressed', 'true')
  await toggle.click()

  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
  await expect(page.locator('html')).not.toHaveClass(/\bdark\b/)
  await expect(
    page.getByRole('button', { name: '切换到黑夜主题' }),
  ).toHaveAttribute('aria-pressed', 'false')

  const actual = await readThemeTokens(
    page,
    Object.keys(APPROVED_LIGHT_THEME_TOKENS),
  )
  expect(actual).toEqual(APPROVED_LIGHT_THEME_TOKENS)

  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
})

// [测试意图] 认证后使用参考图式顶部导航，桌面不再依赖左侧栏承载主入口。
test('收藏页使用顶部主导航和全局主题开关', async ({ page }) => {
  await page.goto('/login')
  await page.evaluate(() => {
    localStorage.setItem('webcollector-token', 'theme-shell-token')
  })
  await page.route('**/api/v1/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          id: 7,
          username: 'reader',
          email: 'reader@example.com',
          role: 'USER',
          createdAt: '2026-11-07T00:00:00Z',
        },
        message: 'success',
        requestId: 'theme-shell',
      }),
    })
  })
  for (const [path, data] of [
    ['collections', []],
    ['tags', []],
    ['links', []],
  ]) {
    await page.route(`**/api/v1/${path}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data,
          message: 'success',
          requestId: `theme-${path}`,
        }),
      })
    })
  }

  await page.goto('/links')

  await expect(page.locator('.sidebar')).toHaveCount(0)
  await expect(page.getByRole('banner')).toBeVisible()
  await expect(page.getByRole('navigation', { name: '主导航' })).toBeVisible()
  await expect(page.getByRole('button', { name: '切换到浅色主题' })).toBeVisible()

  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: '打开导航' }).click()
  await expect(page.getByRole('dialog', { name: '导航' })).toBeVisible()
  await expect(
    page.getByRole('navigation', { name: '移动端主导航' }),
  ).toBeVisible()
})
