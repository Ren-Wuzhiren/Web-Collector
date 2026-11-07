import { defineConfig, devices } from '@playwright/test'

const defaultBaseUrl = 'http://127.0.0.1:4173'
const defaultApiUrl = 'http://127.0.0.1:8080'
const baseURL = process.env.E2E_BASE_URL || defaultBaseUrl
const useExternalServer = Boolean(process.env.E2E_BASE_URL)
const localRedisPassword = process.env.E2E_REDIS_PASSWORD || 'webcollector_dev'

export default defineConfig({
  testDir: './e2e',
  outputDir: './test-results',
  globalSetup: './e2e/global-setup.js',
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  forbidOnly: Boolean(process.env.CI),
  timeout: 180_000,
  expect: {
    timeout: 15_000,
  },
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: 'playwright-report' }],
  ],
  use: {
    ...devices['Desktop Chrome'],
    channel: process.env.E2E_BROWSER_CHANNEL || undefined,
    baseURL,
    viewport: { width: 1440, height: 900 },
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: process.env.E2E_VIDEO === 'off' ? 'off' : 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
    },
  ],
  webServer: useExternalServer
    ? undefined
    : [
        {
          command: 'mvn -f ../pom.xml -pl backend spring-boot:run',
          url: `${defaultApiUrl}/api/v1/health/ready`,
          reuseExistingServer: false,
          timeout: 120_000,
          env: {
            ...process.env,
            CAPTURE_SCHEDULER_ENABLED: 'true',
            REDIS_PASSWORD: localRedisPassword,
            SPRING_DATA_REDIS_PASSWORD: localRedisPassword,
          },
        },
        {
          command:
            'npm run preview -- --host 127.0.0.1 --port 4173',
          url: `${defaultBaseUrl}/login`,
          reuseExistingServer: false,
          timeout: 120_000,
        },
      ],
})
