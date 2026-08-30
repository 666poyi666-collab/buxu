import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createHash, generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto'
import { once } from 'node:events'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { createServer as createHttpServer } from 'node:http'
import { createServer as createNetServer } from 'node:net'
import { tmpdir } from 'node:os'
import { dirname, resolve } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import Ajv2020 from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'

const TEST_SYNC_KEY = 'watch-contract-admin-key-000000000000000000000000'
const STATIC_MCP_KEY = 'watch-contract-static-key-must-never-authenticate-000000'
const WRONG_TOKEN = 'watch-contract-wrong-token-00000000000000000000000'
const TEST_RS_CLIENT_ID = 'watch-contract-resource-server'
const TEST_RS_CLIENT_SECRET = 'watch-contract-resource-secret-000000000000'
const TEST_AUTHORITY_AUDIENCE = 'https://personal-mcp-authority.contract.test/authority/watch'
const AUTHORITY_PUBLIC_PATH = '/_internal/v1/authority-observation'
const AUTHORITY_BINDING_PATH = '/__service-binding/watch-observation'
const AUTHORITY_MEDIA_TYPE = 'application/vnd.poyi.authority-observation.v1+json'
const TEST_BUILD_COMMIT = '0123456789abcdef0123456789abcdef01234567'
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const WRANGLER_CLI = resolve(ROOT, 'node_modules', 'wrangler', 'bin', 'wrangler.js')
const SOURCE_PATH = resolve(ROOT, 'src', 'index.ts')
const SCHEMA_PATH = resolve(ROOT, 'schema.sql')
const FIXTURE = JSON.parse(await readFile(resolve(ROOT, 'contracts', 'watch-sync-v1.fixture.json'), 'utf8'))
const CONTRACT_SCHEMA = JSON.parse(await readFile(resolve(ROOT, 'contracts', 'watch-sync-v1.schema.json'), 'utf8'))
const AUTHORITY_SCHEMA = JSON.parse(await readFile(
  resolve(ROOT, 'contracts', 'watch-authority-observation-v1.schema.json'), 'utf8',
))
const contractAjv = new Ajv2020({ allErrors: true, strict: false })
addFormats(contractAjv)
const validateContract = contractAjv.compile(CONTRACT_SCHEMA)
const validateAuthorityObservation = contractAjv.compile(AUTHORITY_SCHEMA)

function captureProcess(command, args, options) {
  const output = []
  const stdout = []
  const child = spawn(command, args, {
    ...options,
    shell: process.platform === 'win32' && command.toLowerCase().endsWith('.cmd'),
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  })
  child.stdout.on('data', (chunk) => {
    const text = chunk.toString()
    stdout.push(text)
    output.push(text)
  })
  child.stderr.on('data', (chunk) => output.push(chunk.toString()))
  return { child, output, stdout }
}

function wranglerInvocation(args) {
  if (process.env.WRANGLER_BIN) return { command: process.env.WRANGLER_BIN, args }
  return { command: process.execPath, args: [WRANGLER_CLI, ...args] }
}

async function runWrangler(args, cwd) {
  const invocation = wranglerInvocation(args)
  const process = captureProcess(invocation.command, invocation.args, { cwd })
  const [code] = await once(process.child, 'exit')
  if (code !== 0) {
    throw new Error(`Wrangler command failed (${code}):\n${process.output.join('').slice(-12_000)}`)
  }
  return { output: process.output.join(''), stdout: process.stdout.join('') }
}

async function freePort() {
  const server = createNetServer()
  await new Promise((resolvePromise, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolvePromise)
  })
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  await new Promise((resolvePromise, reject) => server.close((error) => error ? reject(error) : resolvePromise()))
  return address.port
}

function base64UrlJson(value) {
  return Buffer.from(JSON.stringify(value)).toString('base64url')
}

async function startFakeOAuth() {
  const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
  const publicJwk = publicKey.export({ format: 'jwk' })
  Object.assign(publicJwk, { kid: 'watch-contract-key', alg: 'RS256', use: 'sig' })
  const revoked = new Set()
  let available = true
  let jwksAvailable = true
  let introspectionAvailable = true
  let advertisedScopes = [
    'journal:read', 'journal:write', 'focuslink:read',
    'watch:read', 'watch:write', 'watch:control',
  ]
  const port = await freePort()
  const issuer = `http://127.0.0.1:${port}`
  const audience = 'https://watch.contract.test/mcp'

  function token(overrides = {}) {
    const now = Math.floor(Date.now() / 1000)
    const header = base64UrlJson({ alg: 'RS256', kid: publicJwk.kid, typ: 'at+jwt' })
    const claims = {
      iss: issuer,
      aud: audience,
      resource: audience,
      sub: 'poyi-owner',
      iat: now,
      exp: now + 300,
      jti: randomUUID(),
      scope: 'watch:read',
      client_id: 'watch-contract-client',
      token_use: 'access_token',
      ...overrides,
    }
    const payload = base64UrlJson(claims)
    const signature = sign('RSA-SHA256', Buffer.from(`${header}.${payload}`), privateKey).toString('base64url')
    return { value: `${header}.${payload}.${signature}`, claims }
  }

  const server = createHttpServer(async (request, response) => {
    if (!available) {
      response.writeHead(503, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
      response.end(JSON.stringify({ error: 'authorization_server_unavailable' }))
      return
    }
    if (request.method === 'GET' && request.url === '/.well-known/oauth-authorization-server') {
      response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
      response.end(JSON.stringify({
        issuer,
        jwks_uri: `${issuer}/jwks.json`,
        authorization_endpoint: `${issuer}/authorize`,
        token_endpoint: `${issuer}/token`,
        introspection_endpoint: `${issuer}/introspect`,
        code_challenge_methods_supported: ['S256'],
        introspection_endpoint_auth_methods_supported: ['client_secret_basic'],
        scopes_supported: advertisedScopes,
      }))
      return
    }
    if (request.method === 'GET' && request.url === '/jwks.json') {
      if (!jwksAvailable) {
        response.writeHead(503, { 'Content-Type': 'application/json' })
        response.end(JSON.stringify({ error: 'jwks_unavailable' }))
        return
      }
      response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'public, max-age=60' })
      response.end(JSON.stringify({ keys: [publicJwk] }))
      return
    }
    if (request.method === 'POST' && request.url === '/introspect') {
      if (!introspectionAvailable) {
        response.writeHead(503, { 'Content-Type': 'application/json' })
        response.end(JSON.stringify({ error: 'introspection_unavailable' }))
        return
      }
      const expectedBasic = `Basic ${Buffer.from(`${TEST_RS_CLIENT_ID}:${TEST_RS_CLIENT_SECRET}`, 'utf8').toString('base64')}`
      if (request.headers.authorization !== expectedBasic) {
        response.writeHead(401, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
        response.end(JSON.stringify({ error: 'invalid_client' }))
        return
      }
      const chunks = []
      for await (const chunk of request) chunks.push(chunk)
      const form = new URLSearchParams(Buffer.concat(chunks).toString('utf8'))
      if (form.get('token_type_hint') !== 'access_token') {
        response.writeHead(400, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
        response.end(JSON.stringify({ error: 'invalid_request' }))
        return
      }
      const supplied = form.get('token') ?? ''
      let claims = null
      try {
        claims = JSON.parse(Buffer.from(supplied.split('.')[1] ?? '', 'base64url').toString('utf8'))
      } catch {
        // Malformed tokens are inactive.
      }
      const active = Boolean(claims?.jti) && !revoked.has(claims.jti)
      response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
      response.end(JSON.stringify(active ? {
        active: true,
        token_type: 'Bearer',
        iss: claims.iss,
        sub: claims.sub,
        aud: claims.aud,
        resource: claims.resource,
        scope: claims.scope,
        client_id: claims.client_id,
        iat: claims.iat,
        exp: claims.exp,
        jti: claims.jti,
      } : { active: false }))
      return
    }
    response.writeHead(404)
    response.end()
  })
  await new Promise((resolvePromise, reject) => {
    server.once('error', reject)
    server.listen(port, '127.0.0.1', resolvePromise)
  })
  return {
    issuer,
    audience,
    jwksUrl: `${issuer}/jwks.json`,
    introspectionUrl: `${issuer}/introspect`,
    asMetadataUrl: `${issuer}/.well-known/oauth-authorization-server`,
    token,
    revoke(jti) { revoked.add(jti) },
    setAvailable(value) { available = value },
    setJwksAvailable(value) { jwksAvailable = value },
    setIntrospectionAvailable(value) { introspectionAvailable = value },
    setScopes(value) { advertisedScopes = [...value] },
    async dispose() { await new Promise((resolvePromise) => server.close(resolvePromise)) },
  }
}

async function stopProcess(child) {
  if (child.exitCode !== null) return
  if (process.platform === 'win32') {
    const killer = spawn('taskkill.exe', ['/pid', String(child.pid), '/t', '/f'], {
      stdio: 'ignore',
      windowsHide: true,
    })
    await once(killer, 'exit')
    // taskkill can return before workerd has released its inherited local D1 handles.
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250))
    return
  }
  child.kill('SIGTERM')
  await Promise.race([
    once(child, 'exit'),
    new Promise((resolvePromise) => setTimeout(resolvePromise, 5_000)),
  ])
  if (child.exitCode === null) child.kill('SIGKILL')
}

async function waitForHttp(url, child, output) {
  const deadline = Date.now() + 40_000
  let lastError
  while (Date.now() < deadline) {
    if (child.exitCode !== null) {
      throw new Error(`Local Worker exited before serving HTTP:\n${output.join('').slice(-12_000)}`)
    }
    try {
      const response = await fetch(url)
      if (response.status > 0) return response
    } catch (error) {
      lastError = error
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 200))
  }
  throw new Error(`Timed out waiting for local Worker: ${lastError instanceof Error ? lastError.message : String(lastError)}\n${output.join('').slice(-12_000)}`)
}

