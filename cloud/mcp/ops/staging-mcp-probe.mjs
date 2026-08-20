import assert from 'node:assert/strict'

const DEFAULT_MCP_URL = 'https://watch-mcp-staging.focuslink-poyi-6465e9.workers.dev/mcp'
const EXPECTED_TOOLS = [
  'watch_get_latest_sleep',
  'watch_get_status',
  'watch_get_sync_overview',
  'watch_list_plans',
  'watch_list_workouts',
  'watch_summarize_sleep',
  'watch_summarize_workouts',
]

function requiredToken() {
  const value = process.env.WATCH_OAUTH_ACCESS_TOKEN?.trim() ?? ''
  assert.match(value, /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/, 'WATCH_OAUTH_ACCESS_TOKEN must be an OAuth JWT')
  return value
}

function endpoint() {
  const value = process.env.WATCH_MCP_URL?.trim() || DEFAULT_MCP_URL
  const url = new URL(value)
  assert.equal(url.protocol, 'https:', 'WATCH_MCP_URL must use HTTPS')
  assert.equal(url.pathname, '/mcp', 'WATCH_MCP_URL must be the exact /mcp resource')
  assert.equal(url.search, '', 'WATCH_MCP_URL must not contain a query')
  assert.equal(url.hash, '', 'WATCH_MCP_URL must not contain a fragment')
  assert.notEqual(url.hostname, 'localhost', 'the staging proof cannot use a local MCP server')
  assert.notEqual(url.hostname, '127.0.0.1', 'the staging proof cannot use a local MCP server')
  return url
}

async function request(url, init = {}) {
  const response = await fetch(url, { ...init, signal: AbortSignal.timeout(20_000) })
  const raw = await response.text()
  return { response, raw }
}

function jsonBody(raw) {
  try {
    return JSON.parse(raw)
  } catch {
    const messages = raw.split(/\r?\n/)
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trim())
      .filter((line) => line && line !== '[DONE]')
    for (let index = messages.length - 1; index >= 0; index--) {
      try {
        return JSON.parse(messages[index])
      } catch {
        // Keep looking for the last JSON-RPC event in an SSE response.
      }
    }
    throw new Error(`response was not JSON or MCP SSE (${raw.slice(0, 160)})`)
  }
}

function toolPayload(message) {
  assert.equal(message?.jsonrpc, '2.0')
  assert.equal(message?.error, undefined, JSON.stringify(message?.error))
  const item = message?.result?.content?.find((entry) => entry?.type === 'text')
  assert.equal(typeof item?.text, 'string', 'tool response did not contain text content')
  return JSON.parse(item.text)
}

