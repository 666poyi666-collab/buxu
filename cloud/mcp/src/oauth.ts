import {
  createRemoteJWKSet,
  customFetch,
  errors as joseErrors,
  jwtVerify,
  type JWTPayload,
  type JWTVerifyResult,
} from 'jose'
import type { AuthInfo } from '@modelcontextprotocol/server'

export const WATCH_READ_SCOPE = 'watch:read'
export const WATCH_WRITE_SCOPE = 'watch:write'
export const WATCH_CONTROL_SCOPE = 'watch:control'
export const WATCH_SCOPES = [WATCH_READ_SCOPE, WATCH_WRITE_SCOPE, WATCH_CONTROL_SCOPE] as const
export const WATCH_TOOL_SECURITY_SCHEMES = [
  { type: 'oauth2' as const, scopes: [...WATCH_SCOPES] },
]

export function watchToolSecuritySchemes(scope: typeof WATCH_SCOPES[number]) {
  return [{ type: 'oauth2' as const, scopes: [scope] }]
}

const MAX_CLOCK_SKEW_SECONDS = 60
const MAX_ACCESS_TOKEN_LIFETIME_SECONDS = 300
const PRODUCTION_ISSUER = 'https://poyi-oauth-as.focuslink-poyi-6465e9.workers.dev'
const PRODUCTION_AUDIENCE = 'https://watch-mcp.focuslink-poyi-6465e9.workers.dev/mcp'
const STAGING_ISSUER = 'https://poyi-oauth-as-staging.focuslink-poyi-6465e9.workers.dev'
const STAGING_AUDIENCE = 'https://watch-mcp-staging.focuslink-poyi-6465e9.workers.dev/mcp'
// The shared authorization server owns several products, but Watch readiness must depend only on
// the scopes this resource server consumes. Tightening Journal or FocusLink policy must not take
// the independent Watch MCP offline.
const AUTHORIZATION_SERVER_SCOPES = [...WATCH_SCOPES] as const

export interface OAuthEnv {
  OAUTH_ISSUER?: string
  OAUTH_AUDIENCE?: string
  OAUTH_JWKS_URL?: string
  OAUTH_AS_METADATA_URL?: string
  OAUTH_INTROSPECTION_URL?: string
  OAUTH_RS_CLIENT_ID?: string
  OAUTH_RS_CLIENT_SECRET?: string
  OAUTH_HTTP?: { fetch: Fetcher }
}

type Fetcher = typeof fetch

/**
 * Resolve the fetcher used for OAuth authorization-server probes.
 *
 * When the OAUTH_HTTP service binding is present, prefer it so metadata,
 * JWKS, and introspection calls travel over Cloudflare's internal network
 * instead of looping back through the public workers.dev URL, which can be
 * unstable from inside another Worker. Falls back to the global fetch when
 * the binding is absent (local tests, non-Cloudflare environments).
 */
export function oauthFetcher(env: OAuthEnv): Fetcher {
  const binding = env.OAUTH_HTTP
  if (binding && typeof binding.fetch === 'function') {
    return (input, init) => binding.fetch(input, init)
  }
  return fetch
}

type OAuthConfig = {
  issuer: string
  audience: string
  jwksUrl: string
  asMetadataUrl: string
  introspectionUrl: string
  rsClientId: string
  rsClientSecret: string
}

type IntrospectionPayload = {
  active: true
  token_type: 'Bearer'
  iss: string
  sub: string
  aud: string | string[]
  resource: string
  scope: string
  client_id: string
  iat: number
  exp: number
  jti: string
}

type IntrospectionResult = 'active' | 'inactive' | 'unavailable'

export type OAuthFailureCode =
  | 'oauth_not_configured'
  | 'missing_token'
  | 'invalid_token'
  | 'insufficient_scope'
  | 'authorization_server_unavailable'

export type OAuthResult =
  | { ok: true; authInfo: AuthInfo; claims: JWTPayload }
  | { ok: false; code: OAuthFailureCode; description: string }

const productionJwks = new Map<string, ReturnType<typeof createRemoteJWKSet>>()

