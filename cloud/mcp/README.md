# Watch Cloud MCP

Watch Cloud MCP is the Cloud V3 authority and OAuth MCP surface. Device Bearer
Tokens can call `/sync/v3/exchange` and `/sync/v3/channel`; `/mcp` accepts only
issuer/audience/scope/introspection-verified OAuth access tokens with
`watch:read`, `watch:write`, or `watch:control`. V3 stores complete plan
libraries, workout summaries/splits/aggregate heart rates, sleep details, live
status, and commands. Raw routes, coordinates, per-sample heart rates, pairing
material, and tokens are rejected. V2 remains migration-only and is not called
by Phone 0.23.0.

Every successful V3 exchange response carries `revisionDomainId`. It is configured as a
non-secret, owner/library-scoped `v3d.*` identifier and must differ between
staging and production. All devices in one authority receive the same value;
Android uses it to keep plan revision watermarks out of device-id, legacy, and
other-authority domains. Missing or malformed configuration fails exchange and
readiness closed.

## Two-hop authority observation

The internal observation is consumed by the **central signing authority**, not
by Gateway or the final MCP client:

```text
watch-cloud-mcp canonical D1
  -> WATCH_OBSERVATION service binding
  -> central signing authority /authority/watch
  -> signed authority document
  -> Gateway /mcp signature verification
```

- Method/path: `GET /_internal/v1/authority-observation`
- Consumer binding name: `WATCH_OBSERVATION`
- Accept header: `Accept: application/vnd.poyi.authority-observation.v1+json`
- Capability header: `Authorization: Capability <WATCH_AUTHORITY_CAPABILITY>`
- Exact audience header: `X-Poyi-Authority-Audience: <WATCH_AUTHORITY_AUDIENCE>`
- Audience shape: `https://<central-authority-host>/authority/watch` (the concrete value is configured outside Git)
- Schema: `contracts/watch-authority-observation-v1.schema.json`

`WATCH_AUTHORITY_CAPABILITY` is a distinct `wao_` secret configured with
Wrangler secret management. It cannot equal the sync admin or OAuth resource
server secret. `WATCH_AUTHORITY_AUDIENCE` must be an HTTPS resource whose exact
path is `/authority/watch`; a Gateway `/mcp` audience fails readiness and request
authorization.

The response has the exact top-level fields `schemaVersion`, `productId`,
`audience`, `observedAt`, `expiresAt`, and `truth`. `truth` has exactly
`revision`, `freshness`, `lastVerifiedAt`, `pendingCount`, `blockerReason`, and
`pcOff`. Its revision comes from a D1 authority checkpoint advanced only by real
sync, device, projection, operation, audit, or revocation transitions. The first
observation for a revision is stored immutably; repeated GETs for that revision
therefore produce identical truth and time fields and the central authority
computes the same `observationHash`. An expired stored observation fails closed
until a real authority transition advances the checkpoint.
Device IDs, ciphertext, nonces, health values, routes, coordinates, credentials,
and tokens are forbidden by the checked-in JSON Schema and black-box tests.

`/readyz` is ready only when the D1 schema, OAuth dependencies, plan revision
domain, and independent authority capability/audience boundary are all configured. `/healthz` remains a
sanitized liveness endpoint and reports `BUILD_COMMIT` only when it is an exact
lowercase 40-character Git SHA; otherwise it reports `unknown`.

## D1 authority migration order

Apply `0005_authority_observation.sql` with `wrangler d1 migrations apply`, then
immediately apply `ops/authority-checkpoint-triggers.sql` with
`wrangler d1 execute --remote --file`. Cloudflare's migration query endpoint
combines migration SQL with its bookkeeping statement and rejects compound
trigger bodies; the file-import endpoint is the supported atomic path for this
idempotent schema adjunct. Deployment verification must prove that migration
`0005_authority_observation.sql`, both authority tables, and all ten
`encrypted_sync_authority_checkpoint_*` triggers exist before the Worker is
promoted.

Staging and production deployment remain gated by migration, secret, OAuth,
revision-domain, and signed-authority integration evidence. PC is not in the
production runtime; Android Doze/reboot evidence is tracked separately. Local
green tests do not turn the legacy `supportsPcOff` field into background proof.

## Staging MCP acceptance gate

The repository includes a read-only, non-empty staging probe for the exact
remote path ChatGPT uses. It verifies protected-resource metadata, readiness,
MCP 2026-07-28 `server/discover`, `tools/list`, and the actual status, plan, workout, activity
summary, and sync-overview tool calls. It fails when the device-uploaded read
projection is empty, so an OAuth handshake cannot be mistaken for usable sync.

Pass a short-lived `watch:read` access token through the environment; the
script never prints it, registers an OAuth client, or writes authorization
server state:

```powershell
$env:WATCH_OAUTH_ACCESS_TOKEN = '<short-lived-watch-read-token>'
$env:WATCH_EXPECTED_BUILD_COMMIT = (git rev-parse HEAD)
npm run test:staging:mcp
Remove-Item Env:WATCH_OAUTH_ACCESS_TOKEN
```

This proves the cloud read path is independent of the Windows Watch MCP and
tunnel. It does not by itself prove Phone/Watch background upload, reboot, or
Doze recovery; those remain real-device gates in the Android repository.