async function main() {
  const token = requiredToken()
  const mcp = endpoint()
  const base = new URL('/', mcp)
  const commonHeaders = {
    accept: 'application/json, text/event-stream',
    authorization: `Bearer ${token}`,
    'content-type': 'application/json',
  }

  const healthResult = await request(new URL('/healthz', base))
  const health = jsonBody(healthResult.raw)
  assert.equal(healthResult.response.status, 200, healthResult.raw)
  assert.equal(health.service, 'watch-cloud-mcp')
  assert.match(health.buildCommit, /^[0-9a-f]{40}$/, 'staging must attest an exact build commit')
  if (process.env.WATCH_EXPECTED_BUILD_COMMIT) {
    assert.equal(health.buildCommit, process.env.WATCH_EXPECTED_BUILD_COMMIT.trim())
  }

  const readyResult = await request(new URL('/readyz', base))
  const ready = jsonBody(readyResult.raw)
  assert.equal(readyResult.response.status, 200, readyResult.raw)
  assert.deepEqual(
    { ready: ready.ready, storage: ready.storage, oauth: ready.oauth, authorityObservation: ready.authorityObservation },
    { ready: true, storage: 'ready', oauth: 'ready', authorityObservation: 'ready' },
  )

  const resourceResult = await request(new URL('/.well-known/oauth-protected-resource/mcp', base))
  const resource = jsonBody(resourceResult.raw)
  assert.equal(resourceResult.response.status, 200, resourceResult.raw)
  assert.equal(resource.resource, mcp.href)
  assert.deepEqual(resource.scopes_supported, ['watch:read'])
  assert.equal(resource.authorization_servers?.length, 1)

  let nextId = 1
  async function rpc(method, params = {}) {
    const id = nextId++
    const name = typeof params.name === 'string' ? params.name : null
    const result = await request(mcp, {
      method: 'POST',
      headers: {
        ...commonHeaders,
        'mcp-protocol-version': '2026-07-28',
        'mcp-method': method,
        ...(name ? { 'mcp-name': name } : {}),
      },
      body: JSON.stringify({
        jsonrpc: '2.0',
        id,
        method,
        params: {
          ...params,
          _meta: {
            'io.modelcontextprotocol/protocolVersion': '2026-07-28',
            'io.modelcontextprotocol/clientInfo': {
              name: 'watch-staging-cloud-read-probe',
              version: '2.0.0',
            },
            'io.modelcontextprotocol/clientCapabilities': {},
          },
        },
      }),
    })
    assert.equal(result.response.status, 200, result.raw)
    const message = jsonBody(result.raw)
    assert.equal(message?.id, id)
    assert.equal(message?.error, undefined, JSON.stringify(message?.error))
    return message
  }

  const discovered = await rpc('server/discover')
  assert.ok(discovered.result.supportedVersions.includes('2026-07-28'))

  const listed = await rpc('tools/list')
  const tools = listed.result.tools.map((tool) => tool.name).sort()
  assert.deepEqual(tools, EXPECTED_TOOLS)
  for (const tool of listed.result.tools) {
    const schemes = tool.securitySchemes ?? tool._meta?.securitySchemes
    assert.equal(schemes?.[0]?.type, 'oauth2')
    assert.deepEqual(schemes?.[0]?.scopes, ['watch:read'])
  }

  async function call(name) {
    return toolPayload(await rpc('tools/call', { name, arguments: {} }))
  }

  const status = await call('watch_get_status')
  const plans = await call('watch_list_plans')
  const workouts = await call('watch_list_workouts')
  const summary = await call('watch_summarize_workouts')
  const overview = await call('watch_get_sync_overview')

  assert.equal(plans.authority, 'device_uploaded_read_projection')
  assert.ok(Array.isArray(plans.plans) && plans.plans.length > 0, 'staging has no actual plan projection')
  assert.equal(workouts.authority, 'device_uploaded_read_projection')
  assert.ok(Array.isArray(workouts.workouts) && workouts.workouts.length > 0, 'staging has no actual workout projection')
  assert.equal(summary.authority, 'device_uploaded_read_projection')
  assert.ok(summary.count > 0, 'staging workout summary is empty')
  assert.ok(Number.isSafeInteger(summary.totalDurationMs) && summary.totalDurationMs > 0, 'staging has no positive workout duration')
  assert.ok(Number.isSafeInteger(summary.totalSteps) && summary.totalSteps >= 0, 'staging totalSteps is invalid')
  assert.equal(overview.readProjection.planCount, plans.plans.length)
  assert.equal(overview.readProjection.workoutCount, workouts.workouts.length)
  assert.equal(status.readProjection.planCount, plans.plans.length)
  assert.equal(status.readProjection.workoutCount, workouts.workouts.length)

  console.log(JSON.stringify({
    ok: true,
    endpoint: mcp.href,
    buildCommit: health.buildCommit,
    oauthScope: 'watch:read',
    toolCount: tools.length,
    planCount: plans.plans.length,
    workoutCount: workouts.workouts.length,
    totalDurationMs: summary.totalDurationMs,
    totalSteps: summary.totalSteps,
    lastProjectionSyncAt: overview.readProjection.lastSyncedAt,
  }, null, 2))
}

main().catch((error) => {
  console.error(`WATCH_STAGING_MCP_PROBE_FAILED: ${error.message}`)
  process.exitCode = 1
})
