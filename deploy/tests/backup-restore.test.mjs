import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const TEST_DIR = path.dirname(fileURLToPath(import.meta.url))
const PROJECT_ROOT = path.resolve(TEST_DIR, '..', '..')
const BACKUP_SCRIPT = path.join(PROJECT_ROOT, 'deploy', 'backup.ps1')
const RESTORE_SCRIPT = path.join(PROJECT_ROOT, 'deploy', 'restore.ps1')
const POWERSHELL = process.env.POWERSHELL_EXE || 'pwsh'
const PROJECT_NAME = 'webcollector-m8-acceptance'

const FAKE_DOCKER_SOURCE = String.raw`
import fs from 'node:fs/promises'
import path from 'node:path'

const args = process.argv.slice(2)
const logPath = process.env.FAKE_DOCKER_LOG
const backendRoot = process.env.FAKE_BACKEND_DATA

if (logPath) {
  await fs.appendFile(logPath, JSON.stringify({ args }) + '\n')
}

function composeSubcommand() {
  const composeIndex = args.indexOf('compose')
  if (composeIndex < 0) return { name: '', rest: [] }

  let index = composeIndex + 1
  while (index < args.length) {
    const value = args[index]
    if (value === '-p' || value === '--env-file' || value === '-f') {
      index += 2
      continue
    }
    if (value.startsWith('-')) {
      index += 1
      continue
    }
    return { name: value, rest: args.slice(index + 1) }
  }
  return { name: '', rest: [] }
}

function containerPath(value) {
  if (!value.startsWith('backend:/data/')) return null
  return path.join(backendRoot, value.slice('backend:/data/'.length))
}

async function copy(source, destination) {
  if (source.startsWith('mysql:/tmp/')) {
    await fs.mkdir(path.dirname(destination), { recursive: true })
    const bytes = Buffer.from(process.env.FAKE_SQL_BASE64 || '', 'base64')
    await fs.writeFile(destination, bytes)
    return
  }

  const sourcePath = containerPath(source)
  if (sourcePath) {
    await fs.mkdir(path.dirname(destination), { recursive: true })
    await fs.cp(sourcePath, destination, { recursive: true })
    return
  }

  const destinationPath = containerPath(destination)
  if (destinationPath) {
    const parent = path.dirname(destinationPath)
    try {
      await fs.access(parent)
    } catch {
      throw new Error(
        'Could not find the file ' + path.posix.dirname(destination) + ' in container',
      )
    }
    await fs.mkdir(path.dirname(destinationPath), { recursive: true })
    await fs.cp(source, destinationPath, { recursive: true })
    return
  }
  if (destination.startsWith('mysql:/tmp/')) {
    return
  }

  throw new Error('unsupported docker cp: ' + source + ' -> ' + destination)
}

function applyRestoreScript(script) {
  const assetMatch = script.match(
    /mv\s+['"]?(\/data\/\.m8-restore-[^/\s']+\/assets\.next)['"]?\s+\/data\/assets/,
  )
  const captureMatch = script.match(
    /mv\s+['"]?(\/data\/\.m8-restore-[^/\s']+\/capture-work\.next)['"]?\s+\/data\/capture-work/,
  )
  if (!assetMatch || !captureMatch) {
    throw new Error('restore script did not replace both directories')
  }

  const assetSource = containerPath(
    'backend:' + assetMatch[1].replace(/\.next$/, ''),
  )
  const captureSource = containerPath(
    'backend:' + captureMatch[1].replace(/\.next$/, ''),
  )
  fs.rm(path.join(backendRoot, 'assets'), { recursive: true, force: true })
    .then(() => fs.cp(assetSource, path.join(backendRoot, 'assets'), { recursive: true }))
    .then(() => fs.rm(path.join(backendRoot, 'capture-work'), { recursive: true, force: true }))
    .then(() => fs.cp(captureSource, path.join(backendRoot, 'capture-work'), { recursive: true }))
    .catch((error) => {
      console.error(error)
      process.exitCode = 1
    })
}

const subcommand = composeSubcommand()
if (subcommand.name === 'ps') {
  process.stdout.write('mysql\nbackend\n')
} else if (subcommand.name === 'cp') {
  if (process.env.FAKE_DOCKER_FAIL_CP === '1') {
    console.error('simulated docker cp failure')
    process.exitCode = 1
  } else {
    await copy(subcommand.rest.at(-2), subcommand.rest.at(-1))
  }
} else if (subcommand.name === 'run') {
  const shellIndex = subcommand.rest.indexOf('-lc')
  if (shellIndex < 0) {
    throw new Error('docker compose run did not include sh -lc')
  }
  applyRestoreScript(subcommand.rest[shellIndex + 1])
} else if (subcommand.name === 'exec') {
  const shellIndex = subcommand.rest.indexOf('-lc')
  if (shellIndex >= 0) {
    const match = subcommand.rest[shellIndex + 1].match(
      /mkdir\s+-p\s+['"]?(\/data\/[^'";\s]+)['"]?/,
    )
    if (match) {
      await fs.mkdir(containerPath('backend:' + match[1]), { recursive: true })
    }
  }
}
`

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex')
}

