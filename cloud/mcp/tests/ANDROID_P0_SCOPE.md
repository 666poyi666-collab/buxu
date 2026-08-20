# Android P0 scope

This repository contains the Cloudflare Worker and its D1 schema. It contains
no Android module, `AndroidManifest.xml`, `CloudSyncCredentials`, or
`CloudSyncEngine` implementation.

The tests in this repository therefore enforce only the client-independent P0
boundary:

- the canonical request has exactly `protocolVersion`, `deviceId`, `cursor`,
  and `mutations`;
- a mutation has exactly `opId`, `entityType`, `entityId`, `baseRevision`,
  `operation`, and `payload`;
- bearer credentials never appear in request bodies or fixtures;
- the Worker rejects the former `operations` / `credentialId` wire shape;
- device-token rotation and revocation invalidate old tokens at the server.

The MCP bearer is separately verified as an issuer/audience/scope-bound signed
OAuth access token. A static bearer secret is not an accepted MCP credential.
Device tokens can replace only their own exact-field read projection during a
successful encrypted `/sync/v2/exchange`. OAuth MCP reads expose actual plan
names, coarse workout rows, encrypted-sync status, and an activity-only health
summary (count, duration, distance, and steps). Routes, coordinates, per-sample
heart data, sleep, credentials, and unknown fields are rejected before D1 and
revalidated before every MCP response.

The Worker also exposes a service-binding-only authority observation at
`GET /_internal/v1/authority-observation`. Its consumer is the central signing
authority, bound as `WATCH_OBSERVATION`; Gateway `/mcp` is the second hop and
never calls this route. Calls require the exact vendor `Accept`,
`Authorization: Capability <wao_*>`, and the exact configured
`X-Poyi-Authority-Audience` ending in
`/authority/watch`. Device bearer, OAuth bearer, missing/wrong capability,
Gateway audience, wrong authority audience, and non-GET requests are negative
contract cases. The v1 response schema forbids device IDs, ciphertext, nonces,
routes, health values, sleep, credentials, tokens, and extra fields. Its
monotonic authority checkpoint and pending/blocker/pcOff facts are computed from
canonical D1 rows and the projection materialization receipt; one revision has
immutable truth and timestamps, so its central-authority observationHash is
stable.

Keystore round trips, Android credential rotation persistence, backup/data
extraction rules, and release-manifest cleartext checks must be implemented and
run in the Android client repository. A static test fails if Android artifacts
are later added here without replacing this explicit absence gate.
