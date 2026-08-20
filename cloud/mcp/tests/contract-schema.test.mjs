import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import Ajv2020 from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'

const schema = JSON.parse(await readFile(new URL('../contracts/watch-sync-v1.schema.json', import.meta.url), 'utf8'))
const fixture = JSON.parse(await readFile(new URL('../contracts/watch-sync-v1.fixture.json', import.meta.url), 'utf8'))
const observationSchema = JSON.parse(await readFile(
  new URL('../contracts/watch-authority-observation-v1.schema.json', import.meta.url), 'utf8',
))
const ajv = new Ajv2020({ allErrors: true, strict: false })
addFormats(ajv)
const validate = ajv.compile(schema)
const validateObservation = ajv.compile(observationSchema)

function clone(value) {
  return structuredClone(value)
}

function assertValid(value) {
  assert.equal(validate(value), true, ajv.errorsText(validate.errors, { separator: '\n' }))
}

function assertInvalid(value) {
  assert.equal(validate(value), false, 'expected the canonical JSON Schema to reject the value')
}

test('canonical request and response fixtures validate against the checked-in schema', () => {
  assertValid(fixture.request)
  assertValid(fixture.response)
})

test('schema rejects former wire fields, non-client enums and non-canonical payload keys', () => {
  const formerWire = clone(fixture.request)
  formerWire.operations = formerWire.mutations
  assertInvalid(formerWire)

  const lowerCaseStage = clone(fixture.request)
  lowerCaseStage.mutations[0].payload.stages[0].kind = 'run'
  assertInvalid(lowerCaseStage)

  const extraPayload = clone(fixture.request)
  extraPayload.mutations[0].payload.nested = { latitude: 31.23 }
  assertInvalid(extraPayload)
})

test('schema rejects inline base64url and unsafe or unencrypted object references', () => {
  const base64url = clone(fixture.request)
  base64url.mutations[0].payload.requirement = 'base64url_payload-'.repeat(12)
  assertInvalid(base64url)

  const unencrypted = clone(fixture.request)
  delete unencrypted.mutations[1].payload.detailRefs.route.encryption
  assertInvalid(unencrypted)

  const traversal = clone(fixture.request)
  traversal.mutations[1].payload.detailRefs.route.key = 'route/../secret.ndjson'
  assertInvalid(traversal)

  const wrongMime = clone(fixture.request)
  wrongMime.mutations[1].payload.detailRefs.route.contentType = 'application/octet-stream'
  assertInvalid(wrongMime)

  const foldedBase64 = clone(fixture.request)
  foldedBase64.mutations[0].payload.requirement = `${'QUJD'.repeat(32).match(/.{1,16}/g).join('\n')}`
  assertInvalid(foldedBase64)
})

test('schema enforces operation/payload and response audit-field consistency', () => {
  const deleteWithPayload = clone(fixture.request)
  deleteWithPayload.mutations[0].operation = 'delete'
  assertInvalid(deleteWithPayload)

  const invalidCursor = clone(fixture.request)
  invalidCursor.cursor = 'not-an-opaque-cursor'
  assertInvalid(invalidCursor)

  const missingSequence = clone(fixture.response)
  delete missingSequence.changes[0].sequence
  assertInvalid(missingSequence)

  const oldEntityState = clone(fixture.response)
  oldEntityState.changes[0].changedAt = 123
  assertInvalid(oldEntityState)
})

test('authority observation schema accepts only checkpoint-derived, privacy-minimal truth', () => {
  const observation = {
    schemaVersion: 1,
    productId: 'watch',
    audience: 'https://authority.contract.test/authority/watch',
    observedAt: '2026-07-29T03:00:00.000Z',
    expiresAt: '2026-07-29T03:05:00.000Z',
    truth: {
      revision: 12,
      freshness: 'fresh',
      lastVerifiedAt: '2026-07-29T03:00:00.000Z',
      pendingCount: 0,
      blockerReason: null,
      pcOff: { readAvailable: true, writeAvailable: true, continuedSync: true },
    },
  }
  assert.equal(validateObservation(observation), true,
    ajv.errorsText(validateObservation.errors, { separator: '\n' }))
  for (const forbidden of [
    { observationHash: 'a'.repeat(64) },
    { deviceId: 'watch-secret' },
    { ciphertext: 'encrypted-body' },
    { route: [{ latitude: 1, longitude: 2 }] },
    { health: { heartRate: 120 } },
    { credential: 'secret' },
  ]) {
    assert.equal(validateObservation({ ...observation, ...forbidden }), false)
  }
})