function exactHttpsUrl(value: string | undefined): string | null {
  if (!value) return null
  try {
    const url = new URL(value)
    if (url.protocol !== 'https:' && url.hostname !== '127.0.0.1' && url.hostname !== 'localhost') {
      return null
    }
    if (url.hash || url.username || url.password) return null
    return url.href.replace(/\/$/, '')
  } catch {
    return null
  }
}

export function oauthConfig(env: OAuthEnv): OAuthConfig | null {
  const issuer = exactHttpsUrl(env.OAUTH_ISSUER)
  const audience = exactHttpsUrl(env.OAUTH_AUDIENCE)
  const jwksUrl = exactHttpsUrl(env.OAUTH_JWKS_URL)
  const asMetadataUrl = exactHttpsUrl(env.OAUTH_AS_METADATA_URL)
  const introspectionUrl = exactHttpsUrl(env.OAUTH_INTROSPECTION_URL)
  const rsClientId = env.OAUTH_RS_CLIENT_ID?.trim()
  const rsClientSecret = env.OAUTH_RS_CLIENT_SECRET?.trim()
  if (
    !issuer || !audience || !jwksUrl || !asMetadataUrl || !introspectionUrl
    || !rsClientId || !rsClientSecret || rsClientId.includes(':')
    || rsClientId.length > 128
    || new TextEncoder().encode(rsClientSecret).byteLength < 32
    || new TextEncoder().encode(rsClientSecret).byteLength > 512
  ) {
    return null
  }
  if (!audience.endsWith('/mcp')) return null
  const local = new URL(issuer).hostname === '127.0.0.1' || new URL(issuer).hostname === 'localhost'
  const production = issuer === PRODUCTION_ISSUER && audience === PRODUCTION_AUDIENCE
  const staging = issuer === STAGING_ISSUER && audience === STAGING_AUDIENCE
  if (!local && !production && !staging) return null
  if (jwksUrl !== `${issuer}/jwks.json`) return null
  if (asMetadataUrl !== `${issuer}/.well-known/oauth-authorization-server`) return null
  if (introspectionUrl !== `${issuer}/introspect`) return null
  return { issuer, audience, jwksUrl, asMetadataUrl, introspectionUrl, rsClientId, rsClientSecret }
}

export function extractBearer(request: Request): string | null {
  const value = request.headers.get('authorization')
  if (!value) return null
  const match = /^Bearer ([A-Za-z0-9._~-]+)$/.exec(value)
  return match?.[1] ?? null
}

function scopes(value: unknown): string[] {
  if (typeof value !== 'string') return []
  return [...new Set(value.split(' ').filter(Boolean))]
}

function sameAudience(value: unknown, audience: string): boolean {
  if (typeof value === 'string') return value === audience
  return Array.isArray(value) && value.length === 1 && value[0] === audience
}

function isIntegerClaim(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value)
}

function validateProfile(
  verified: JWTVerifyResult,
  config: OAuthConfig,
  nowSeconds: number,
  requiredScope: string | readonly string[],
): OAuthResult {
  const { payload, protectedHeader } = verified
  if (protectedHeader.alg !== 'RS256' || protectedHeader.typ !== 'at+jwt') {
    return { ok: false, code: 'invalid_token', description: 'The access token header is invalid.' }
  }
  if (typeof protectedHeader.kid !== 'string' || protectedHeader.kid.length < 1) {
    return { ok: false, code: 'invalid_token', description: 'The access token kid is missing.' }
  }
  if (
    payload.iss !== config.issuer
    || !sameAudience(payload.aud, config.audience)
    || payload.resource !== config.audience
    || payload.sub !== 'poyi-owner'
    || payload.token_use !== 'access_token'
    || typeof payload.client_id !== 'string'
    || payload.client_id.length < 1
    || typeof payload.jti !== 'string'
    || payload.jti.length < 1
    || !isIntegerClaim(payload.iat)
    || !isIntegerClaim(payload.exp)
    || payload.exp <= payload.iat
    || payload.exp - payload.iat > MAX_ACCESS_TOKEN_LIFETIME_SECONDS
    || payload.iat > nowSeconds + MAX_CLOCK_SKEW_SECONDS
    || payload.exp <= nowSeconds - MAX_CLOCK_SKEW_SECONDS
  ) {
    return { ok: false, code: 'invalid_token', description: 'The access token claims are invalid.' }
  }
  const grantedScopes = scopes(payload.scope)
  const acceptedScopes = typeof requiredScope === 'string' ? [requiredScope] : [...requiredScope]
  if (!acceptedScopes.some((scope) => grantedScopes.includes(scope))) {
    return {
      ok: false,
      code: 'insufficient_scope',
      description: `One of ${acceptedScopes.join(', ')} is required.`,
    }
  }
  return {
    ok: true,
    claims: payload,
    authInfo: {
      token: '',
      clientId: payload.client_id,
      scopes: grantedScopes,
      expiresAt: payload.exp,
      resource: new URL(config.audience),
      extra: { issuer: payload.iss, subject: payload.sub, jti: payload.jti },
    },
  }
}