function run(command, args, env) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd: PROJECT_ROOT,
      env: { ...process.env, ...env },
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
    child.once('exit', (code) => resolve({ code, stdout, stderr }))
  })
}

async function createSandbox() {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'webcollector-m8-backup-'))
  const fakeBin = path.join(root, 'fake-bin')
  const fakeDocker = path.join(fakeBin, 'fake-docker.mjs')
  const backendData = path.join(root, 'backend-data')
  const composeFile = path.join(root, 'docker-compose.yml')
  const envFile = path.join(root, '.env')
  const logFile = path.join(root, 'docker.log')

  await fs.mkdir(fakeBin, { recursive: true })
  await fs.mkdir(path.join(backendData, 'assets'), { recursive: true })
  await fs.mkdir(path.join(backendData, 'capture-work'), { recursive: true })
  await fs.writeFile(fakeDocker, FAKE_DOCKER_SOURCE)
  await fs.writeFile(
    path.join(fakeBin, 'docker.cmd'),
    `@echo off\r\n"${process.execPath}" "${fakeDocker}" %*\r\n`,
  )
  await fs.writeFile(composeFile, 'services: {}\n')
  await fs.writeFile(envFile, 'MYSQL_DATABASE=webcollector\n')

  return {
    root,
    fakeBin,
    backendData,
    composeFile,
    envFile,
    logFile,
    env: {
      PATH: `${fakeBin}${path.delimiter}${process.env.PATH || ''}`,
      FAKE_DOCKER_LOG: logFile,
      FAKE_BACKEND_DATA: backendData,
    },
  }
}

async function readDockerLog(sandbox) {
  const content = await fs.readFile(sandbox.logFile, 'utf8').catch(() => '')
  return content
    .trim()
    .split(/\r?\n/)
    .filter(Boolean)
    .map((line) => JSON.parse(line))
}

async function writeBackupFixture(sandbox, backupDirectory) {
  const sql = Buffer.from(
    '\uFEFF-- backup marker\r\nCREATE TABLE marker (id BIGINT);\r\n',
    'utf8',
  )
  const assets = new Map([
    ['asset-a.txt', Buffer.from('asset-a\n', 'utf8')],
    ['nested/asset-b.bin', Buffer.from([0, 1, 2, 255])],
  ])
  const capture = new Map([
    ['capture-marker.txt', Buffer.from('capture-marker\n', 'utf8')],
  ])

  await fs.mkdir(path.join(backupDirectory, 'assets', 'nested'), {
    recursive: true,
  })
  await fs.mkdir(path.join(backupDirectory, 'capture-work'), {
    recursive: true,
  })
  await fs.writeFile(path.join(backupDirectory, 'mysql.sql'), sql)
  for (const [relativePath, bytes] of assets) {
    await fs.writeFile(path.join(backupDirectory, 'assets', relativePath), bytes)
  }
  for (const [relativePath, bytes] of capture) {
    await fs.writeFile(
      path.join(backupDirectory, 'capture-work', relativePath),
      bytes,
    )
  }

  const files = [
    { path: 'mysql.sql', bytes: sql },
    ...Array.from(assets, ([relativePath, bytes]) => ({
      path: `assets/${relativePath}`,
      bytes,
    })),
    ...Array.from(capture, ([relativePath, bytes]) => ({
      path: `capture-work/${relativePath}`,
      bytes,
    })),
  ]
    .sort((left, right) => left.path.localeCompare(right.path))
    .map(({ path: relativePath, bytes }) => ({
      path: relativePath,
      sizeBytes: bytes.length,
      sha256: sha256(bytes),
    }))

  await fs.writeFile(
    path.join(backupDirectory, 'manifest.json'),
    JSON.stringify(
      {
        schemaVersion: 1,
        projectName: PROJECT_NAME,
        createdAt: '2026-11-08T00:00:00.0000000Z',
        files,
      },
      null,
      2,
    ) + '\n',
  )
}