async function startIsolatedWorker({
  applySchema = true,
  oauth = null,
  rsClientSecret = TEST_RS_CLIENT_SECRET,
  syncKey = TEST_SYNC_KEY,
  authorityCapability,
  authorityAudience = TEST_AUTHORITY_AUDIENCE,
  revisionDomainId = 'v3d.watch-contract-owner-v1',
  setupSql = null,
  allowLegacy = true,
} = {}) {
  const sandbox = await mkdtemp(resolve(tmpdir(), 'watch-cloud-contract-'))
  const stateDirectory = resolve(sandbox, 'state')
  const configPath = resolve(sandbox, 'wrangler.jsonc')
  const callerConfigPath = resolve(sandbox, 'caller.wrangler.jsonc')
  const callerSourcePath = resolve(sandbox, 'caller.mjs')
  const targetName = 'watch-cloud-contract-tests'
  const effectiveAuthorityCapability = authorityCapability === null
    ? null
    : authorityCapability ?? `wao_${randomBytes(32).toString('base64url')}`
  const config = {
    name: targetName,
    main: SOURCE_PATH,
    compatibility_date: '2025-03-10',
    compatibility_flags: ['nodejs_compat'],
    migrations: [{ tag: 'v1', new_sqlite_classes: ['WatchCommandChannel'] }],
    durable_objects: { bindings: [
      { class_name: 'WatchCommandChannel', name: 'COMMAND_CHANNEL' },
    ] },
    d1_databases: [{
      binding: 'DB',
      database_name: 'watch-cloud-contract-tests',
      database_id: '00000000-0000-0000-0000-000000000000',
    }],
    vars: {
      BUILD_COMMIT: TEST_BUILD_COMMIT,
      ...(revisionDomainId ? { WATCH_PLAN_REVISION_DOMAIN_ID: revisionDomainId } : {}),
      ...(allowLegacy ? { ALLOW_LEGACY_SYNC_V1: 'contract-test-only' } : {}),
      ...(authorityAudience ? { WATCH_AUTHORITY_AUDIENCE: authorityAudience } : {}),
      ...(oauth ? {
        OAUTH_ISSUER: oauth.issuer,
        OAUTH_AUDIENCE: oauth.audience,
        OAUTH_JWKS_URL: oauth.jwksUrl,
        OAUTH_AS_METADATA_URL: oauth.asMetadataUrl,
        OAUTH_INTROSPECTION_URL: oauth.introspectionUrl,
        OAUTH_RS_CLIENT_ID: TEST_RS_CLIENT_ID,
      } : {}),
    },
  }
  const callerConfig = {
    name: 'watch-authority-contract-caller',
    main: callerSourcePath,
    compatibility_date: '2025-03-10',
    compatibility_flags: ['nodejs_compat'],
    services: [
      { binding: 'WATCH_PUBLIC', service: targetName },
      {
        binding: 'WATCH_OBSERVATION',
        service: targetName,
        entrypoint: 'WatchAuthorityObservation',
      },
    ],
  }
  let workerProcess
  try {
    await writeFile(configPath, `${JSON.stringify(config, null, 2)}\n`, 'utf8')
    await writeFile(callerConfigPath, `${JSON.stringify(callerConfig, null, 2)}\n`, 'utf8')
    await writeFile(callerSourcePath, `
export default {
  async fetch(request, env) {
    const url = new URL(request.url)
    if (url.pathname === ${JSON.stringify(AUTHORITY_BINDING_PATH)}) {
      const target = new URL('https://watch-cloud.internal${AUTHORITY_PUBLIC_PATH}')
      return env.WATCH_OBSERVATION.fetch(new Request(target, request))
    }
    return env.WATCH_PUBLIC.fetch(request)
  },
}
`, 'utf8')
    const devVars = [
      `SYNC_KEY=${JSON.stringify(syncKey)}`,
      ...(oauth ? [`OAUTH_RS_CLIENT_SECRET=${JSON.stringify(rsClientSecret)}`] : []),
      ...(effectiveAuthorityCapability
        ? [`WATCH_AUTHORITY_CAPABILITY=${JSON.stringify(effectiveAuthorityCapability)}`]
        : []),
    ]
    await writeFile(resolve(sandbox, '.dev.vars'), `${devVars.join('\n')}\n`, 'utf8')
    if (applySchema) {
      await runWrangler([
        '--config', configPath,
        'd1', 'execute', 'DB', '--local', '--persist-to', stateDirectory, '--file', SCHEMA_PATH,
      ], sandbox)
    }
    if (setupSql) {
      const setupPath = resolve(sandbox, 'contract-setup.sql')
      await writeFile(setupPath, setupSql, 'utf8')
      await runWrangler([
        '--config', configPath,
        'd1', 'execute', 'DB', '--local', '--persist-to', stateDirectory, '--file', setupPath,
      ], sandbox)
    }

    let baseUrl
    const launch = async () => {
      const port = await freePort()
      const invocation = wranglerInvocation([
        '--config', callerConfigPath, '--config', configPath,
        'dev', '--local', '--ip', '127.0.0.1', '--port', String(port), '--persist-to', stateDirectory,
        '--log-level', 'error', '--show-interactive-dev-session', 'false',
      ])
      workerProcess = captureProcess(invocation.command, invocation.args, { cwd: sandbox })
      baseUrl = `http://127.0.0.1:${port}`
      await waitForHttp(`${baseUrl}/healthz`, workerProcess.child, workerProcess.output)
    }
    await launch()
    let stopped = false
    return {
      get baseUrl() { return baseUrl },
      get authorityCapability() { return effectiveAuthorityCapability },
      async stop() {
        if (stopped) return
        stopped = true
        await stopProcess(workerProcess.child)
      },
      async restart() {
        assert.ok(stopped, 'stop the isolated Worker before restarting it')
        await launch()
        stopped = false
        return baseUrl
      },
      async query(sql) {
        assert.ok(stopped, 'stop the isolated Worker before inspecting its D1 file')
        const queryPath = resolve(sandbox, 'contract-query.sql')
        await writeFile(queryPath, sql, 'utf8')
        const result = await runWrangler([
          '--config', configPath,
          'd1', 'execute', 'DB', '--local', '--persist-to', stateDirectory, '--file', queryPath, '--json',
        ], sandbox)
        return parseWranglerRows(result.stdout)
      },
      async dispose() {
        if (!stopped) await stopProcess(workerProcess.child)
        stopped = true
        await rm(sandbox, { force: true, recursive: true })
      },
    }
  } catch (error) {
    if (workerProcess) await stopProcess(workerProcess.child)
    await rm(sandbox, { force: true, recursive: true })
    throw error
  }
}

function parseWranglerRows(raw) {
  const text = raw.replace(/\u001b\[[0-9;]*m/g, '').trim()
  const candidates = [text]
  const arrayStart = text.indexOf('[')
  const arrayEnd = text.lastIndexOf(']')
  if (arrayStart >= 0 && arrayEnd > arrayStart) candidates.push(text.slice(arrayStart, arrayEnd + 1))
  const objectStart = text.indexOf('{')
  const objectEnd = text.lastIndexOf('}')
  if (objectStart >= 0 && objectEnd > objectStart) candidates.push(text.slice(objectStart, objectEnd + 1))
  for (const candidate of candidates) {
    try {
      const parsed = JSON.parse(candidate)
      const item = Array.isArray(parsed) ? parsed[0] : parsed
      if (item && Array.isArray(item.results)) return item.results
    } catch {
      // Try the next JSON-shaped span.
    }
  }
  throw new Error(`Could not parse Wrangler D1 JSON output:\n${text.slice(-8_000)}`)
}

async function requestJson(baseUrl, path, { method = 'GET', token, deviceId, body, headers = {} } = {}) {
  const requestHeaders = { accept: 'application/json', ...headers }
  if (token) requestHeaders.authorization = `Bearer ${token}`
  if (deviceId) requestHeaders['x-watch-device-id'] = deviceId
  if (body !== undefined && typeof body !== 'string') requestHeaders['content-type'] = 'application/json'
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: requestHeaders,
    body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body),
  })
  const raw = await response.text()
  let payload = null
  try {
    payload = JSON.parse(raw)
  } catch {
    // Some route-level errors are intentionally plain text.
  }
  return { response, payload, raw }
}

async function initializeMcp(baseUrl, token) {
  return await fetch(`${baseUrl}/mcp`, {
    method: 'POST',
    headers: {
      accept: 'application/json, text/event-stream',
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 1,
      method: 'initialize',
      params: {
        protocolVersion: '2025-03-26',
        capabilities: {},
        clientInfo: { name: 'watch-worker-contract-test', version: '1.0.0' },
      },
    }),
  })
}

async function callMcpTool(baseUrl, token, name, args = {}) {
  const response = await modernMcpRequest(baseUrl, token, 'tools/call', {
    name,
    arguments: args,
  })
  const text = await response.text()
  assert.equal(response.status, 200, text)
  return text
}

async function modernMcpRequest(baseUrl, token, method, params = {}) {
  const name = typeof params.name === 'string' ? params.name : undefined
  return fetch(`${baseUrl}/mcp`, {
    method: 'POST',
    headers: {
      accept: 'application/json, text/event-stream',
      authorization: `Bearer ${token}`,
      'content-type': 'application/json',
      'mcp-protocol-version': '2026-07-28',
      'mcp-method': method,
      ...(name ? { 'mcp-name': name } : {}),
    },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 3,
      method,
      params: {
        ...params,
        _meta: {
          'io.modelcontextprotocol/protocolVersion': '2026-07-28',
          'io.modelcontextprotocol/clientInfo': {
            name: 'watch-worker-contract-test',
            version: '1.0.0',
          },
          'io.modelcontextprotocol/clientCapabilities': {},
        },
      },
    }),
  })
}

function mcpToolPayload(raw) {
  const dataLine = raw.split(/\r?\n/).find((line) => line.startsWith('data: '))
  const rpc = dataLine ? JSON.parse(dataLine.slice(6)) : JSON.parse(raw)
  const content = rpc.result?.content?.find((item) => item.type === 'text')?.text
  assert.equal(typeof content, 'string', raw)
  try { return JSON.parse(content) } catch { return { text: content, isError: rpc.result?.isError === true } }
}

function uuid(number) {
  return `00000000-0000-4000-8000-${number.toString(16).padStart(12, '0')}`
}

function planPayload(id, name, suffix = '') {
  return {
    schemaVersion: 1,
    id,
    name,
    groupId: 'group-contract',
    groupName: 'Contract plans',
    groupSortOrder: 0,
    requirement: 'Contract-only bounded plan',
    selected: false,
    stages: [{ kind: 'RUN', unit: 'TIME', target: 300 + suffix.length }],
    updatedAt: 1785196800000 + suffix.length,
  }
}

function workoutPayload(workoutId, durationMs = 1_800_000) {
  return {
    schemaVersion: 1,
    id: workoutId,
    startedAt: 1785200400000,
    endedAt: 1785202200000,
    durationMs,
    pausedDurationMs: 0,
    distanceMeters: 5000,
    steps: 6200,
    averageHeartRate: 132,
    planName: 'Contract 5K plan',
    planGroup: 'Contract plans',
    planCompletedActiveMs: durationMs,
    planDistanceMeters: 5000,
    freeRecordingDistanceMeters: 0,
    stageResults: [],
    detailRefs: { route: encryptedRouteRef(workoutId) },
  }
}

function encryptedRouteRef(workoutId) {
  return {
    storage: 'r2',
    key: `contract/${workoutId}/route.ndjson.enc`,
    sha256: 'a'.repeat(64),
    sizeBytes: 4096,
    contentType: 'application/x-ndjson',
    encryption: { algorithm: 'AES-256-GCM', keyId: 'watch-contract-key' },
  }
}

function mutation({ opId, entityType = 'plan', entityId, baseRevision = 0, operation = 'upsert', payload }) {
  return {
    opId,
    entityType,
    entityId,
    baseRevision,
    operation,
    payload: operation === 'delete' ? null : payload,
  }
}

function envelope(deviceId, mutations = [], cursor = null) {
  return { protocolVersion: 1, deviceId, cursor, mutations }
}

function stableJson(value) {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(',')}]`
  if (!value || typeof value !== 'object') return JSON.stringify(value)
  return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stableJson(value[key])}`).join(',')}}`
}

function encryptedMutation({
  opId,
  entityType = 'plan',
  entityId,
  baseRevision = 0,
  operation = 'upsert',
  ciphertext = 'ZW5jcnlwdGVkLXdhdGNoLXBheWxvYWQ',
}) {
  const mutation = {
    opId,
    entityType,
    entityId,
    baseRevision,
    operation,
    keyVersion: 1,
    ciphertext: operation === 'delete' ? null : ciphertext,
    nonce: operation === 'delete' ? null : 'MDEyMzQ1Njc4OWFi',
    aadHash: '',
    objects: [],
  }
  mutation.aadHash = createHash('sha256').update(stableJson({
    envelopeVersion: 1,
    entityId: mutation.entityId,
    entityType: mutation.entityType,
    keyVersion: mutation.keyVersion,
    operation: mutation.operation,
    product: 'watch',
    revision: mutation.baseRevision + 1,
  })).digest('hex')
  return mutation
}

function encryptedEnvelope(deviceId, mutations = [], cursor = null) {
  return {
    protocolVersion: 2,
    envelopeVersion: 1,
    product: 'watch',
    deviceId,
    cursor,
    mutations,
  }
}

function v3Envelope(deviceId, overrides = {}) {
  return {
    protocolVersion: 3,
    requestId: randomUUID(),
    deviceId,
    cursor: null,
    planChanges: [],
    workoutFacts: [],
    sleepRecords: [],
    liveStatus: null,
    commandResults: [],
    ...overrides,
  }
}

function v3PlanLibrary(name = 'Cloud interval') {
  return {
    schemaVersion: 1,
    selectedPlanId: 'plan-cloud',
    groups: [{ id: 'group-cloud', name: 'Cloud plans', sortOrder: 0 }],
    plans: [{
      id: 'plan-cloud', name, groupId: 'group-cloud', requirement: 'Keep form relaxed',
      sortOrder: 0, stages: [{ kind: 'RUN', unit: 'TIME', target: 300 }],
    }],
  }
}

function v3Workout(id = 'workout-cloud', durationMs = 1_800_000) {
  return {
    schemaVersion: 1, id, startedAt: 1_785_200_400_000, endedAt: 1_785_202_200_000,
    durationMs, pausedDurationMs: 0, elapsedDurationMs: durationMs,
    distanceMeters: 5000, steps: 6200, averageHeartRate: 132,
    plan: '{"schemaVersion":1}', planName: 'Cloud interval', planGroup: 'Cloud plans',
    planRequirement: 'Keep form relaxed', stageResults: [],
    averagePaceSecondsPerKm: 360, averageCadenceSpm: 172, elevationGainMeters: 24,
    splits: [{ index: 1, distanceMeters: 1000, durationMs: 350000, paceSecondsPerKm: 350 }],
    heartRateRange: { min: 88, max: 168 },
    dataSourceSummary: {
      distanceSource: 'watch_gps', speedSource: 'gnss', heartRateSource: 'watch_sensor',
      locationAccuracyClass: 'good',
    },
  }
}

function v3SleepRecord() {
  return {
    timestamp: 1_785_139_200_000, totalDurationMinutes: 420, sleepScore: 87,
    spo2AveragePercent: 97, osaResult: -1, heartRateBenchmarkBpm: 58,
    breathRateBenchmarkPerMinute: 15, heartRateRangeBpm: { minimum: 48, maximum: 83 },
    breathRateRangePerMinute: { minimum: 12, maximum: 19 },
    sessions: [{
      startTime: 1_785_110_400_000, endTime: 1_785_135_600_000,
      sleepDurationMinutes: 420, deepDurationMinutes: 92, lightDurationMinutes: 230,
      remDurationMinutes: 78, awakeDurationMinutes: 20,
      stages: [{ type: 2, label: 'deep', startTime: 1_785_110_400_000, endTime: 1_785_115_800_000 }],
    }],
  }
}

function v3LiveStatus(revision = 1) {
  const observedAt = Date.now()
  return {
    statusRevision: revision, observedAt, expiresAt: observedAt + 60_000,
    connectionState: 'connected', activeSession: true, sessionState: 'RUNNING',
    planState: 'ACTIVE',
    workout: {
      activeDurationMs: 300000, distanceMeters: 1200, paceSecondsPerKm: 300,
      speedMps: 3.3, steps: 1600, heartRate: 145, averageHeartRate: 138,
      maximumHeartRate: 154, cadenceSpm: 174, elevationGainMeters: 8,
      stageName: 'Run', stageNumber: 1, stageCount: 4,
    },
  }
}

async function provision(baseUrl, label) {
  const result = await requestJson(baseUrl, '/sync/v1/devices', {
    method: 'POST',
    token: TEST_SYNC_KEY,
    body: { label },
  })
  assert.equal(result.response.status, 201, result.raw)
  assert.match(result.payload.deviceId, /^watch-[0-9a-f-]{36}$/i)
  assert.equal(typeof result.payload.deviceToken, 'string')
  assert.ok(result.payload.deviceToken.length >= 32)
  assert.notEqual(result.payload.deviceToken, TEST_SYNC_KEY)
  return { deviceId: result.payload.deviceId, deviceToken: result.payload.deviceToken }
}

function assertUnauthorized(result) {
  assert.equal(result.response.status, 401, result.raw)
  if (result.payload) assert.equal(result.payload.error, 'unauthorized')
}

function assertInvalidExchange(result) {
  assert.equal(result.response.status, 400, result.raw)
  assert.equal(result.payload?.error, 'invalid_exchange')
}

function assertConflict(result, errorPattern) {
  assert.equal(result.response.status, 200, result.raw)
  assert.equal(result.payload.acknowledged.length, 0)
  assert.equal(result.payload.conflicts.length, 1)
  assert.match(result.payload.conflicts[0].error, errorPattern)
  return result.payload.conflicts[0]
}

function sqlLiteral(value) {
  return `'${String(value).replaceAll("'", "''")}'`
}