function remoteJwks(config: OAuthConfig, fetcher: Fetcher) {
  if (fetcher !== fetch) {
    return createRemoteJWKSet(new URL(config.jwksUrl), { [customFetch]: fetcher })
  }
  const cached = productionJwks.get(config.jwksUrl)
  if (cached) return cached
  const resolver = createRemoteJWKSet(new URL(config.jwksUrl), {
    cooldownDuration: 30_000,
    cacheMaxAge: 300_000,
    timeoutDuration: 5_000,
  })
  productionJwks.set(config.jwksUrl, resolver)
  return resolver
}

function isIntrospectionPayload(value: unknown): value is IntrospectionPayload {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const body = value as Record<string, unknown>
  return body.active === true
    && body.token_type === 'Bearer'
    && typeof body.iss === 'string'
    && typeof body.sub === 'string'
    && (typeof body.aud === 'string' || Array.isArray(body.aud))
    && typeof body.resource === 'string'
    && typeof body.scope === 'string'
    && typeof body.client_id === 'string'
    && isIntegerClaim(body.iat)
    && isIntegerClaim(body.exp)
    && typeof body.jti === 'string'
}

async function introspect(
  token: string,
  config: OAuthConfig,
  claims: JWTPayload,
  fetcher: Fetcher,
): Promise<IntrospectionResult> {
  const basic = resourceServerBasic(config)
  let response: Response
  try {
    response = await fetcher(config.introspectionUrl, {
      method: 'POST',
      headers: {
        Authorization: `Basic ${basic}`,
        'Content-Type': 'application/x-www-form-urlencoded',
        Accept: 'application/json',
      },
      body: new URLSearchParams({ token, token_type_hint: 'access_token' }).toString(),
      signal: AbortSignal.timeout(5_000),
    })
  } catch {
    return 'unavailable'
  }
  if (response.status !== 200) return 'unavailable'
  let body: unknown
  try {
    body = await response.json()
  } catch {
    return 'unavailable'
  }
  if (isRecordWithInactive(body)) return 'inactive'
  if (!isIntrospectionPayload(body)) return 'unavailable'
  const matches = body.iss === claims.iss
    && body.sub === claims.sub
    && sameAudience(body.aud, config.audience)
    && body.resource === config.audience
    && body.scope === claims.scope
    && body.client_id === claims.client_id
    && body.iat === claims.iat
    && body.exp === claims.exp
    && body.jti === claims.jti
  return matches ? 'active' : 'unavailable'
}

function resourceServerBasic(config: OAuthConfig): string {
  return utf8Base64(`${config.rsClientId}:${config.rsClientSecret}`)
}

function utf8Base64(value: string): string {
  const bytes = new TextEncoder().encode(value)
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary)
}

function isRecordWithInactive(value: unknown): boolean {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
    && Object.keys(value as Record<string, unknown>).length === 1
    && (value as Record<string, unknown>).active === false
}

