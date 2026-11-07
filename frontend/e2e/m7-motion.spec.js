import { expect, test } from '@playwright/test'

const TOKEN_KEY = 'webcollector-token'
const TOKEN = 'm7-motion-token'
const FIXED_TIME = '2026-11-07T04:00:00Z'

function apiResponse(data, message = 'success') {
  return {
    data,
    message,
    requestId: 'm7-motion-e2e',
  }
}

async function installSession(page) {
  await page.route('**/api/v1/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse({
        id: 7,
        username: 'motion-reader',
        email: 'motion@example.com',
        role: 'USER',
        createdAt: FIXED_TIME,
      })),
    })
  })

  await page.goto('/login')
  await page.evaluate(
    ([key, token]) => localStorage.setItem(key, token),
    [TOKEN_KEY, TOKEN],
  )
}

function tagFixture(id, name) {
  return {
    id,
    name,
    linkCount: 0,
    createdAt: FIXED_TIME,
  }
}

async function installTagCatalog(page, initialTags) {
  let tags = [...initialTags]

  await page.route('**/api/v1/tags', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(tags)),
      })
      return
    }

    const request = route.request().postDataJSON()
    const created = tagFixture(100, request.name)
    tags = [...tags, created]
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse(created)),
    })
  })
}

async function watchCounterMotion(page) {
  return page.evaluate(() => new Promise((resolve) => {
    const counter = document.querySelector('.ledger-footer .archive-counter')
    const states = []
    const record = () => {
      const state = counter?.getAttribute('data-motion-state') || null
      if (states.at(-1) !== state) states.push(state)
    }

    record()
    const observer = new MutationObserver(record)
    if (counter) {
      observer.observe(counter, {
        attributes: true,
        attributeFilter: ['data-motion-state'],
      })
    }

    window.setTimeout(() => {
      observer.disconnect()
      resolve(states)
    }, 700)
  }))
}

async function createNextUnusedTag(page) {
  await page.locator('#new-tag-name').fill('Tenth')
  await page.getByRole('button', { name: '创建标签', exact: true }).click()
}

// [测试意图] 防止首次进入标签页时把“— → 9”误当成用户动作而播放数字滚动。
test('标签计数首次加载直接落到真实值且不滚动', async ({ page }) => {
  await installSession(page)
  await installTagCatalog(
    page,
    Array.from({ length: 9 }, (_, index) => tagFixture(index + 1, `Tag ${index + 1}`)),
  )

  await page.goto('/tags')

  const counter = page.locator('.ledger-footer .archive-counter')
  await expect(counter.locator('.visually-hidden')).toHaveText('9')
  await expect(counter).toHaveAttribute('data-motion-state', 'idle')

  const states = await watchCounterMotion(page)
  expect(states).toEqual(['idle'])
})

// [测试意图] 验证 9→10 时单位与十位使用稳定位 key，并只在用户动作后播放一次滚动。
test('创建第十个未使用标签时数字从 9 正向滚到 10', async ({ page }) => {
  await installSession(page)
  await installTagCatalog(
    page,
    Array.from({ length: 9 }, (_, index) => tagFixture(index + 1, `Tag ${index + 1}`)),
  )
  await page.goto('/tags')

  const counter = page.locator('.ledger-footer .archive-counter')
  await expect(counter.locator('.visually-hidden')).toHaveText('9')
  const statesPromise = watchCounterMotion(page)

  await createNextUnusedTag(page)

  await expect(counter.locator('.visually-hidden')).toHaveText('10')
  const states = await statesPromise
  expect(states).toContain('rolling')
  expect(states.at(-1)).toBe('idle')

  const windows = counter.locator('.digit-window')
  await expect(windows).toHaveCount(2)
  await expect(windows.nth(0)).toHaveAttribute('data-digit-key', '-1')
  await expect(windows.nth(1)).toHaveAttribute('data-digit-key', '0')
  await expect(windows.nth(1).locator('.digit-reel')).toHaveAttribute('data-digit', '0')
})

// [测试意图] 验证 reduced-motion 下数字直接替换，不进入滚动状态。
test('reduced-motion 下标签计数直接替换为 10', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await installSession(page)
  await installTagCatalog(
    page,
    Array.from({ length: 9 }, (_, index) => tagFixture(index + 1, `Tag ${index + 1}`)),
  )
  await page.goto('/tags')

  const counter = page.locator('.ledger-footer .archive-counter')
  const statesPromise = watchCounterMotion(page)
  await createNextUnusedTag(page)

  await expect(counter.locator('.visually-hidden')).toHaveText('10')
  const states = await statesPromise
  expect(states).not.toContain('rolling')
  expect(states.at(-1)).toBe('idle')
})