test('Watch Worker + D1 canonical sync, auth, metadata and retirement contract', { timeout: 420_000 }, async (t) => {
  const oauth = await startFakeOAuth()
  t.after(async () => oauth.dispose())
  const worker = await startIsolatedWorker({ oauth })
  const rejectedOperationIds = []
  const issuedTokens = []
  let deviceAToken
  let deviceBToken
  let deviceCToken
  let deviceAId
  let deviceBId
  let deviceCId
  let expectedChangeCount = 0
  let v3Cursor = null
  let v3PlanRevision = 0

  try {
    await t.test('/healthz exposes only a validated deployment commit attestation', async () => {
      const health = await requestJson(worker.baseUrl, '/healthz')
      assert.equal(health.response.status, 200, health.raw)
      assert.equal(health.payload.ok, true)
      assert.equal(health.payload.buildCommit, TEST_BUILD_COMMIT)
      assert.deepEqual(Object.keys(health.payload).sort(), [
        'authorityObservationSchemaVersion',
        'buildCommit',
        'cloudSyncProtocolVersion',
        'envelopeVersion',
        'legacySyncProtocolVersion',
        'ok',
        'service',
        'syncProtocolVersion',
      ])
    })

    await t.test('/readyz is public, sanitized and backed by the initialized D1 schema', async () => {
      const ready = await requestJson(worker.baseUrl, '/readyz')
      assert.equal(ready.response.status, 200, ready.raw)
      assert.equal(ready.payload.ok, true)
      assert.equal(ready.payload.storage, 'ready')
      assert.equal(ready.payload.authorityObservation, 'ready')
      assert.equal(ready.payload.revisionDomain, 'ready')
      assert.doesNotMatch(ready.raw, new RegExp(TEST_SYNC_KEY, 'g'))
      assert.doesNotMatch(ready.raw, new RegExp(STATIC_MCP_KEY, 'g'))
      assert.doesNotMatch(ready.raw, new RegExp(worker.authorityCapability, 'g'))
    })

    await t.test('public authority path is closed and a missing real revision fails closed', async () => {
      const headers = {
        accept: AUTHORITY_MEDIA_TYPE,
        authorization: `Capability ${worker.authorityCapability}`,
        'x-poyi-authority-audience': TEST_AUTHORITY_AUDIENCE,
      }
      const publicDirect = await requestJson(worker.baseUrl, AUTHORITY_PUBLIC_PATH, { headers })
      assert.equal(publicDirect.response.status, 403, publicDirect.raw)
      assert.equal(publicDirect.payload.error, 'service_binding_required')
      const unavailable = await requestJson(worker.baseUrl, AUTHORITY_BINDING_PATH, { headers })
      assert.equal(unavailable.response.status, 503, unavailable.raw)
      assert.equal(unavailable.payload.error, 'authority_observation_unavailable')
    })

    await t.test('both protected-resource metadata aliases describe the fixed /mcp resource', async () => {
      let canonical
      for (const path of ['/.well-known/oauth-protected-resource/mcp', '/.well-known/oauth-protected-resource']) {
        const result = await requestJson(worker.baseUrl, path)
        assert.equal(result.response.status, 200, `${path}: ${result.raw}`)
        assert.equal(result.payload.resource, oauth.audience)
        assert.deepEqual(result.payload.authorization_servers, [oauth.issuer])
        assert.deepEqual(result.payload.bearer_methods_supported, ['header'])
        assert.deepEqual(result.payload.scopes_supported, ['watch:read', 'watch:write', 'watch:control'])
        assert.doesNotMatch(result.raw, new RegExp(STATIC_MCP_KEY, 'g'))
        assert.doesNotMatch(result.raw, new RegExp(TEST_SYNC_KEY, 'g'))
        canonical ??= result.payload
        assert.deepEqual(result.payload, canonical)
      }
    })

    await t.test('/mcp rejects anonymous, static, malformed and invalid JWTs with OAuth discovery', async () => {
      for (const token of [undefined, WRONG_TOKEN, STATIC_MCP_KEY]) {
        const response = await initializeMcp(worker.baseUrl, token)
        assert.equal(response.status, 401)
        const challenge = response.headers.get('www-authenticate') ?? ''
        assert.match(challenge, /^Bearer\b/i)
        assert.match(challenge, /resource_metadata="[^"]+\/\.well-known\/oauth-protected-resource\/mcp"/)
        const body = await response.text()
        assert.doesNotMatch(body, new RegExp(STATIC_MCP_KEY, 'g'))
        assert.doesNotMatch(body, new RegExp(TEST_SYNC_KEY, 'g'))
      }

      const now = Math.floor(Date.now() / 1000)
      assert.equal((await initializeMcp(worker.baseUrl, oauth.token({ exp: now - 120 }).value)).status, 401)
      assert.equal((await initializeMcp(worker.baseUrl, oauth.token({ aud: 'https://wrong.example/mcp' }).value)).status, 401)
      const wrongScope = await initializeMcp(worker.baseUrl, oauth.token({ scope: 'journal:read' }).value)
      assert.equal(wrongScope.status, 403)
      assert.match(wrongScope.headers.get('www-authenticate') ?? '', /insufficient_scope/)

      const revoked = oauth.token()
      oauth.revoke(revoked.claims.jti)
      assert.equal((await initializeMcp(worker.baseUrl, revoked.value)).status, 401)

      const response = await initializeMcp(worker.baseUrl, oauth.token().value)
      const responseText = await response.text()
      assert.equal(response.status, 200, responseText)
      assert.match(responseText, /serverInfo|poyi-watch/)

      const legacySecretPath = await fetch(`${worker.baseUrl}/${encodeURIComponent(STATIC_MCP_KEY)}/mcp`, {
        method: 'POST',
      })
      assert.equal(legacySecretPath.status, 404)
    })

    await t.test('tools/list advertises per-tool OAuth security schemes for ChatGPT linking', async () => {
      const accessToken = oauth.token().value
      const discovered = await modernMcpRequest(worker.baseUrl, accessToken, 'server/discover')
      const discoveredBody = await discovered.text()
      assert.equal(discovered.status, 200, discoveredBody)
      assert.match(discoveredBody, /2026-07-28/)
      assert.equal(discovered.headers.get('mcp-session-id'), null)
      const listed = await modernMcpRequest(worker.baseUrl, accessToken, 'tools/list')
      const body = await listed.text()
      assert.equal(listed.status, 200, body)
      assert.match(body, /securitySchemes/)
      assert.ok((body.match(/watch:read/g) ?? []).length >= 10, body)
      assert.ok((body.match(/watch:write/g) ?? []).length >= 6, body)
      assert.ok((body.match(/watch:control/g) ?? []).length >= 5, body)
      assert.match(body, /watch_get_plan/)
      assert.match(body, /watch_move_plan/)
      assert.match(body, /watch_replace_plan_stages/)
      assert.match(body, /\"kind\"/)
      assert.match(body, /\"unit\"/)
      assert.match(body, /\"target\"/)
      assert.doesNotMatch(body, /ACCESS_KEY/)
    })

    await t.test('retired /sync/push returns 410 before auth, method or body parsing', async () => {
      const cases = [
        { method: 'POST', body: '{not-json', headers: { 'content-type': 'application/json' } },
        { method: 'POST', token: TEST_SYNC_KEY, body: { source: 'pc-sync', snapshots: {} } },
        { method: 'POST', token: STATIC_MCP_KEY, body: 'x'.repeat(1024 * 1024 + 1) },
        { method: 'GET' },
        { method: 'PUT', body: '{}' },
        { method: 'DELETE' },
      ]
      for (const options of cases) {
        const result = await requestJson(worker.baseUrl, '/sync/push', options)
        assert.equal(result.response.status, 410, `${options.method}: ${result.raw}`)
      }
    })

    await t.test('plaintext v1 data routes default to 410 outside the explicit contract harness', async () => {
      const retired = await startIsolatedWorker({ oauth, allowLegacy: false })
      try {
        const exchange = await requestJson(retired.baseUrl, '/sync/v1/exchange', {
          method: 'POST',
          body: '{not-json',
          headers: { 'content-type': 'application/json' },
        })
        assert.equal(exchange.response.status, 410, exchange.raw)
        assert.equal(exchange.payload?.replacement, '/sync/v2/exchange')
        const status = await requestJson(retired.baseUrl, '/sync/v1/status')
        assert.equal(status.response.status, 410, status.raw)
        assert.equal(status.payload?.replacement, '/sync/v2/status')
        const encrypted = await requestJson(retired.baseUrl, '/sync/v2/exchange', {
          method: 'POST', body: {},
        })
        assert.equal(encrypted.response.status, 401, encrypted.raw)
      } finally {
        await retired.dispose()
      }
    })

    await t.test('device provisioning requires the admin bearer and rotation kills the old token', async () => {
      const anonymous = await requestJson(worker.baseUrl, '/sync/v1/devices', {
        method: 'POST',
        body: { label: 'A' },
      })
      assertUnauthorized(anonymous)

      const wrong = await requestJson(worker.baseUrl, '/sync/v1/devices', {
        method: 'POST',
        token: WRONG_TOKEN,
        body: { label: 'A' },
      })
      assertUnauthorized(wrong)

      const provisionedA = await provision(worker.baseUrl, 'Contract device A')
      deviceAId = provisionedA.deviceId
      const originalToken = provisionedA.deviceToken
      issuedTokens.push(originalToken)
      const rotated = await requestJson(worker.baseUrl, `/sync/v1/devices/${deviceAId}/rotate`, {
        method: 'POST',
        token: TEST_SYNC_KEY,
        body: { label: 'Contract device A rotated' },
      })
      assert.equal(rotated.response.status, 200, rotated.raw)
      deviceAToken = rotated.payload.deviceToken
      issuedTokens.push(deviceAToken)
      assert.notEqual(deviceAToken, originalToken)

      const oldToken = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
        method: 'POST',
        token: originalToken,
        body: envelope(deviceAId),
      })
      assertUnauthorized(oldToken)

      const provisionedB = await provision(worker.baseUrl, 'Contract device B')
      deviceBId = provisionedB.deviceId
      deviceBToken = provisionedB.deviceToken
      const provisionedC = await provision(worker.baseUrl, 'Pagination device')
      deviceCId = provisionedC.deviceId
      deviceCToken = provisionedC.deviceToken
      issuedTokens.push(deviceBToken, deviceCToken)
    })

    await t.test('exchange distinguishes anonymous, wrong, admin, MCP, mismatched and correct device bearers', async () => {
      for (const token of [undefined, WRONG_TOKEN, TEST_SYNC_KEY, STATIC_MCP_KEY, oauth.token().value]) {
        const result = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
          method: 'POST',
          token,
          body: envelope(deviceAId),
        })
        assertUnauthorized(result)
      }

      const mismatched = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
        method: 'POST',
        token: deviceAToken,
        body: envelope(deviceBId),
      })
      assert.equal(mismatched.response.status, 403, mismatched.raw)
      assert.equal(mismatched.payload.error, 'device_mismatch')

      const correct = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
        method: 'POST',
        token: deviceAToken,
        body: envelope(deviceAId),
      })
      assert.equal(correct.response.status, 200, correct.raw)
      assert.deepEqual(correct.payload.changes, [])
      assert.equal(correct.payload.nextCursor, 'c0')

      const deviceCredentialOnMcp = await initializeMcp(worker.baseUrl, deviceAToken)
      assert.equal(deviceCredentialOnMcp.status, 401)
    })

    await t.test('sync status requires the authenticated device id header', async () => {
      const missing = await requestJson(worker.baseUrl, '/sync/v1/status', { token: deviceAToken })
      assert.equal(missing.response.status, 403, missing.raw)
      assert.equal(missing.payload.error, 'device_mismatch')
      const wrong = await requestJson(worker.baseUrl, '/sync/v1/status', {
        token: deviceAToken,
        deviceId: deviceBId,
      })
      assert.equal(wrong.response.status, 403, wrong.raw)
      const correct = await requestJson(worker.baseUrl, '/sync/v1/status', {
        token: deviceAToken,
        deviceId: deviceAId,
      })
      assert.equal(correct.response.status, 200, correct.raw)
      assert.equal(correct.payload.deviceId, deviceAId)
    })

    await t.test('SyncEnvelopeV1 accepts ciphertext only, preserves conflicts and keeps workouts immutable', async () => {
      const plan = encryptedMutation({
        opId: uuid(950),
        entityId: 'encrypted-plan-contract',
        ciphertext: 'cGxhbi1jaXBoZXJ0ZXh0LXByb2R1Y3Qtc2VjcmV0',
      })
      const created = await requestJson(worker.baseUrl, '/sync/v2/exchange', {
        method: 'POST',
        token: deviceAToken,
        body: encryptedEnvelope(deviceAId, [plan]),
      })
      assert.equal(created.response.status, 200, created.raw)
      assert.equal(created.payload.protocolVersion, 2)
      assert.equal(created.payload.envelopeVersion, 1)
      assert.equal(created.payload.product, 'watch')
      assert.equal(created.payload.acknowledged[0].revision, 1)
      assert.equal(created.payload.changes[0].ciphertext, plan.ciphertext)
      assert.equal(Object.hasOwn(created.payload.changes[0], 'payload'), false)
      assert.match(created.payload.nextCursor, /^c[0-9a-z]+$/)

      const replay = await requestJson(worker.baseUrl, '/sync/v2/exchange', {
        method: 'POST',
        token: deviceAToken,
        body: encryptedEnvelope(deviceAId, [plan], created.payload.nextCursor),
      })
      assert.equal(replay.response.status, 200, replay.raw)
      assert.equal(replay.payload.acknowledged[0].replayed, true)
      assert.deepEqual(replay.payload.changes, [])

      const stale = encryptedMutation({
        opId: uuid(951),
        entityId: plan.entityId,
        ciphertext: 'c3RhbGUtcGxhbi1jaXBoZXJ0ZXh0',
      })
      const conflict = await requestJson(worker.baseUrl, '/sync/v2/exchange', {
        method: 'POST',
        token: deviceBToken,
        body: encryptedEnvelope(deviceBId, [stale]),
      })
      assert.equal(conflict.response.status, 200, conflict.raw)
      assert.equal(conflict.payload.conflicts[0].error, 'REVISION_CONFLICT')
      assert.equal(conflict.payload.conflicts[0].current.ciphertext, plan.ciphertext)
      assert.deepEqual(conflict.payload.conflicts[0].candidate, stale)

      const workout = encryptedMutation({
        opId: uuid(952),
        entityType: 'workout',
        entityId: 'encrypted-workout-contract',
      })
      const workoutCreated = await requestJson(worker.baseUrl, '/sync/v2/exchange', {
        method: 'POST',
        token: deviceAToken,
        body: encryptedEnvelope(deviceAId, [workout], created.payload.nextCursor),
      })
      assert.equal(workoutCreated.response.status, 200, workoutCreated.raw)
      assert.equal(workoutCreated.payload.acknowledged[0].revision, 1)

      const workoutUpdate = encryptedMutation({
        opId: uuid(953),
        entityType: 'workout',
        entityId: workout.entityId,
        baseRevision: 1,
        ciphertext: 'c2Vjb25kLXdlbGwtZm9ybWVkLWNpcGhlcnRleHQ',
      })
      const immutable = await requestJson(worker.baseUrl, '/sync/v2/exchange', {
        method: 'POST',
        token: deviceAToken,
        body: encryptedEnvelope(deviceAId, [workoutUpdate], workoutCreated.payload.nextCursor),
      })
      assert.equal(immutable.response.status, 200, immutable.raw)
      assert.equal(immutable.payload.conflicts[0].error, 'IMMUTABLE_ENTITY')
      assert.equal(immutable.payload.conflicts[0].current.ciphertext, workout.ciphertext)

      const status = await requestJson(worker.baseUrl, '/sync/v2/status', {
        token: deviceAToken,
        deviceId: deviceAId,
      })
      assert.equal(status.response.status, 200, status.raw)
      assert.equal(status.payload.authority, 'remote_ciphertext_authoritative')
      assert.equal(status.payload.product, 'watch')
    })

    await t.test('retained V2 read projection stays isolated from the Cloud V3 MCP data plane', async () => {
      const projection = {
        plans: [{ entityKey: `p:${'a'.repeat(64)}`, name: '户外间歇' }],
        workouts: [{
          entityKey: `w:${'b'.repeat(64)}`,
          workoutType: 'planned',
          startedAt: 1785200400000,
          endedAt: 1785202200000,
          durationMs: 1_800_000,
          distanceMeters: 5000.5,
          steps: 6200,
        }],
      }
      const accepted = await requestJson(worker.baseUrl, '/sync/v2/exchange', {
        method: 'POST',
        token: deviceAToken,
        body: { ...encryptedEnvelope(deviceAId), readProjection: projection },
      })
      assert.equal(accepted.response.status, 200, accepted.raw)

      for (const forbidden of [
        { route: [{ latitude: 1, longitude: 2 }] },
        { heartRateSamples: [120] },
        { sleep: { score: 90 } },
        { credential: 'must-not-enter-d1' },
        { rawSensors: [1, 2, 3] },
      ]) {
        const rejected = await requestJson(worker.baseUrl, '/sync/v2/exchange', {
          method: 'POST',
          token: deviceAToken,
          body: {
            ...encryptedEnvelope(deviceAId),
            readProjection: {
              ...projection,
              workouts: [{ ...projection.workouts[0], ...forbidden }],
            },
          },
        })
        assert.equal(rejected.response.status, 400, rejected.raw)
        assert.equal(rejected.payload.error, 'invalid_read_projection')
      }

      const mismatched = await requestJson(worker.baseUrl, '/sync/v2/exchange', {
        method: 'POST',
        token: deviceBToken,
        body: { ...encryptedEnvelope(deviceAId), readProjection: { plans: [], workouts: [] } },
      })
      assert.equal(mismatched.response.status, 403, mismatched.raw)

      const oauthToken = oauth.token().value
      const plans = await callMcpTool(worker.baseUrl, oauthToken, 'watch_list_plans')
      assert.equal(mcpToolPayload(plans).revision, 0)
      assert.doesNotMatch(plans, /户外间歇/)
      const workouts = await callMcpTool(worker.baseUrl, oauthToken, 'watch_list_workouts')
      assert.deepEqual(mcpToolPayload(workouts).workouts, [])
      assert.doesNotMatch(workouts, /planned|5000\.5/)
      assert.doesNotMatch(workouts, /latitude|longitude|coordinates|sleep|credential|rawSensors|entityKey/)
      const summary = await callMcpTool(worker.baseUrl, oauthToken, 'watch_summarize_workouts')
      assert.equal(mcpToolPayload(summary).workoutCount, 0)
      assert.equal(mcpToolPayload(summary).totalSteps, 0)
    })

    await t.test('V3 exchange bootstraps the cloud plan library with strict replay, OCC, cursor and privacy rules', async () => {
      const anonymous = await requestJson(worker.baseUrl, '/sync/v3/exchange', {
        method: 'POST', body: v3Envelope(deviceAId),
      })
      assertUnauthorized(anonymous)

      const planOperationId = uuid(3001)
      const body = v3Envelope(deviceAId, {
        requestId: uuid(3000),
        planChanges: [{ operationId: planOperationId, expectedRevision: 0, library: v3PlanLibrary() }],
        liveStatus: v3LiveStatus(),
      })
      const created = await requestJson(worker.baseUrl, '/sync/v3/exchange', {
        method: 'POST', token: deviceAToken, body,
      })
      assert.equal(created.response.status, 200, created.raw)
      assert.equal(created.payload.protocolVersion, 3)
      assert.equal(created.payload.authority, 'cloud_authoritative')
      assert.equal(created.payload.revisionDomainId, 'v3d.watch-contract-owner-v1')
      assert.equal(created.payload.acknowledgements[0].outcome, 'acknowledged')
      assert.equal(created.payload.acknowledgements[0].revision, 1)
      assert.equal(created.payload.planLibrary.plans[0].name, 'Cloud interval')
      assert.match(created.payload.nextCursor, /^v3c[0-9a-z]+$/)
      v3Cursor = created.payload.nextCursor
      v3PlanRevision = 1

      const replay = await requestJson(worker.baseUrl, '/sync/v3/exchange', {
        method: 'POST', token: deviceAToken, body,
      })
      assert.equal(replay.response.status, 200, replay.raw)
      assert.equal(replay.payload.replayed, true)
      assert.equal(replay.payload.revisionDomainId, created.payload.revisionDomainId)
      assert.equal(replay.payload.serverTime, created.payload.serverTime)

      const requestReuse = await requestJson(worker.baseUrl, '/sync/v3/exchange', {
        method: 'POST', token: deviceAToken,
        body: { ...body, cursor: v3Cursor, liveStatus: null },
      })
      assert.equal(requestReuse.response.status, 409, requestReuse.raw)
      assert.equal(requestReuse.payload.error, 'request_id_reused')

      const stale = await requestJson(worker.baseUrl, '/sync/v3/exchange', {
        method: 'POST', token: deviceBToken,
        body: v3Envelope(deviceBId, {
          requestId: uuid(3002), cursor: v3Cursor,
          planChanges: [{
            operationId: uuid(3003), expectedRevision: 0,
            library: v3PlanLibrary('Must not overwrite'),
          }],
        }),
      })
      assert.equal(stale.response.status, 200, stale.raw)
      assert.equal(stale.payload.acknowledgements[0].outcome, 'conflict')
      assert.equal(stale.payload.acknowledgements[0].error, 'revision_conflict')
      assert.equal(stale.payload.revisionDomainId, created.payload.revisionDomainId)
      assert.equal(stale.payload.planLibrary.plans[0].name, 'Cloud interval')

      const reusedOperation = await requestJson(worker.baseUrl, '/sync/v3/exchange', {
        method: 'POST', token: deviceAId ? deviceAToken : null,
        body: v3Envelope(deviceAId, {
          requestId: uuid(3004), cursor: v3Cursor,
          planChanges: [{
            operationId: planOperationId, expectedRevision: 1,
            library: v3PlanLibrary('Changed operation body'),
          }],
        }),
      })
      assert.equal(reusedOperation.response.status, 200, reusedOperation.raw)
      assert.equal(reusedOperation.payload.acknowledgements[0].error, 'operation_id_reused')

      const ahead = await requestJson(worker.baseUrl, '/sync/v3/exchange', {
        method: 'POST', token: deviceAToken,
        body: v3Envelope(deviceAId, { cursor: 'v3czzzzzz' }),
      })
      assert.equal(ahead.response.status, 409, ahead.raw)
      assert.equal(ahead.payload.error, 'cursor_ahead')
      assert.equal(ahead.payload.resetCursor, v3Cursor)

      for (const invalidBody of [
        { ...v3Envelope(deviceAId), extra: true },
        v3Envelope(deviceAId, { workoutFacts: [{ operationId: uuid(3005), workout: { ...v3Workout(), route: [] } }] }),
        v3Envelope(deviceAId, { planChanges: Array.from({ length: 26 }, (_, index) => ({
          operationId: uuid(3100 + index), expectedRevision: 1, library: v3PlanLibrary(),
        })) }),
      ]) {
        const invalid = await requestJson(worker.baseUrl, '/sync/v3/exchange', {
          method: 'POST', token: deviceAToken, body: invalidBody,
        })
        assert.equal(invalid.response.status, 400, invalid.raw)
        assert.equal(invalid.payload.error, 'invalid_exchange')
      }
      const mismatched = await requestJson(worker.baseUrl, '/sync/v3/exchange', {
        method: 'POST', token: deviceBToken, body: v3Envelope(deviceAId),
      })
      assert.equal(mismatched.response.status, 403, mismatched.raw)

      const channelWithoutUpgrade = await requestJson(worker.baseUrl, '/sync/v3/channel', {
        token: deviceAToken,
      })
      assert.equal(channelWithoutUpgrade.response.status, 426, channelWithoutUpgrade.raw)
    })

    await t.test('V3 stores immutable workout facts, detailed sleep and live status without raw telemetry', async () => {
      const workout = v3Workout()
      const sleep = v3SleepRecord()
      const uploaded = await requestJson(worker.baseUrl, '/sync/v3/exchange', {
        method: 'POST', token: deviceAToken,
        body: v3Envelope(deviceAId, {
          requestId: uuid(3200), cursor: v3Cursor,
          workoutFacts: [{ operationId: uuid(3201), workout }],
          sleepRecords: [{
            operationId: uuid(3202), recordId: 'sleep-cloud', sourceRevision: 'source-1', record: sleep,
          }],
          liveStatus: v3LiveStatus(2),
        }),
      })
      assert.equal(uploaded.response.status, 200, uploaded.raw)
      assert.deepEqual(uploaded.payload.acknowledgements.map((item) => item.outcome),
        ['acknowledged', 'acknowledged'])
      v3Cursor = uploaded.payload.nextCursor

      const duplicateFact = await requestJson(worker.baseUrl, '/sync/v3/exchange', {
        method: 'POST', token: deviceAToken,
        body: v3Envelope(deviceAId, {
          requestId: uuid(3203), cursor: v3Cursor,
          workoutFacts: [{ operationId: uuid(3204), workout }],
        }),
      })
      assert.equal(duplicateFact.response.status, 200, duplicateFact.raw)
      assert.equal(duplicateFact.payload.acknowledgements[0].outcome, 'acknowledged')
      assert.equal(duplicateFact.payload.acknowledgements[0].replayed, true)

      const changedFact = await requestJson(worker.baseUrl, '/sync/v3/exchange', {
        method: 'POST', token: deviceAToken,
        body: v3Envelope(deviceAId, {
          requestId: uuid(3205), cursor: v3Cursor,
          workoutFacts: [{ operationId: uuid(3206), workout: v3Workout('workout-cloud', 1_800_001) }],
        }),
      })
      assert.equal(changedFact.response.status, 200, changedFact.raw)
      assert.equal(changedFact.payload.acknowledgements[0].error, 'workout_immutable')

      const readToken = oauth.token({ scope: 'watch:read' }).value
      const detail = mcpToolPayload(await callMcpTool(
        worker.baseUrl, readToken, 'watch_get_workout', { workoutId: 'workout-cloud' },
      ))
      assert.equal(detail.workout.distanceMeters, 5000)
      assert.equal(detail.workout.minimumHeartRate, 88)
      assert.equal(detail.workout.maximumHeartRate, 168)
      assert.equal(detail.workout.rawRoute, 'local_only')
      assert.equal(detail.workout.heartRateSamples, 'local_only')
      assert.equal(Object.hasOwn(detail.workout, 'route'), false)

      const sleepRecords = mcpToolPayload(await callMcpTool(
        worker.baseUrl, readToken, 'watch_list_sleep_records', { limit: 31 },
      ))
      assert.equal(sleepRecords.records[0].record.sleepScore, 87)
      assert.equal(sleepRecords.records[0].record.sessions[0].stages[0].type, 2)
      const status = mcpToolPayload(await callMcpTool(worker.baseUrl, readToken, 'watch_get_status'))
      assert.equal(status.supportsPcOff, false)
      assert.equal(status.status.workout.heartRate, 145)
      assert.doesNotMatch(JSON.stringify({ detail, sleepRecords, status }),
        /latitude|longitude|coordinates|accessToken|refreshToken/)
    })

    await t.test('MCP read, write and control scopes are isolated and commands complete exactly once by Watch ACK', async () => {
      const readToken = oauth.token({ scope: 'watch:read' }).value
      const writeToken = oauth.token({ scope: 'watch:write' }).value
      const controlToken = oauth.token({ scope: 'watch:control' }).value
      const writeArgs = {
        requestId: uuid(3300), operationId: uuid(3301), expectedRevision: v3PlanRevision,
        plan: {
          ...v3PlanLibrary('Cloud interval updated').plans[0],
          name: 'Cloud interval updated',
        },
      }
      const readDeniedWrite = mcpToolPayload(await callMcpTool(
        worker.baseUrl, readToken, 'watch_upsert_plan', writeArgs,
      ))
      assert.equal(readDeniedWrite.isError, true)
      assert.match(readDeniedWrite.text, /watch:write/)

      const writeResult = mcpToolPayload(await callMcpTool(
        worker.baseUrl, writeToken, 'watch_upsert_plan', writeArgs,
      ))
      assert.equal(writeResult.outcome, 'acknowledged')
      v3PlanRevision = writeResult.revision
      const onePlan = mcpToolPayload(await callMcpTool(
        worker.baseUrl, readToken, 'watch_get_plan', { planId: 'plan-cloud' },
      ))
      assert.equal(onePlan.plan.name, 'Cloud interval updated')
      assert.equal(onePlan.plan.stages[0].target, 300)

      const nonEmptyGroupDelete = mcpToolPayload(await callMcpTool(
        worker.baseUrl, writeToken, 'watch_delete_plan_group', {
          requestId: uuid(3340), operationId: uuid(3341),
          expectedRevision: v3PlanRevision, groupId: 'group-cloud',
        },
      ))
      assert.equal(nonEmptyGroupDelete.outcome, 'conflict')
      assert.equal(nonEmptyGroupDelete.error, 'group_not_empty')

      const newGroup = mcpToolPayload(await callMcpTool(
        worker.baseUrl, writeToken, 'watch_upsert_plan_group', {
          requestId: uuid(3342), operationId: uuid(3343),
          expectedRevision: v3PlanRevision,
          group: { id: 'group-week-two', name: 'Week two', sortOrder: 1 },
        },
      ))
      assert.equal(newGroup.outcome, 'acknowledged')
      v3PlanRevision = newGroup.revision

      const replacedStages = mcpToolPayload(await callMcpTool(
        worker.baseUrl, writeToken, 'watch_replace_plan_stages', {
          requestId: uuid(3344), operationId: uuid(3345),
          expectedRevision: v3PlanRevision, planId: 'plan-cloud',
          stages: [
            { kind: 'WALK', unit: 'TIME', target: 300 },
            { kind: 'RUN', unit: 'TIME', target: 120 },
            { kind: 'WALK', unit: 'TIME', target: 60 },
          ],
        },
      ))
      assert.equal(replacedStages.outcome, 'acknowledged')
      v3PlanRevision = replacedStages.revision

      const movedPlan = mcpToolPayload(await callMcpTool(
        worker.baseUrl, writeToken, 'watch_move_plan', {
          requestId: uuid(3346), operationId: uuid(3347),
          expectedRevision: v3PlanRevision, planId: 'plan-cloud',
          groupId: 'group-week-two', sortOrder: 0,
        },
      ))
      assert.equal(movedPlan.outcome, 'acknowledged')
      v3PlanRevision = movedPlan.revision

      const changedPlan = mcpToolPayload(await callMcpTool(
        worker.baseUrl, readToken, 'watch_get_plan', { planId: 'plan-cloud' },
      ))
      assert.equal(changedPlan.plan.groupId, 'group-week-two')
      assert.deepEqual(changedPlan.plan.stages.map((stage) => stage.target), [300, 120, 60])

      const missingPlanDelete = mcpToolPayload(await callMcpTool(
        worker.baseUrl, writeToken, 'watch_delete_plan', {
          requestId: uuid(3348), operationId: uuid(3349),
          expectedRevision: v3PlanRevision, planId: 'missing-plan',
        },
      ))
      assert.equal(missingPlanDelete.outcome, 'conflict')
      assert.equal(missingPlanDelete.error, 'plan_not_found')
      const writeDeniedRead = mcpToolPayload(await callMcpTool(
        worker.baseUrl, writeToken, 'watch_list_plans',
      ))
      assert.equal(writeDeniedRead.isError, true)
      assert.match(writeDeniedRead.text, /watch:read/)
      const controlDeniedWrite = mcpToolPayload(await callMcpTool(
        worker.baseUrl, controlToken, 'watch_upsert_plan', writeArgs,
      ))
      assert.equal(controlDeniedWrite.isError, true)

      const commandArgs = {
        requestId: uuid(3310), commandId: uuid(3311), expectedState: 'IDLE',
        controlRevision: 7, planId: 'plan-cloud',
      }
      const controlCall = callMcpTool(
        worker.baseUrl, controlToken, 'watch_start_workout', commandArgs,
      )
      await new Promise((resolvePromise) => setTimeout(resolvePromise, 500))
      const delivery = await requestJson(worker.baseUrl, '/sync/v3/exchange', {
        method: 'POST', token: deviceAToken,
        body: v3Envelope(deviceAId, { requestId: uuid(3312), cursor: v3Cursor }),
      })
      assert.equal(delivery.response.status, 200, delivery.raw)
      assert.equal(delivery.payload.pendingCommands.length, 1)
      assert.equal(delivery.payload.pendingCommands[0].commandId, commandArgs.commandId)
      assert.equal(delivery.payload.pendingCommands[0].type, 'start')
      assert.deepEqual(delivery.payload.pendingCommands[0].arguments, {
        planId: commandArgs.planId,
      })
      v3Cursor = delivery.payload.nextCursor
      const acknowledged = await requestJson(worker.baseUrl, '/sync/v3/exchange', {
        method: 'POST', token: deviceAToken,
        body: v3Envelope(deviceAId, {
          requestId: uuid(3313), cursor: v3Cursor,
          commandResults: [{
            commandId: commandArgs.commandId, outcome: 'succeeded', actualState: 'RUNNING',
            controlRevision: 8, completedAt: Date.now(), error: null,
          }],
        }),
      })
      assert.equal(acknowledged.response.status, 200, acknowledged.raw)
      assert.equal(acknowledged.payload.commandAcknowledgements[0].outcome, 'acknowledged')
      v3Cursor = acknowledged.payload.nextCursor
      const controlResult = mcpToolPayload(await controlCall)
      assert.equal(controlResult.status, 'succeeded')
      assert.equal(controlResult.result.actualState, 'RUNNING')

      const replay = mcpToolPayload(await callMcpTool(
        worker.baseUrl, controlToken, 'watch_start_workout', commandArgs,
      ))
      assert.equal(replay.status, 'succeeded')
      const changedReplay = mcpToolPayload(await callMcpTool(
        worker.baseUrl, controlToken, 'watch_start_workout', { ...commandArgs, controlRevision: 9 },
      ))
      assert.equal(changedReplay.error, 'request_id_reused')

      const deleteArgs = {
        requestId: uuid(3320), commandId: uuid(3321), expectedState: 'STOPPED',
        controlRevision: 8, workoutId: 'workout-cloud',
      }
      const deleteCall = callMcpTool(
        worker.baseUrl, writeToken, 'watch_delete_workout', deleteArgs,
      )
      await new Promise((resolvePromise) => setTimeout(resolvePromise, 500))
      const deleteDelivery = await requestJson(worker.baseUrl, '/sync/v3/exchange', {
        method: 'POST', token: deviceAToken,
        body: v3Envelope(deviceAId, { requestId: uuid(3322), cursor: null }),
      })
      assert.equal(deleteDelivery.response.status, 200, deleteDelivery.raw)
      assert.equal(deleteDelivery.payload.pendingCommands[0].commandId, deleteArgs.commandId)
      const deleteAck = await requestJson(worker.baseUrl, '/sync/v3/exchange', {
        method: 'POST', token: deviceAToken,
        body: v3Envelope(deviceAId, {
          requestId: uuid(3323), cursor: deleteDelivery.payload.nextCursor,
          commandResults: [{
            commandId: deleteArgs.commandId, outcome: 'succeeded', actualState: 'STOPPED',
            controlRevision: 9, completedAt: Date.now(), error: null,
          }],
        }),
      })
      assert.equal(deleteAck.response.status, 200, deleteAck.raw)
      assert.equal(mcpToolPayload(await deleteCall).status, 'succeeded')
      const deletedUpload = await requestJson(worker.baseUrl, '/sync/v3/exchange', {
        method: 'POST', token: deviceAToken,
        body: v3Envelope(deviceAId, {
          requestId: uuid(3324), cursor: deleteAck.payload.nextCursor,
          workoutFacts: [{ operationId: uuid(3325), workout: v3Workout() }],
        }),
      })
      assert.equal(deletedUpload.response.status, 200, deletedUpload.raw)
      assert.equal(deletedUpload.payload.acknowledgements[0].error, 'workout_deleted')
      const deletedDetail = mcpToolPayload(await callMcpTool(
        worker.baseUrl, readToken, 'watch_get_workout', { workoutId: 'workout-cloud' },
      ))
      assert.equal(deletedDetail.workout, null)

      const expiringArgs = {
        requestId: uuid(3330), commandId: uuid(3331), expectedState: 'RUNNING',
        controlRevision: 9,
      }
      const pending = mcpToolPayload(await callMcpTool(
        worker.baseUrl, controlToken, 'watch_pause_workout', expiringArgs,
      ))
      assert.equal(pending.status, 'pending')
      await worker.stop()
      await worker.query(`
        UPDATE watch_v3_commands
        SET expires_at = '2026-07-28T00:00:00.000Z'
        WHERE owner_id = 'poyi-owner' AND command_id = '${expiringArgs.commandId}';
      `)
      await worker.restart()
      const lateResult = await requestJson(worker.baseUrl, '/sync/v3/exchange', {
        method: 'POST', token: deviceAToken,
        body: v3Envelope(deviceAId, {
          requestId: uuid(3332), cursor: null,
          commandResults: [{
            commandId: expiringArgs.commandId, outcome: 'succeeded', actualState: 'PAUSED',
            controlRevision: 10, completedAt: Date.now(), error: null,
          }],
        }),
      })
      assert.equal(lateResult.response.status, 200, lateResult.raw)
      assert.equal(lateResult.payload.commandAcknowledgements[0].error, 'command_expired')
      const expiredStatus = mcpToolPayload(await callMcpTool(
        worker.baseUrl, controlToken, 'watch_get_command_status', { commandId: expiringArgs.commandId },
      ))
      assert.equal(expiredStatus.command.status, 'expired')
    })

    await t.test('service-binding authority observation is real, monotonic and cross-token isolated', async () => {
      const path = AUTHORITY_BINDING_PATH
      const oauthToken = oauth.token().value
      for (const token of [undefined, deviceAToken, oauthToken]) {
        const rejected = await requestJson(worker.baseUrl, path, {
          token,
          headers: {
            accept: AUTHORITY_MEDIA_TYPE,
            'x-poyi-authority-audience': TEST_AUTHORITY_AUDIENCE,
          },
        })
        assert.equal(rejected.response.status, 401, rejected.raw)
        assert.equal(rejected.payload.error, 'unauthorized')
      }
      const wrongAccept = await requestJson(worker.baseUrl, path, {
        headers: {
          authorization: `Capability ${worker.authorityCapability}`,
          'x-poyi-authority-audience': TEST_AUTHORITY_AUDIENCE,
        },
      })
      assert.equal(wrongAccept.response.status, 406, wrongAccept.raw)
      const wrongCapability = await requestJson(worker.baseUrl, path, {
        headers: {
          accept: AUTHORITY_MEDIA_TYPE,
          authorization: `Capability wao_${randomBytes(32).toString('base64url')}`,
          'x-poyi-authority-audience': TEST_AUTHORITY_AUDIENCE,
        },
      })
      assert.equal(wrongCapability.response.status, 401, wrongCapability.raw)
      const crossProductCapability = await requestJson(worker.baseUrl, path, {
        headers: {
          accept: AUTHORITY_MEDIA_TYPE,
          authorization: `Capability jao_${randomBytes(32).toString('base64url')}`,
          'x-poyi-authority-audience': TEST_AUTHORITY_AUDIENCE,
        },
      })
      assert.equal(crossProductCapability.response.status, 401, crossProductCapability.raw)
      const missingAudience = await requestJson(worker.baseUrl, path, {
        headers: {
          accept: AUTHORITY_MEDIA_TYPE,
          authorization: `Capability ${worker.authorityCapability}`,
        },
      })
      assert.equal(missingAudience.response.status, 403, missingAudience.raw)
      const wrongAudience = await requestJson(worker.baseUrl, path, {
        headers: {
          accept: AUTHORITY_MEDIA_TYPE,
          authorization: `Capability ${worker.authorityCapability}`,
          'x-poyi-authority-audience': 'https://personal-mcp-gateway.contract.test/mcp',
        },
      })
      assert.equal(wrongAudience.response.status, 403, wrongAudience.raw)
      const writeAttempt = await requestJson(worker.baseUrl, path, {
        method: 'POST',
        headers: {
          accept: AUTHORITY_MEDIA_TYPE,
          authorization: `Capability ${worker.authorityCapability}`,
          'x-poyi-authority-audience': TEST_AUTHORITY_AUDIENCE,
        },
      })
      assert.equal(writeAttempt.response.status, 405, writeAttempt.raw)

      for (const [deviceId, token] of [[deviceAId, deviceAToken], [deviceBId, deviceBToken]]) {
        const caughtUp = await requestJson(worker.baseUrl, '/sync/v3/exchange', {
          method: 'POST', token, body: v3Envelope(deviceId, { cursor: null }),
        })
        assert.equal(caughtUp.response.status, 200, caughtUp.raw)
        if (deviceId === deviceAId) v3Cursor = caughtUp.payload.nextCursor
      }
      const authorityHeaders = {
        accept: AUTHORITY_MEDIA_TYPE,
        authorization: `Capability ${worker.authorityCapability}`,
        'x-poyi-authority-audience': TEST_AUTHORITY_AUDIENCE,
      }
      const first = await requestJson(worker.baseUrl, path, { headers: authorityHeaders })
      assert.equal(first.response.status, 200, first.raw)
      assert.equal(first.response.headers.get('content-type'),
        AUTHORITY_MEDIA_TYPE)
      assert.equal(validateAuthorityObservation(first.payload), true,
        contractAjv.errorsText(validateAuthorityObservation.errors, { separator: '\n' }))
      assert.deepEqual(Object.keys(first.payload).sort(),
        ['audience', 'expiresAt', 'observedAt', 'productId', 'schemaVersion', 'truth'])
      assert.deepEqual(Object.keys(first.payload.truth).sort(),
        ['blockerReason', 'freshness', 'lastVerifiedAt', 'pcOff', 'pendingCount', 'revision'])
      assert.equal(first.payload.productId, 'watch')
      assert.equal(first.payload.audience, TEST_AUTHORITY_AUDIENCE)
      assert.ok(Date.parse(first.payload.expiresAt) > Date.parse(first.payload.observedAt))
      assert.ok(Date.parse(first.payload.truth.lastVerifiedAt) <= Date.parse(first.payload.observedAt))
      assert.equal(first.payload.truth.freshness, 'fresh')
      assert.equal(first.payload.truth.pendingCount, 0)
      assert.equal(first.payload.truth.blockerReason, null)
      assert.deepEqual(first.payload.truth.pcOff, {
        readAvailable: false,
        writeAvailable: false,
        continuedSync: false,
      })
      assert.doesNotMatch(first.raw,
        /deviceId|checkpoint|cursor|envelope|ciphertext|nonce|route|latitude|longitude|heartRate|sleep|credential|token/i)
      assert.doesNotMatch(first.raw, new RegExp(worker.authorityCapability, 'g'))
      assert.equal(first.response.headers.has('signature'), false)

      const repeated = await requestJson(worker.baseUrl, path, { headers: authorityHeaders })
      assert.equal(repeated.response.status, 200, repeated.raw)
      assert.deepEqual(repeated.payload, first.payload)
      const observationHash = (raw) => createHash('sha256').update(raw).digest('hex')
      assert.equal(observationHash(repeated.raw), observationHash(first.raw))

      const extraField = structuredClone(first.payload)
      extraField.truth.workout = { steps: 1 }
      assert.equal(validateAuthorityObservation(extraField), false)
      const expired = structuredClone(first.payload)
      expired.expiresAt = expired.observedAt
      assert.equal(Date.parse(expired.expiresAt) > Date.parse(expired.observedAt), false)

      const writeToken = oauth.token({ scope: 'watch:write' }).value
      const advanced = mcpToolPayload(await callMcpTool(
        worker.baseUrl, writeToken, 'watch_upsert_plan_group', {
          requestId: uuid(3390), operationId: uuid(3391), expectedRevision: v3PlanRevision,
          group: { id: 'group-cloud', name: 'Cloud plans updated', sortOrder: 0 },
        },
      ))
      assert.equal(advanced.outcome, 'acknowledged')
      v3PlanRevision = advanced.revision
      const second = await requestJson(worker.baseUrl, path, { headers: authorityHeaders })
      assert.equal(second.response.status, 200, second.raw)
      assert.ok(second.payload.truth.revision > first.payload.truth.revision)
      assert.ok(second.payload.truth.pendingCount > 0)
      assert.equal(second.payload.truth.freshness, 'blocked')
      assert.equal(second.payload.truth.blockerReason, 'device_catchup_pending')
      assert.equal(second.payload.truth.pcOff.continuedSync, false)

      for (const [deviceId, token] of [[deviceAId, deviceAToken], [deviceBId, deviceBToken]]) {
        const caughtUp = await requestJson(worker.baseUrl, '/sync/v3/exchange', {
          method: 'POST', token, body: v3Envelope(deviceId, { cursor: null }),
        })
        assert.equal(caughtUp.response.status, 200, caughtUp.raw)
      }
      await worker.stop()
      await worker.query(`
        UPDATE watch_v3_device_state
        SET last_exchange_at = '2026-07-28T00:00:00.000Z', updated_at = '2026-07-28T00:00:00.000Z'
        WHERE owner_id = 'poyi-owner';
      `)
      await worker.restart()
      const stale = await requestJson(worker.baseUrl, path, { headers: authorityHeaders })
      assert.equal(stale.response.status, 200, stale.raw)
      assert.equal(stale.payload.truth.freshness, 'stale')
      assert.equal(stale.payload.truth.pendingCount, 0)
      assert.equal(stale.payload.truth.blockerReason, null)
    })

    await t.test('only the exact canonical envelope and mutation keys are accepted', async () => {
      let nextRejectedId = 1000
      const rejectedMutation = (entityId, extra = {}) => {
        const opId = uuid(nextRejectedId++)
        rejectedOperationIds.push(opId)
        return {
          ...mutation({ opId, entityId, payload: planPayload(entityId, 'must be rejected') }),
          ...extra,
        }
      }

      const topLevelCases = [
        { operations: [] },
        { credentialId: 'obsolete-credential' },
        { token: 'must-never-be-in-body' },
        { arbitraryExtra: true },
      ]
      for (const [index, extra] of topLevelCases.entries()) {
        const body = {
          ...envelope(deviceAId, [rejectedMutation(`rejected-top-${index}`)]),
          ...extra,
        }
        const result = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
          method: 'POST',
          token: deviceAToken,
          body,
        })
        assertInvalidExchange(result)
      }

      const formerWire = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
        method: 'POST',
        token: deviceAToken,
        body: {
          protocolVersion: 1,
          deviceId: deviceAId,
          cursor: null,
          credentialId: 'obsolete-credential',
          operations: [rejectedMutation('rejected-former-wire')],
        },
      })
      assertInvalidExchange(formerWire)

      for (const [index, extra] of [
        { credentialId: 'obsolete' },
        { operationId: 'obsolete' },
        { token: 'body-secret' },
        { arbitraryExtra: true },
      ].entries()) {
        const result = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
          method: 'POST',
          token: deviceAToken,
          body: envelope(deviceAId, [rejectedMutation(`rejected-mutation-${index}`, extra)]),
        })
        assertInvalidExchange(result)
      }
    })

    await t.test('nested raw telemetry, coordinates, sleep and inline base64 are rejected without partial writes', async () => {
      const forbiddenPayloadFragments = [
        { telemetry: { route: [{ x: 1, y: 2 }] } },
        { telemetry: { latitude: 31.2304 } },
        { telemetry: { longitude: 121.4737 } },
        { telemetry: { heartRateSamples: [120, 122] } },
        { telemetry: { sleep: { stages: ['deep'] } } },
        { attachment: { base64: 'AAECAw==' } },
        { attachment: { uri: 'data:application/octet-stream;base64,AAECAw==' } },
      ]
      for (const [index, fragment] of forbiddenPayloadFragments.entries()) {
        const opId = uuid(1100 + index)
        rejectedOperationIds.push(opId)
        const entityId = `rejected-raw-${index}`
        const payload = { ...planPayload(entityId, 'raw must be rejected'), nested: { level: { ...fragment } } }
        const result = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
          method: 'POST',
          token: deviceAToken,
          body: envelope(deviceAId, [mutation({
            opId,
            entityId,
            payload,
          })]),
        })
        assertInvalidExchange(result)
      }

      for (const [index, requirement] of [
        'data:application/octet-stream;base64,AAECAw==',
        'A'.repeat(128),
        'base64url_payload-'.repeat(12),
      ].entries()) {
        const entityId = `rejected-inline-${index}`
        const opId = uuid(1110 + index)
        rejectedOperationIds.push(opId)
        const result = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
          method: 'POST',
          token: deviceAToken,
          body: envelope(deviceAId, [mutation({
            opId,
            entityId,
            payload: { ...planPayload(entityId, 'inline must be rejected'), requirement },
          })]),
        })
        assertInvalidExchange(result)
      }

      for (const [index, entityType] of ['route', 'heart', 'sleep', 'raw_route'].entries()) {
        const opId = uuid(1120 + index)
        rejectedOperationIds.push(opId)
        const result = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
          method: 'POST',
          token: deviceAToken,
          body: envelope(deviceAId, [mutation({
            opId,
            entityType,
            entityId: `rejected-type-${index}`,
            payload: { summary: true },
          })]),
        })
        assertInvalidExchange(result)
      }

      const objectRefCases = [
        ({ encryption: _encryption, ...withoutEncryption }) => withoutEncryption,
        (ref) => ({ ...ref, contentType: 'application/octet-stream' }),
        (ref) => ({ ...ref, key: '../raw-route.ndjson' }),
        (ref) => ({ ...ref, encryption: { algorithm: 'none', keyId: 'bad' } }),
      ]
      for (const [index, mutateRef] of objectRefCases.entries()) {
        const entityId = `rejected-ref-${index}`
        const opId = uuid(1130 + index)
        rejectedOperationIds.push(opId)
        const payload = {
          ...workoutPayload(entityId),
          detailRefs: { route: mutateRef(encryptedRouteRef(entityId)) },
        }
        const result = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
          method: 'POST',
          token: deviceAToken,
          body: envelope(deviceAId, [mutation({
            opId,
            entityType: 'workout',
            entityId,
            payload,
          })]),
        })
        assertInvalidExchange(result)
      }
    })

    await t.test('invalid and ahead cursors reject before applying mutations', async () => {
      for (const [index, cursor] of ['not-a-cursor', 'c1'].entries()) {
        const opId = uuid(1150 + index)
        rejectedOperationIds.push(opId)
        const result = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
          method: 'POST',
          token: deviceAToken,
          body: envelope(deviceAId, [mutation({
            opId,
            entityId: `rejected-cursor-${index}`,
            payload: planPayload(`rejected-cursor-${index}`, 'cursor rejection'),
          })], cursor),
        })
        assert.equal(result.response.status, index === 0 ? 400 : 409, result.raw)
        assert.match(result.payload?.error ?? '', index === 0 ? /invalid_(?:exchange|cursor)/i : /cursor_ahead/i)
      }
    })

    let cursorA = 'c0'
    let planRevisionOne
    await t.test('canonical fixture creates a plan and exact opId replay is stable', async () => {
      const initial = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
        method: 'POST',
        token: deviceAToken,
        body: { ...FIXTURE.request, deviceId: deviceAId, mutations: [FIXTURE.request.mutations[0]] },
      })
      assert.equal(initial.response.status, 200, initial.raw)
      assert.equal(
        validateContract(initial.payload),
        true,
        contractAjv.errorsText(validateContract.errors, { separator: '\n' }),
      )
      assert.deepEqual(Object.keys(initial.payload).sort(), Object.keys(FIXTURE.response).sort())
      assert.equal(initial.payload.protocolVersion, 1)
      assert.equal(initial.payload.authority, 'remote_authoritative')
      assert.deepEqual(initial.payload.acknowledged, [FIXTURE.response.acknowledged[0]])
      assert.deepEqual(initial.payload.conflicts, [])
      assert.equal(initial.payload.changes.length, 1)
      assert.equal(initial.payload.changes[0].entityType, 'plan')
      assert.equal(initial.payload.changes[0].sequence, 1)
      assert.equal(initial.payload.changes[0].entityId, FIXTURE.request.mutations[0].entityId)
      assert.equal(initial.payload.changes[0].revision, 1)
      assert.deepEqual(initial.payload.changes[0].payload, FIXTURE.request.mutations[0].payload)
      assert.match(initial.payload.nextCursor, /^c[0-9a-z]+$/)
      cursorA = initial.payload.nextCursor
      planRevisionOne = initial.payload.changes[0]
      expectedChangeCount += 1

      const replay = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
        method: 'POST',
        token: deviceAToken,
        body: envelope(deviceAId, [FIXTURE.request.mutations[0]], cursorA),
      })
      assert.equal(replay.response.status, 200, replay.raw)
      assert.deepEqual(replay.payload.acknowledged, [{
        ...FIXTURE.response.acknowledged[0],
        replayed: true,
      }])
      assert.deepEqual(replay.payload.changes, [])
      assert.equal(replay.payload.nextCursor, cursorA)
    })

    await t.test('same-device changed-body and cross-device opId reuse are conflicts, never replays', async () => {
      const changedBody = {
        ...FIXTURE.request.mutations[0],
        payload: planPayload(FIXTURE.request.mutations[0].entityId, 'different body under same opId'),
      }
      const sameDevice = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
        method: 'POST',
        token: deviceAToken,
        body: envelope(deviceAId, [changedBody], cursorA),
      })
      assertConflict(sameDevice, /(?:OPERATION|OP)_?ID_?(?:REUSED|COLLISION)/i)

      const crossDevice = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
        method: 'POST',
        token: deviceBToken,
        body: envelope(deviceBId, [FIXTURE.request.mutations[0]], null),
      })
      assertConflict(crossDevice, /(?:OPERATION|OP)_?ID_?(?:REUSED|COLLISION)/i)
    })

    let planRevisionTwo
    await t.test('plan OCC returns current + candidate and replays the stored conflict stably', async () => {
      const staleCandidate = planPayload(FIXTURE.request.mutations[0].entityId, 'stale candidate preserved')
      const staleMutation = mutation({
        opId: uuid(2000),
        entityId: FIXTURE.request.mutations[0].entityId,
        baseRevision: 0,
        payload: staleCandidate,
      })
      const stale = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
        method: 'POST',
        token: deviceBToken,
        body: envelope(deviceBId, [staleMutation], cursorA),
      })
      const conflict = assertConflict(stale, /REVISION_CONFLICT/i)
      assert.equal(conflict.entityType, 'plan')
      assert.equal(conflict.current.entityType, 'plan')
      assert.equal(conflict.current.entityId, planRevisionOne.entityId)
      assert.equal(conflict.current.revision, 1)
      assert.deepEqual(conflict.current.payload, planRevisionOne.payload)
      assert.deepEqual(conflict.candidate, staleCandidate)
      assert.equal(conflict.preserveCandidate, true)
      assert.match(conflict.conflictId, /^[0-9a-f-]{36}$/i)

      const staleReplay = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
        method: 'POST',
        token: deviceBToken,
        body: envelope(deviceBId, [staleMutation], cursorA),
      })
      const replayConflict = assertConflict(staleReplay, /REVISION_CONFLICT/i)
      assert.deepEqual(replayConflict, conflict)

      const updatedPayload = planPayload(FIXTURE.request.mutations[0].entityId, 'authoritative revision two')
      const update = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
        method: 'POST',
        token: deviceAToken,
        body: envelope(deviceAId, [mutation({
          opId: uuid(2001),
          entityId: FIXTURE.request.mutations[0].entityId,
          baseRevision: 1,
          payload: updatedPayload,
        })], cursorA),
      })
      assert.equal(update.response.status, 200, update.raw)
      assert.equal(update.payload.acknowledged[0].revision, 2)
      assert.equal(update.payload.changes.length, 1)
      assert.equal(update.payload.changes[0].revision, 2)
      assert.deepEqual(update.payload.changes[0].payload, updatedPayload)
      planRevisionTwo = update.payload.changes[0]
      cursorA = update.payload.nextCursor
      expectedChangeCount += 1
    })

    await t.test('workout summary is immutable for update and delete', async () => {
      const workoutId = 'workout-contract-001'
      const createMutation = mutation({
        opId: uuid(3000),
        entityType: 'workout',
        entityId: workoutId,
        payload: workoutPayload(workoutId),
      })
      const created = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
        method: 'POST',
        token: deviceAToken,
        body: envelope(deviceAId, [createMutation], cursorA),
      })
      assert.equal(created.response.status, 200, created.raw)
      assert.equal(created.payload.acknowledged[0].revision, 1)
      assert.equal(created.payload.changes[0].entityType, 'workout')
      const immutableCurrent = created.payload.changes[0]
      cursorA = created.payload.nextCursor
      expectedChangeCount += 1

      const replay = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
        method: 'POST',
        token: deviceAToken,
        body: envelope(deviceAId, [createMutation], cursorA),
      })
      assert.equal(replay.response.status, 200, replay.raw)
      assert.deepEqual(replay.payload.acknowledged, [{
        ...created.payload.acknowledged[0],
        replayed: true,
      }])

      const changedWorkout = workoutPayload(workoutId, 1900)
      const update = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
        method: 'POST',
        token: deviceAToken,
        body: envelope(deviceAId, [mutation({
          opId: uuid(3001),
          entityType: 'workout',
          entityId: workoutId,
          baseRevision: 1,
          payload: changedWorkout,
        })], cursorA),
      })
      const updateConflict = assertConflict(update, /IMMUTABLE/i)
      assert.equal(updateConflict.entityType, 'workout')
      assert.equal(updateConflict.current.entityType, 'workout')
      assert.equal(updateConflict.current.entityId, immutableCurrent.entityId)
      assert.equal(updateConflict.current.revision, 1)
      assert.deepEqual(updateConflict.current.payload, immutableCurrent.payload)
      assert.deepEqual(updateConflict.candidate, changedWorkout)

      const deletion = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
        method: 'POST',
        token: deviceAToken,
        body: envelope(deviceAId, [mutation({
          opId: uuid(3002),
          entityType: 'workout',
          entityId: workoutId,
          baseRevision: 1,
          operation: 'delete',
        })], cursorA),
      })
      const deleteConflict = assertConflict(deletion, /IMMUTABLE/i)
      assert.equal(deleteConflict.current.entityId, immutableCurrent.entityId)
      assert.equal(deleteConflict.current.revision, 1)
      assert.equal(deleteConflict.candidate, null)
    })

    await t.test('plan deletion emits a tombstone and never mutates the preserved prior state', async () => {
      const deletion = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
        method: 'POST',
        token: deviceAToken,
        body: envelope(deviceAId, [mutation({
          opId: uuid(4000),
          entityId: FIXTURE.request.mutations[0].entityId,
          baseRevision: 2,
          operation: 'delete',
        })], cursorA),
      })
      assert.equal(deletion.response.status, 200, deletion.raw)
      assert.equal(deletion.payload.acknowledged[0].revision, 3)
      assert.equal(deletion.payload.changes.length, 1)
      assert.equal(deletion.payload.changes[0].entityType, 'plan')
      assert.equal(deletion.payload.changes[0].entityId, planRevisionTwo.entityId)
      assert.equal(deletion.payload.changes[0].revision, 3)
      assert.equal(deletion.payload.changes[0].operation, 'delete')
      assert.equal(deletion.payload.changes[0].payload, null)
      cursorA = deletion.payload.nextCursor
      expectedChangeCount += 1
    })

    await t.test('bounded change pages resume exactly from nextCursor', async () => {
      const bulkCount = 105
      for (let start = 0; start < bulkCount; start += 25) {
        const count = Math.min(25, bulkCount - start)
        const mutations = Array.from({ length: count }, (_, offset) => {
          const number = start + offset
          return mutation({
            opId: uuid(5000 + number),
            entityId: `bulk-plan-${String(number).padStart(3, '0')}`,
            payload: planPayload(`bulk-plan-${String(number).padStart(3, '0')}`, `Bulk plan ${number}`, String(number)),
          })
        })
        const push = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
          method: 'POST',
          token: deviceAToken,
          body: envelope(deviceAId, mutations, cursorA),
        })
        assert.equal(push.response.status, 200, push.raw)
        assert.equal(push.payload.acknowledged.length, count)
        assert.equal(push.payload.conflicts.length, 0)
        assert.ok(push.payload.changes.length <= 100)
        cursorA = push.payload.nextCursor
        expectedChangeCount += count
      }

      let cursor = null
      const collected = []
      let pages = 0
      while (true) {
        const page = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
          method: 'POST',
          token: deviceCToken,
          body: envelope(deviceCId, [], cursor),
        })
        assert.equal(page.response.status, 200, page.raw)
        assert.ok(page.payload.changes.length <= 100)
        assert.match(page.payload.nextCursor, /^c[0-9a-z]+$/)
        if (cursor !== null) assert.notEqual(page.payload.nextCursor, cursor, 'hasMore page must advance its cursor')
        collected.push(...page.payload.changes)
        pages += 1
        if (!page.payload.hasMore) {
          cursor = page.payload.nextCursor
          break
        }
        assert.equal(page.payload.changes.length, 100)
        cursor = page.payload.nextCursor
        assert.ok(pages < 10, 'pagination did not converge')
      }
      assert.ok(pages >= 2, 'more than 100 changes must require more than one bounded page')
      assert.equal(collected.length, expectedChangeCount)
      assert.equal(new Set(collected.map((change) => `${change.entityType}:${change.entityId}:${change.revision}:${change.operation}`)).size, expectedChangeCount)
      assert.equal(collected.find((change) => change.entityId === FIXTURE.request.mutations[0].entityId && change.revision === 3)?.operation, 'delete')

      const exhausted = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
        method: 'POST',
        token: deviceCToken,
        body: envelope(deviceCId, [], cursor),
      })
      assert.equal(exhausted.response.status, 200, exhausted.raw)
      assert.deepEqual(exhausted.payload.changes, [])
      assert.equal(exhausted.payload.nextCursor, cursor)
      assert.equal(exhausted.payload.hasMore, false)
    })

    await t.test('revocation invalidates the current device token', async () => {
      const revoked = await requestJson(worker.baseUrl, `/sync/v1/devices/${deviceAId}`, {
        method: 'DELETE',
        token: TEST_SYNC_KEY,
      })
      assert.equal(revoked.response.status, 200, revoked.raw)

      const rejected = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
        method: 'POST',
        token: deviceAToken,
        body: envelope(deviceAId, [], cursorA),
      })
      assertUnauthorized(rejected)
    })

    await worker.stop()
    await t.test('rejected requests left no operation, entity or change row and raw device tokens are not stored', async () => {
      const rejectedIds = rejectedOperationIds.map(sqlLiteral).join(', ')
      const rawTokens = issuedTokens.map(sqlLiteral).join(', ')
      const rows = await worker.query(`
        SELECT
          (SELECT COUNT(*) FROM sync_operations WHERE op_id IN (${rejectedIds})) AS rejected_operations,
          (SELECT COUNT(*) FROM watch_entities WHERE entity_id LIKE 'rejected-%') AS rejected_entities,
          (SELECT COUNT(*) FROM watch_changes WHERE entity_id LIKE 'rejected-%') AS rejected_changes,
          (SELECT COUNT(*) FROM sync_devices WHERE token_hash IN (${rawTokens})) AS raw_device_tokens,
          (SELECT COUNT(*) FROM sync_operations WHERE result_json IS NOT NULL AND completed_at IS NOT NULL) AS completed_operations,
          (SELECT COUNT(*) FROM plan_conflicts WHERE candidate_json IS NOT NULL) AS preserved_conflicts,
          (SELECT COUNT(*) FROM watch_entities WHERE entity_type = 'plan' AND entity_id = ${sqlLiteral(FIXTURE.request.mutations[0].entityId)} AND deleted = 1 AND payload_json IS NULL) AS tombstones
      `)
      assert.equal(rows.length, 1)
      assert.deepEqual({
        rejected_operations: Number(rows[0].rejected_operations),
        rejected_entities: Number(rows[0].rejected_entities),
        rejected_changes: Number(rows[0].rejected_changes),
        raw_device_tokens: Number(rows[0].raw_device_tokens),
      }, {
        rejected_operations: 0,
        rejected_entities: 0,
        rejected_changes: 0,
        raw_device_tokens: 0,
      })
      assert.ok(Number(rows[0].completed_operations) >= expectedChangeCount + 3)
      assert.equal(Number(rows[0].preserved_conflicts), 1)
      assert.equal(Number(rows[0].tombstones), 1)
    })

    await t.test('persisted D1 changes survive a Worker process restart and remain pullable', async () => {
      await worker.restart()
      const pulled = await requestJson(worker.baseUrl, '/sync/v1/exchange', {
        method: 'POST',
        token: deviceCToken,
        body: envelope(deviceCId, [], null),
      })
      assert.equal(pulled.response.status, 200, pulled.raw)
      assert.ok(pulled.payload.changes.length > 0)
      assert.ok(pulled.payload.changes.some((change) =>
        change.entityId === FIXTURE.request.mutations[0].entityId
        && change.operation === 'delete'
        && change.payload === null))
      await worker.stop()
    })
  } finally {
    await worker.dispose()
  }

  await t.test('/readyz and V3 exchange fail closed for missing or malformed revision domains', async () => {
    const invalidDomains = [
      null,
      '',
      'v3d.short',
      'v3d.invalid!owner',
      `v3d.${'a'.repeat(65)}`,
    ]
    for (const revisionDomainId of invalidDomains) {
      const unconfigured = await startIsolatedWorker({ oauth, revisionDomainId })
      try {
        const ready = await requestJson(unconfigured.baseUrl, '/readyz')
        assert.equal(ready.response.status, 503, `${revisionDomainId}: ${ready.raw}`)
        assert.equal(ready.payload.revisionDomain, 'unavailable')
        const exchange = await requestJson(unconfigured.baseUrl, '/sync/v3/exchange', {
          method: 'POST', body: v3Envelope('unconfigured-device'),
        })
        assert.equal(exchange.response.status, 503, `${revisionDomainId}: ${exchange.raw}`)
        assert.equal(exchange.payload.error, 'revision_domain_not_configured')
      } finally {
        await unconfigured.dispose()
      }
    }
  })

  await t.test('/readyz fails when the required D1 schema is absent', async () => {
    const uninitialized = await startIsolatedWorker({ applySchema: false, oauth })
    try {
      const ready = await requestJson(uninitialized.baseUrl, '/readyz')
      assert.equal(ready.response.status, 503, ready.raw)
      assert.equal(ready.payload.ok, false)
      assert.equal(ready.payload.storage, 'unavailable')
    } finally {
      await uninitialized.dispose()
    }
  })

  await t.test('readiness exposes missing or cross-role authority capability without leaking it', async () => {
    const missing = await startIsolatedWorker({ oauth, authorityCapability: null })
    try {
      const ready = await requestJson(missing.baseUrl, '/readyz')
      assert.equal(ready.response.status, 503, ready.raw)
      assert.equal(ready.payload.authorityObservation, 'unavailable')
    } finally {
      await missing.dispose()
    }

    const reusedCapability = `wao_${randomBytes(32).toString('base64url')}`
    const reused = await startIsolatedWorker({
      oauth,
      syncKey: reusedCapability,
      authorityCapability: reusedCapability,
    })
    try {
      const ready = await requestJson(reused.baseUrl, '/readyz')
      assert.equal(ready.response.status, 503, ready.raw)
      assert.equal(ready.payload.authorityObservation, 'unavailable')
      assert.doesNotMatch(ready.raw, new RegExp(reusedCapability, 'g'))
    } finally {
      await reused.dispose()
    }
  })

  await t.test('D1 infrastructure faults return retryable 503 and never harden into OCC conflicts', async () => {
    const deviceId = 'watch-fault-device'
    const token = `dw1.${deviceId}.${'f'.repeat(43)}`
    const tokenHash = createHash('sha256').update(token).digest('hex')
    const opId = uuid(9000)
    const faulted = await startIsolatedWorker({
      oauth,
      setupSql: `
        INSERT INTO sync_devices (device_id, label, token_hash, created_at)
        VALUES (${sqlLiteral(deviceId)}, 'Fault probe', ${sqlLiteral(tokenHash)}, '2026-07-28T00:00:00.000Z');
        DROP TABLE watch_entities;
      `,
    })
    try {
      const result = await requestJson(faulted.baseUrl, '/sync/v1/exchange', {
        method: 'POST',
        token,
        body: envelope(deviceId, [mutation({
          opId,
          entityId: 'fault-plan',
          payload: planPayload('fault-plan', 'Must remain retryable'),
        })]),
      })
      assert.equal(result.response.status, 503, result.raw)
      assert.equal(result.payload.error, 'sync_temporarily_unavailable')
      assert.equal(result.payload.retryable, true)
      assert.deepEqual(result.payload.conflicts, undefined)
      await faulted.stop()
      const rows = await faulted.query(
        `SELECT result_json, completed_at FROM sync_operations WHERE op_id = ${sqlLiteral(opId)}`,
      )
      assert.equal(rows.length, 1)
      assert.equal(rows[0].result_json, null)
      assert.equal(rows[0].completed_at, null)
    } finally {
      await faulted.dispose()
    }
  })

  await t.test('OAuth metadata and MCP fail closed when the authorization server is not configured', async () => {
    const unconfigured = await startIsolatedWorker()
    try {
      for (const path of ['/.well-known/oauth-protected-resource/mcp', '/.well-known/oauth-protected-resource']) {
        const metadata = await requestJson(unconfigured.baseUrl, path)
        assert.equal(metadata.response.status, 503, `${path}: ${metadata.raw}`)
        assert.match(metadata.payload?.error ?? '', /oauth_(?:not_configured|unavailable)/)
      }
      const mcp = await initializeMcp(unconfigured.baseUrl, STATIC_MCP_KEY)
      assert.equal(mcp.status, 503)
    } finally {
      await unconfigured.dispose()
    }
  })

  await t.test('configured OAuth metadata and MCP fail closed while the authorization server is unavailable', async () => {
    const dependencyFailure = await startIsolatedWorker({ oauth })
    try {
      const warm = await initializeMcp(dependencyFailure.baseUrl, oauth.token().value)
      assert.equal(warm.status, 200, await warm.text())
      oauth.setAvailable(false)
      const metadata = await requestJson(dependencyFailure.baseUrl, '/.well-known/oauth-protected-resource/mcp')
      assert.equal(metadata.response.status, 503, metadata.raw)
      assert.equal(metadata.payload?.error, 'authorization_server_unavailable')
      const mcp = await initializeMcp(dependencyFailure.baseUrl, oauth.token().value)
      assert.equal(mcp.status, 503)
    } finally {
      oauth.setAvailable(true)
      await dependencyFailure.dispose()
    }
  })

  await t.test('MCP and readiness fail closed when OAuth and sync admin secrets are reused', async () => {
    const reusedSecret = await startIsolatedWorker({
      oauth,
      syncKey: TEST_SYNC_KEY,
      rsClientSecret: TEST_SYNC_KEY,
    })
    try {
      const ready = await requestJson(reusedSecret.baseUrl, '/readyz')
      assert.equal(ready.response.status, 503, ready.raw)
      assert.equal(ready.payload.oauth, 'unavailable')
      const mcp = await initializeMcp(reusedSecret.baseUrl, oauth.token().value)
      const body = await mcp.text()
      assert.equal(mcp.status, 503, body)
      assert.match(body, /oauth_secret_boundary_invalid/)
    } finally {
      await reusedSecret.dispose()
    }
  })

  await t.test('client_secret_basic rejects a wrong resource-server secret without token fallback', async () => {
    const wrongBasic = await startIsolatedWorker({
      oauth,
      rsClientSecret: 'wrong-watch-resource-secret-0000000000000000',
    })
    try {
      const ready = await requestJson(wrongBasic.baseUrl, '/readyz')
      assert.equal(ready.response.status, 503, ready.raw)
      assert.equal(ready.payload.oauth, 'unavailable')
      const mcp = await initializeMcp(wrongBasic.baseUrl, oauth.token().value)
      const body = await mcp.text()
      assert.equal(mcp.status, 503, body)
      assert.match(body, /authorization_server_unavailable/)
    } finally {
      await wrongBasic.dispose()
    }
  })

  await t.test('readiness fails when JWKS or introspection is partially unavailable', async () => {
    const partial = await startIsolatedWorker({ oauth })
    try {
      oauth.setJwksAvailable(false)
      let ready = await requestJson(partial.baseUrl, '/readyz')
      assert.equal(ready.response.status, 503, ready.raw)
      assert.equal(ready.payload.oauth, 'unavailable')
      oauth.setJwksAvailable(true)

      oauth.setIntrospectionAvailable(false)
      ready = await requestJson(partial.baseUrl, '/readyz')
      assert.equal(ready.response.status, 503, ready.raw)
      assert.equal(ready.payload.oauth, 'unavailable')
      oauth.setIntrospectionAvailable(true)

      oauth.setScopes([
        'watch:read', 'watch:write', 'watch:control', 'offline_access',
      ])
      ready = await requestJson(partial.baseUrl, '/readyz')
      assert.equal(ready.response.status, 200, ready.raw)
      const offlineMetadata = await requestJson(partial.baseUrl, '/.well-known/oauth-protected-resource/mcp')
      assert.equal(offlineMetadata.response.status, 200, offlineMetadata.raw)

      oauth.setScopes([
        'journal:read', 'journal:write', 'focuslink:read', 'watch:read',
        'gateway:read', 'watch:write', 'watch:control',
      ])
      ready = await requestJson(partial.baseUrl, '/readyz')
      assert.equal(ready.response.status, 200, ready.raw)

      oauth.setScopes(['journal:read', 'journal:write', 'focuslink:read', 'gateway:read'])
      ready = await requestJson(partial.baseUrl, '/readyz')
      assert.equal(ready.response.status, 503, ready.raw)
      const metadata = await requestJson(partial.baseUrl, '/.well-known/oauth-protected-resource/mcp')
      assert.equal(metadata.response.status, 503, metadata.raw)
    } finally {
      oauth.setJwksAvailable(true)
      oauth.setIntrospectionAvailable(true)
      oauth.setScopes([
        'journal:read', 'journal:write', 'focuslink:read',
        'watch:read', 'watch:write', 'watch:control',
      ])
      await partial.dispose()
    }
  })
})