export async function verifyMcpRequest(
  request: Request,
  env: OAuthEnv,
  requiredScope: string | readonly string[] = WATCH_SCOPES,
  fetcher: Fetcher = fetch,
  nowSeconds = Math.floor(Date.now() / 1000),
): Promise<OAuthResult> {
  const config = oauthConfig(env)
  if (!config) {
    return { ok: false, code: 'oauth_not_configured', description: 'OAuth is not configured.' }
  }
  const token = extractBearer(request)
  if (!token) {
    return { ok: false, code: 'missing_token', description: 'A Bearer access token is required.' }
  }
  let verified: JWTVerifyResult
  try {
    verified = await jwtVerify(token, remoteJwks(config, fetcher), {
      algorithms: ['RS256'],
      issuer: config.issuer,
      audience: config.audience,
      clockTolerance: MAX_CLOCK_SKEW_SECONDS,
      currentDate: new Date(nowSeconds * 1000),
    })
  } catch (error) {
    const unavailable = error instanceof TypeError
      || error instanceof joseErrors.JWKSTimeout
      || error instanceof joseErrors.JOSEError
        && (error.code === 'ERR_JWKS_TIMEOUT' || error.code === 'ERR_JOSE_GENERIC')
    return {
      ok: false,
      code: unavailable ? 'authorization_server_unavailable' : 'invalid_token',
      description: unavailable ? 'The authorization server is unavailable.' : 'The access token is invalid.',
    }
  }
  const profile = validateProfile(verified, config, nowSeconds, requiredScope)
  if (!profile.ok) return profile
  const active = await introspect(token, config, profile.claims, fetcher)
  if (active === 'unavailable') {
    return {
      ok: false,
      code: 'authorization_server_unavailable',
      description: 'The authorization server could not confirm token status.',
    }
  }
  if (active === 'inactive') {
    return { ok: false, code: 'invalid_token', description: 'The access token is inactive or revoked.' }
  }
  profile.authInfo.token = token
  return profile
}

export async function verifyAuthorizationServer(
  env: OAuthEnv,
  fetcher: Fetcher = fetch,
): Promise<boolean> {
  const config = oauthConfig(env)
  if (!config) return false
  let response: Response
  try {
    response = await fetcher(config.asMetadataUrl, {
      headers: { Accept: 'application/json' },
      signal: AbortSignal.timeout(5_000),
    })
  } catch {
    return false
  }
  if (response.status !== 200) return false
  try {
    const metadata = await response.json() as Record<string, unknown>
    const advertisedScopes = Array.isArray(metadata.scopes_supported)
      && metadata.scopes_supported.every((scope) => typeof scope === 'string')
      ? metadata.scopes_supported as string[]
      : []
    const expectedScopes = [...AUTHORIZATION_SERVER_SCOPES]
    // Shared AS advertises scopes for all products; verify required scopes are a subset
    const acceptedScopes =
      expectedScopes.every((scope) => advertisedScopes.includes(scope))
    return metadata.issuer === config.issuer
      && metadata.jwks_uri === config.jwksUrl
      && metadata.introspection_endpoint === config.introspectionUrl
      && metadata.authorization_endpoint === `${config.issuer}/authorize`
      && metadata.token_endpoint === `${config.issuer}/token`
      && Array.isArray(metadata.code_challenge_methods_supported)
      && metadata.code_challenge_methods_supported.length === 1
      && metadata.code_challenge_methods_supported[0] === 'S256'
      && Array.isArray(metadata.introspection_endpoint_auth_methods_supported)
      && metadata.introspection_endpoint_auth_methods_supported.length === 1
      && metadata.introspection_endpoint_auth_methods_supported[0] === 'client_secret_basic'
      && acceptedScopes
  } catch {
    return false
  }
}

function isPublicRs256Jwk(value: unknown): boolean {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const jwk = value as Record<string, unknown>
  const privateFields = ['d', 'p', 'q', 'dp', 'dq', 'qi', 'oth']
  if (privateFields.some((field) => field in jwk)) return false
  if (
    jwk.kty !== 'RSA' || jwk.alg !== 'RS256' || jwk.use !== 'sig'
    || typeof jwk.kid !== 'string' || jwk.kid.length < 1
    || typeof jwk.n !== 'string' || typeof jwk.e !== 'string'
  ) return false
  try {
    const normalized = jwk.n.replaceAll('-', '+').replaceAll('_', '/')
    const padded = normalized + '='.repeat((4 - normalized.length % 4) % 4)
    return atob(padded).length >= 256
  } catch {
    return false
  }
}