async function installLinkList(page, title = 'Motion Article') {
  const link = {
    id: 1,
    collectionId: 1,
    title,
    originalUrl: 'https://example.com/motion',
    description: '',
    createdAt: FIXED_TIME,
    updatedAt: FIXED_TIME,
  }

  await page.route('**/api/v1/links', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse([link])),
      })
    }
  })
  await page.route('**/api/v1/collections', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse([{
        id: 1,
        name: 'Inbox',
        description: '',
        isInbox: true,
        linkCount: 1,
        createdAt: FIXED_TIME,
      }])),
    })
  })
  await page.route('**/api/v1/tags', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse([])),
    })
  })
  await page.route('**/api/v1/links/1', async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 800))
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse(link)),
    })
  })
  await page.route('**/api/v1/links/1/readable', async (route) => {
    await route.fulfill({
      status: 404,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'NOT_FOUND', message: '正文尚未生成' }),
    })
  })
  await page.route('**/api/v1/links/1/tags', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse([])),
    })
  })
  await page.route('**/api/v1/assets?linkId=1', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse([])),
    })
  })

  return link
}

async function installViewTransitionProbe(page, { supported = true } = {}) {
  await page.addInitScript(({ viewTransitionSupported }) => {
    window.__viewTransitionCalls = []
    if (!viewTransitionSupported) {
      Object.defineProperty(document, 'startViewTransition', {
        configurable: true,
        value: undefined,
      })
      return
    }

    const original = document.startViewTransition?.bind(document)
    if (!original) return
    document.startViewTransition = (callback) => {
      window.__viewTransitionCalls.push({
        route: window.location.pathname,
      })
      return original(async () => {
        const result = await callback()
        window.__viewTransitionCalls.at(-1).state = {
          ...window.history.state,
        }
        return result
      })
    }
  }, { viewTransitionSupported: supported })
}

// [测试意图] 验证列表进入详情时，加载骨架已带有同一标题 transition name 和路由 state 标题。
test('列表标题在异步详情加载期间保持 View Transition 配对', async ({ page }) => {
  await installViewTransitionProbe(page)
  await installSession(page)
  await installLinkList(page, 'Motion Article')
  await page.goto('/links')

  await page.getByRole('link', { name: '打开收藏 Motion Article' }).click()

  const loadingTitle = page.getByTestId('detail-loading-title')
  await expect(loadingTitle).toHaveText('Motion Article')
  await expect(loadingTitle).toHaveCSS('view-transition-name', 'link-title-1')
  await expect.poll(() => page.evaluate(() => window.__viewTransitionCalls.length)).toBe(1)
  await expect.poll(() => page.evaluate(
    () => window.__viewTransitionCalls[0]?.state?.transitionTitle,
  )).toBe('Motion Article')
})

// [测试意图] 验证浏览器不支持 View Transitions 时仍能正常进入详情。
test('不支持 View Transitions 时直接跳转详情', async ({ page }) => {
  await installViewTransitionProbe(page, { supported: false })
  await installSession(page)
  await installLinkList(page, 'Fallback Article')
  await page.goto('/links')

  await page.getByRole('link', { name: '打开收藏 Fallback Article' }).click()

  await expect(page).toHaveURL(/\/links\/1$/)
  expect(await page.evaluate(() => window.__viewTransitionCalls)).toEqual([])
})

// [测试意图] 验证列表标题实际被省略号截断时退化为普通跳转，避免标题快照突然展开。
test('截断标题不播放标题形变过渡', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await installViewTransitionProbe(page)
  await installSession(page)
  await installLinkList(
    page,
    'A very long article title that must be visibly clipped inside the compact mobile archive row',
  )
  await page.goto('/links')
  await page.getByRole('button', { name: '列表视图' }).click()

  const title = page.locator('.link-copy h3')
  await expect.poll(() => title.evaluate(
    (element) => element.scrollWidth > element.clientWidth,
  )).toBe(true)
  await page.locator('.link-identity').click()

  await expect(page).toHaveURL(/\/links\/1$/)
  expect(await page.evaluate(() => window.__viewTransitionCalls)).toEqual([])
})

function detailedLink() {
  return {
    id: 1,
    collectionId: 1,
    title: 'Settling Article',
    originalUrl: 'https://example.com/settling',
    description: 'Motion settlement regression',
    createdAt: FIXED_TIME,
    updatedAt: FIXED_TIME,
  }
}