test('backup writes binary-safe SQL, both data directories, and a manifest', async () => {
  const sandbox = await createSandbox()
  const outputRoot = path.join(sandbox.root, 'backups')
  const sql = Buffer.from(
    "\uFEFF-- binary-safe\r\nSELECT '\u4e2d\u6587';\r\n",
    'utf8',
  )
  sandbox.env.FAKE_SQL_BASE64 = sql.toString('base64')
  await fs.writeFile(
    path.join(sandbox.backendData, 'assets', 'asset-a.txt'),
    'asset-a\n',
  )
  await fs.mkdir(path.join(sandbox.backendData, 'assets', 'nested'))
  await fs.writeFile(
    path.join(sandbox.backendData, 'assets', 'nested', 'asset-b.bin'),
    Buffer.from([0, 1, 2, 255]),
  )
  await fs.writeFile(
    path.join(sandbox.backendData, 'capture-work', 'capture-marker.txt'),
    'capture-marker\n',
  )

  const result = await run(
    POWERSHELL,
    [
      '-NoProfile',
      '-File',
      BACKUP_SCRIPT,
      '-ComposeFile',
      sandbox.composeFile,
      '-EnvFile',
      sandbox.envFile,
      '-ProjectName',
      PROJECT_NAME,
      '-OutputRoot',
      outputRoot,
    ],
    sandbox.env,
  )
  assert.equal(result.code, 0, result.stderr)

  const [backupName] = await fs.readdir(outputRoot)
  const backupDirectory = path.join(outputRoot, backupName)
  assert.deepEqual(
    await fs.readFile(path.join(backupDirectory, 'mysql.sql')),
    sql,
  )
  assert.equal(
    await fs.readFile(
      path.join(backupDirectory, 'assets', 'nested', 'asset-b.bin'),
      'utf8',
    ),
    Buffer.from([0, 1, 2, 255]).toString('utf8'),
  )
  assert.equal(
    await fs.readFile(
      path.join(backupDirectory, 'capture-work', 'capture-marker.txt'),
      'utf8',
    ),
    'capture-marker\n',
  )

  const manifest = JSON.parse(
    await fs.readFile(path.join(backupDirectory, 'manifest.json'), 'utf8'),
  )
  assert.equal(manifest.schemaVersion, 1)
  assert.equal(manifest.projectName, PROJECT_NAME)
  assert.deepEqual(
    manifest.files.map((file) => file.path),
    [
      'assets/asset-a.txt',
      'assets/nested/asset-b.bin',
      'capture-work/capture-marker.txt',
      'mysql.sql',
    ],
  )
  assert.equal(
    manifest.files.find((file) => file.path === 'mysql.sql').sha256,
    sha256(sql),
  )

  const commands = await readDockerLog(sandbox)
  assert.ok(commands.length > 0)
  for (const command of commands) {
    assert.deepEqual(command.args.slice(0, 7), [
      'compose',
      '-p',
      PROJECT_NAME,
      '--env-file',
      sandbox.envFile,
      '-f',
      sandbox.composeFile,
    ])
  }
})

test('backup preserves an existing output root and creates a unique child', async () => {
  const sandbox = await createSandbox()
  const outputRoot = path.join(sandbox.root, 'backups')
  const sql = Buffer.from('-- output root reuse\n', 'utf8')
  sandbox.env.FAKE_SQL_BASE64 = sql.toString('base64')
  await fs.mkdir(outputRoot, { recursive: true })
  await fs.writeFile(
    path.join(outputRoot, 'keep.txt'),
    'keep\n',
  )

  const result = await run(
    POWERSHELL,
    [
      '-NoProfile',
      '-File',
      BACKUP_SCRIPT,
      '-ComposeFile',
      sandbox.composeFile,
      '-EnvFile',
      sandbox.envFile,
      '-ProjectName',
      PROJECT_NAME,
      '-OutputRoot',
      outputRoot,
    ],
    sandbox.env,
  )

  assert.equal(result.code, 0, result.stderr)
  assert.equal(
    await fs.readFile(path.join(outputRoot, 'keep.txt'), 'utf8'),
    'keep\n',
  )
  const entries = await fs.readdir(outputRoot, { withFileTypes: true })
  const backupDirectories = entries.filter((entry) => entry.isDirectory())
  assert.equal(backupDirectories.length, 1)
  assert.ok(
    await fs.stat(
      path.join(outputRoot, backupDirectories[0].name, 'manifest.json'),
    ),
  )
})