export async function verifyOAuthDependencies(
  env: OAuthEnv,
  fetcher: Fetcher = fetch,
): Promise<boolean> {
  const config = oauthConfig(env)
  if (!config || !await verifyAuthorizationServer(env, fetcher)) return false
  try {
    const jwksResponse = await fetcher(config.jwksUrl, {
      headers: { Accept: 'application/json' },
      signal: AbortSignal.timeout(5_000),
    })
    if (jwksResponse.status !== 200) return false
    const jwks = await jwksResponse.json() as Record<string, unknown>
    if (!Array.isArray(jwks.keys) || jwks.keys.length < 1 || !jwks.keys.every(isPublicRs256Jwk)) return false
    const kids = jwks.keys.map((key) => (key as Record<string, unknown>).kid)
    if (new Set(kids).size !== kids.length) return false

    const introspection = await fetcher(config.introspectionUrl, {
      method: 'POST',
      headers: {
        Authorization: `Basic ${resourceServerBasic(config)}`,
        'Content-Type': 'application/x-www-form-urlencoded',
        Accept: 'application/json',
      },
      body: new URLSearchParams({
        token: 'watch-readiness-probe.invalid',
        token_type_hint: 'access_token',
      }).toString(),
      signal: AbortSignal.timeout(5_000),
    })
    if (introspection.status !== 200) return false
    const inactive = await introspection.json()
    return isRecordWithInactive(inactive)
  } catch {
    return false
  }
}

function quoteChallenge(value: string): string {
  return value.replaceAll('\\', '\\\\').replaceAll('"', '\\"')
}

export function oauthChallenge(
  request: Request,
  failure: Extract<OAuthResult, { ok: false }>,
): string {
  const resourceMetadata = `${new URL(request.url).origin}/.well-known/oauth-protected-resource/mcp`
  const error = failure.code === 'insufficient_scope' ? 'insufficient_scope' : 'invalid_token'
  return `Bearer resource_metadata="${quoteChallenge(resourceMetadata)}", error="${error}", error_description="${quoteChallenge(failure.description)}"`
}

export function oauthHttpError(
  request: Request,
  failure: Extract<OAuthResult, { ok: false }>,
): Response {
  const status = failure.code === 'oauth_not_configured'
    || failure.code === 'authorization_server_unavailable'
    ? 503
    : failure.code === 'insufficient_scope' ? 403 : 401
  const headers = new Headers({ 'Cache-Control': 'no-store' })
  if (status === 401 || status === 403) headers.set('WWW-Authenticate', oauthChallenge(request, failure))
  return Response.json({ error: failure.code, error_description: failure.description }, { status, headers })
}

export function oauthToolError(request: Request, description = 'Authentication is required.') {
  const failure = { ok: false as const, code: 'missing_token' as const, description }
  return {
    content: [{ type: 'text' as const, text: description }],
    _meta: { 'mcp/www_authenticate': [oauthChallenge(request, failure)] },
    isError: true,
  }
}

export async function protectedResourceMetadata(
  request: Request,
  env: OAuthEnv,
  fetcher: Fetcher = fetch,
): Promise<Response> {
  const config = oauthConfig(env)
  if (!config) {
    return Response.json(
      { error: 'oauth_not_configured' },
      { status: 503, headers: { 'Cache-Control': 'no-store' } },
    )
  }
  if (!await verifyOAuthDependencies(env, fetcher)) {
    return Response.json(
      { error: 'authorization_server_unavailable' },
      { status: 503, headers: { 'Cache-Control': 'no-store' } },
    )
  }
  return Response.json({
    resource: config.audience,
    authorization_servers: [config.issuer],
    scopes_supported: [...WATCH_SCOPES],
    bearer_methods_supported: ['header'],
    resource_name: 'Watch Cloud MCP',
  }, { headers: { 'Cache-Control': 'public, max-age=60' } })
}