async function installSettlingDetail(page) {
  const link = detailedLink()
  let jobCalls = 0
  let settled = false

  await installSession(page)
  await page.route('**/api/v1/links/1', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse(link)),
    })
  })
  await page.route('**/api/v1/jobs/41', async (route) => {
    jobCalls += 1
    if (jobCalls >= 2) settled = true
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse({
        id: 41,
        linkId: 1,
        status: settled ? 'SUCCEEDED' : 'PENDING',
        attemptCount: settled ? 1 : 0,
        errorMessage: null,
        assets: [],
      })),
    })
  })
  await page.route('**/api/v1/links/1/readable', async (route) => {
    if (!settled) {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'NOT_FOUND', message: '正文尚未生成' }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse({
        link,
        textContent: 'Readable content after capture settles.',
      })),
    })
  })
  await page.route('**/api/v1/assets?linkId=1', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse(settled
        ? [
            { id: 11, format: 'HTML', mimeType: 'text/html', fileSizeBytes: 100, createdAt: FIXED_TIME },
            { id: 12, format: 'PDF', mimeType: 'application/pdf', fileSizeBytes: 200, createdAt: FIXED_TIME },
          ]
        : [])),
    })
  })
  await page.route('**/api/v1/links/1/tags', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse([])),
    })
  })
  await page.route('**/api/v1/collections', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse([{
        id: 1,
        name: 'Inbox',
        description: '',
        isInbox: true,
        linkCount: 1,
        createdAt: FIXED_TIME,
      }])),
    })
  })
  await page.route('**/api/v1/tags', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse([])),
    })
  })
}

// [测试意图] 验证任务从 PENDING 到 SUCCEEDED 后只淡入正文和已有证据，缺失证据保持静态。
test('任务终态让正文和已有证据淡入且不改变正文盒模型', async ({ page }) => {
  await installSettlingDetail(page)
  await page.goto('/links/1?job=41')

  const readable = page.getByTestId('readable-text')
  await expect(readable).toContainText('Readable content after capture settles.', {
    timeout: 10_000,
  })
  await expect(readable).toHaveClass(/is-settling/)
  await expect(page.locator('.evidence-list')).toHaveClass(/is-settling/)

  const motion = await readable.evaluate((element) => ({
    animationName: getComputedStyle(element).animationName,
    transform: getComputedStyle(element).transform,
  }))
  expect(motion.animationName).toContain('settle-fade')
  expect(motion.transform).toBe('none')

  const missingEvidence = page.getByTestId('evidence-screenshot')
  await expect(missingEvidence).toHaveClass(/is-missing/)
  expect(await missingEvidence.evaluate(
    (element) => getComputedStyle(element).animationName,
  )).toBe('none')
})

// [测试意图] 验证直接打开已完成收藏不会误播终态淡入。
test('首次打开已完成收藏不播放终态淡入', async ({ page }) => {
  const link = detailedLink()
  await installSession(page)
  await page.route('**/api/v1/links/1', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse(link)),
    })
  })
  await page.route('**/api/v1/links/1/readable', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse({
        link,
        textContent: 'Already completed readable content.',
      })),
    })
  })
  await page.route('**/api/v1/assets?linkId=1', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse([])),
    })
  })
  await page.route('**/api/v1/links/1/tags', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse([])),
    })
  })
  await page.route('**/api/v1/collections', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse([{
        id: 1,
        name: 'Inbox',
        description: '',
        isInbox: true,
        linkCount: 1,
        createdAt: FIXED_TIME,
      }])),
    })
  })
  await page.route('**/api/v1/tags', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse([])),
    })
  })

  await page.goto('/links/1')

  const readable = page.getByTestId('readable-text')
  await expect(readable).toContainText('Already completed readable content.')
  await expect(readable).not.toHaveClass(/is-settling/)
  await expect(page.locator('.evidence-list')).not.toHaveClass(/is-settling/)
})

// [测试意图] 验证 reduced-motion 下终态直接显示，正文无动画且横向盒模型稳定。
test('reduced-motion 下终态正文直接显示且布局不位移', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await installSettlingDetail(page)
  await page.goto('/links/1?job=41')

  const readable = page.getByTestId('readable-text')
  await expect(readable).toContainText('Readable content after capture settles.', {
    timeout: 10_000,
  })

  const motion = await readable.evaluate((element) => {
    const style = getComputedStyle(element)
    return {
      animationName: style.animationName,
      transform: style.transform,
      left: element.getBoundingClientRect().left,
      width: element.getBoundingClientRect().width,
    }
  })
  expect(motion.animationName).toBe('none')
  expect(motion.transform).toBe('none')
  expect(motion.left).toBeGreaterThanOrEqual(0)
  expect(motion.width).toBeGreaterThan(0)
})