test('restore rejects an incomplete backup before changing the stack', async () => {
  const sandbox = await createSandbox()
  const backupDirectory = path.join(sandbox.root, 'backup')
  await writeBackupFixture(sandbox, backupDirectory)
  await fs.rm(path.join(backupDirectory, 'capture-work'), {
    recursive: true,
    force: true,
  })

  const result = await run(
    POWERSHELL,
    [
      '-NoProfile',
      '-File',
      RESTORE_SCRIPT,
      '-ComposeFile',
      sandbox.composeFile,
      '-EnvFile',
      sandbox.envFile,
      '-ProjectName',
      PROJECT_NAME,
      '-BackupDirectory',
      backupDirectory,
    ],
    sandbox.env,
  )

  assert.notEqual(result.code, 0)
  assert.match(`${result.stdout}\n${result.stderr}`, /capture-work/)
  assert.deepEqual(await readDockerLog(sandbox), [])
})

test('restore rejects a manifest whose file hash no longer matches', async () => {
  const sandbox = await createSandbox()
  const backupDirectory = path.join(sandbox.root, 'backup')
  await writeBackupFixture(sandbox, backupDirectory)
  const tamperedPath = path.join(backupDirectory, 'assets', 'asset-a.txt')
  const tampered = await fs.readFile(tamperedPath)
  tampered[0] ^= 0xff
  await fs.writeFile(tamperedPath, tampered)

  const result = await run(
    POWERSHELL,
    [
      '-NoProfile',
      '-File',
      RESTORE_SCRIPT,
      '-ComposeFile',
      sandbox.composeFile,
      '-EnvFile',
      sandbox.envFile,
      '-ProjectName',
      PROJECT_NAME,
      '-BackupDirectory',
      backupDirectory,
    ],
    sandbox.env,
  )

  assert.notEqual(result.code, 0)
  assert.match(`${result.stdout}\n${result.stderr}`, /SHA-256|hash/i)
  assert.deepEqual(await readDockerLog(sandbox), [])
})

test('restore rejects a manifest created for another Compose project', async () => {
  const sandbox = await createSandbox()
  const backupDirectory = path.join(sandbox.root, 'backup')
  await writeBackupFixture(sandbox, backupDirectory)
  const manifestPath = path.join(backupDirectory, 'manifest.json')
  const manifest = JSON.parse(await fs.readFile(manifestPath, 'utf8'))
  manifest.projectName = 'another-project'
  await fs.writeFile(manifestPath, JSON.stringify(manifest, null, 2) + '\n')

  const result = await run(
    POWERSHELL,
    [
      '-NoProfile',
      '-File',
      RESTORE_SCRIPT,
      '-ComposeFile',
      sandbox.composeFile,
      '-EnvFile',
      sandbox.envFile,
      '-ProjectName',
      PROJECT_NAME,
      '-BackupDirectory',
      backupDirectory,
    ],
    sandbox.env,
  )

  assert.notEqual(result.code, 0)
  assert.match(`${result.stdout}\n${result.stderr}`, /another-project|project/i)
  assert.deepEqual(await readDockerLog(sandbox), [])
})

test('restore replaces both directories only after stopping backend', async () => {
  const sandbox = await createSandbox()
  const backupDirectory = path.join(sandbox.root, 'backup')
  await writeBackupFixture(sandbox, backupDirectory)
  await fs.writeFile(
    path.join(sandbox.backendData, 'assets', 'stale.txt'),
    'stale',
  )
  await fs.writeFile(
    path.join(sandbox.backendData, 'capture-work', 'stale.txt'),
    'stale',
  )

  const result = await run(
    POWERSHELL,
    [
      '-NoProfile',
      '-File',
      RESTORE_SCRIPT,
      '-ComposeFile',
      sandbox.composeFile,
      '-EnvFile',
      sandbox.envFile,
      '-ProjectName',
      PROJECT_NAME,
      '-BackupDirectory',
      backupDirectory,
    ],
    sandbox.env,
  )
  assert.equal(result.code, 0, result.stderr)

  await assert.rejects(
    fs.access(path.join(sandbox.backendData, 'assets', 'stale.txt')),
  )
  await assert.rejects(
    fs.access(path.join(sandbox.backendData, 'capture-work', 'stale.txt')),
  )
  assert.equal(
    await fs.readFile(
      path.join(sandbox.backendData, 'assets', 'asset-a.txt'),
      'utf8',
    ),
    'asset-a\n',
  )
  assert.equal(
    await fs.readFile(
      path.join(sandbox.backendData, 'capture-work', 'capture-marker.txt'),
      'utf8',
    ),
    'capture-marker\n',
  )

  const commands = await readDockerLog(sandbox)
  const names = commands.map((command) => command.args[7])
  assert.ok(names.indexOf('stop') < names.indexOf('run'))
  assert.ok(names.indexOf('stop') < names.indexOf('start'))
  assert.ok(names.indexOf('cp') < names.indexOf('stop'))
})
