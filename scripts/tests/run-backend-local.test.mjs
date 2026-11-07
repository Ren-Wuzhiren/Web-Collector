import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import net from 'node:net'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const TEST_DIR = path.dirname(fileURLToPath(import.meta.url))
const PROJECT_ROOT = path.resolve(TEST_DIR, '..', '..')
const SCRIPT_PATH = path.resolve(
  PROJECT_ROOT,
  'scripts',
  'run-backend-local.ps1',
)
const POWERSHELL = process.env.POWERSHELL_EXE || 'pwsh'

function run(command, args, extraEnv = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd: PROJECT_ROOT,
      env: {
        ...process.env,
        ...extraEnv,
      },
      windowsHide: true,
    })
    let stdout = ''
    let stderr = ''
    child.stdout.on('data', (chunk) => {
      stdout += chunk.toString()
    })
    child.stderr.on('data', (chunk) => {
      stderr += chunk.toString()
    })
    child.once('error', reject)
    child.once('exit', (code) => {
      resolve({ code, stdout, stderr })
    })
  })
}

async function readRedisPassword() {
  const result = await run('docker', [
    'inspect',
    'webcollector-redis',
    '--format',
    '{{json .Config.Env}}',
  ])
  assert.equal(result.code, 0, result.stderr)
  const entry = JSON.parse(result.stdout).find((value) =>
    value.startsWith('REDIS_PASSWORD='),
  )
  assert.ok(entry, 'Redis container must expose REDIS_PASSWORD')
  return entry.slice('REDIS_PASSWORD='.length)
}

function getFreePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address()
      server.close((error) => {
        if (error) {
          reject(error)
          return
        }
        resolve(port)
      })
    })
  })
}

test('check-only synchronizes the container Redis password without exposing it', async () => {
  const wrongPassword = 'definitely-the-wrong-redis-password'
  const containerPassword = await readRedisPassword()
  const backendPort = await getFreePort()

  const result = await run(
    POWERSHELL,
    [
      '-NoProfile',
      '-File',
      SCRIPT_PATH,
      '-CheckOnly',
      '-BackendPort',
      String(backendPort),
    ],
    {
      SPRING_DATA_REDIS_PASSWORD: wrongPassword,
    },
  )

  assert.equal(result.code, 0, result.stderr)
  assert.match(result.stdout, /MySQL: healthy/)
  assert.match(result.stdout, /Redis: healthy/)
  assert.match(
    result.stdout,
    /Redis credential: synchronized from webcollector-redis/,
  )
  assert.match(result.stdout, /Check-only completed/)

  const output = `${result.stdout}\n${result.stderr}`
  assert.equal(output.includes(containerPassword), false)
  assert.equal(output.includes(wrongPassword), false)
})
