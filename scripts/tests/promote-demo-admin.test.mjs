import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const TEST_DIR = path.dirname(fileURLToPath(import.meta.url))
const PROJECT_ROOT = path.resolve(TEST_DIR, '..', '..')
const SCRIPT_PATH = path.resolve(
  PROJECT_ROOT,
  'scripts',
  'promote-demo-admin.ps1',
)
const COMPOSE_PATH = path.resolve(
  PROJECT_ROOT,
  'deploy',
  'local',
  'docker-compose.yml',
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

function runSql(sql) {
  return run('docker', [
    'compose',
    '-f',
    COMPOSE_PATH,
    'exec',
    '-T',
    '-e',
    `SQL=${sql}`,
    'mysql',
    'sh',
    '-lc',
    'mysql --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE" --batch --skip-column-names --execute="$SQL"',
  ])
}

function sqlString(value) {
  return `'${String(value).replaceAll("'", "''")}'`
}

async function waitForMysql() {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const result = await runSql('SELECT 1')
    if (result.code === 0) return
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error('本地 MySQL 未就绪')
}

test('promotion script is idempotent and does not expose credentials', async (t) => {
  await waitForMysql()
  const username = `script-admin-${randomUUID().replaceAll('-', '').slice(0, 8)}`
  const email = `${username}@example.com`
  const inserted = await runSql(`
    INSERT INTO app_user
      (username, email, password_hash, role, created_at, updated_at)
    VALUES
      (${sqlString(username)}, ${sqlString(email)}, '$2a$10$placeholder',
       'USER', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
  `)
  assert.equal(inserted.code, 0, inserted.stderr)

  t.after(async () => {
    await runSql(`
      DELETE FROM admin_action_log
      WHERE admin_user_id IN (
        SELECT id FROM app_user WHERE username = ${sqlString(username)}
      );
      DELETE FROM app_user WHERE username = ${sqlString(username)}
    `)
  })

  const first = await run(POWERSHELL, [
    '-NoProfile',
    '-File',
    SCRIPT_PATH,
    '-Username',
    username,
  ])
  assert.equal(first.code, 0, first.stderr)

  const role = await runSql(`
    SELECT role FROM app_user WHERE username = ${sqlString(username)}
  `)
  assert.equal(role.code, 0, role.stderr)
  assert.equal(role.stdout.trim(), 'ADMIN')

  const second = await run(POWERSHELL, [
    '-NoProfile',
    '-File',
    SCRIPT_PATH,
    '-Username',
    username,
  ])
  assert.equal(second.code, 0, second.stderr)
  assert.match(second.stdout, /已经是 ADMIN/)
  assert.doesNotMatch(`${first.stdout}\n${first.stderr}`, /MYSQL_PASSWORD=/)
})

test('promotion script fails clearly when account does not exist', async () => {
  await waitForMysql()
  const result = await run(POWERSHELL, [
    '-NoProfile',
    '-File',
    SCRIPT_PATH,
    '-Username',
    'missing-script-admin',
  ])

  assert.notEqual(result.code, 0)
  assert.match(`${result.stdout}\n${result.stderr}`, /不存在/)
})
