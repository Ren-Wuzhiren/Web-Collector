import { expect, test } from '@playwright/test'

const APPROVED_SECOND_VERSION_TOKENS = {
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

// [测试意图] 锁定用户确认的第二版奶油纸感 + 陶土 UI，防止通用设计规则再次覆盖视觉方向。
test('登录页保持第二版视觉 token', async ({ page }) => {
  await page.goto('/login')

  const actual = await page.evaluate((names) => {
    const styles = getComputedStyle(document.documentElement)
    return Object.fromEntries(
      names.map((name) => [name, styles.getPropertyValue(name).trim()]),
    )
  }, Object.keys(APPROVED_SECOND_VERSION_TOKENS))

  expect(actual).toEqual(APPROVED_SECOND_VERSION_TOKENS)
})
