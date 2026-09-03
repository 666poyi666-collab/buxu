/**
 * Watch Cloud canonical sync v1 and OAuth-protected remote MCP.
 *
 * Plans use optimistic revisions. Workout summaries are create-once facts.
 * Raw routes, coordinates, per-sample heart-rate data, credentials, and base64 series never enter the D1 sync plane.
 */
import { createMcpHandler } from 'agents/mcp/server'
import { McpServer } from '@modelcontextprotocol/server'
import { WorkerEntrypoint } from 'cloudflare:workers'
import { z } from 'zod'
import {
  WATCH_CONTROL_SCOPE,
  WATCH_READ_SCOPE,
  WATCH_SCOPES,
  WATCH_WRITE_SCOPE,
  oauthFetcher,
  oauthHttpError,
  oauthToolError,
  protectedResourceMetadata,
  verifyOAuthDependencies,
  verifyMcpRequest,
  watchToolSecuritySchemes,
  type OAuthEnv,
} from './oauth'
import { routeSync, type SyncEnv } from './sync'
import {
  WatchCommandChannel,
  cloudV3Configured,
  cloudPlans,
  cloudSleepRecords,
  cloudStatus,
  cloudWorkout,
  cloudWorkouts,
  cloudHealthRecords,
  createCommand,
  getCommand,
  replaceCloudPlanLibrary,
  routeCloudV3,
  summarizeCloudSleep,
  summarizeCloudHealth,
  summarizeCloudWorkouts,
  type CloudV3Env,
} from './cloud-v3'
import {
  authorityObservation,
  authorityObservationConfigured,
  WATCH_AUTHORITY_PATH,
  type AuthorityObservationEnv,
} from './authority-observation'

interface Env extends SyncEnv, OAuthEnv, AuthorityObservationEnv, CloudV3Env {
  COMMAND_CHANNEL: DurableObjectNamespace
  BUILD_COMMIT?: string
}

type JsonRecord = Record<string, unknown>

const PROJECT = { name: 'poyi-watch', version: '0.5.0' }
const COMMIT_PATTERN = /^[0-9a-f]{40}$/

type WatchScope = typeof WATCH_SCOPES[number]

const EMPTY_SCHEMA = { type: 'object', properties: {}, additionalProperties: false } as const
const UUID_SCHEMA = { type: 'string', format: 'uuid' } as const
const ID_SCHEMA = {
  type: 'string', minLength: 1, maxLength: 128,
  pattern: '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$',
} as const
const STAGE_SCHEMA = {
  type: 'object',
  properties: {
    kind: { type: 'string', enum: ['RUN', 'WALK', 'REST'] },
    unit: { type: 'string', enum: ['DISTANCE', 'TIME'] },
    target: { type: 'integer', minimum: 1, maximum: 1_000_000 },
  },
  required: ['kind', 'unit', 'target'],
  additionalProperties: false,
} as const
const PLAN_SCHEMA = {
  type: 'object',
  properties: {
    id: ID_SCHEMA,
    name: { type: 'string', minLength: 1, maxLength: 200 },
    groupId: { anyOf: [ID_SCHEMA, { type: 'null' }] },
    requirement: { type: 'string', maxLength: 2_000 },
    sortOrder: { type: 'integer', minimum: 0 },
    stages: {
      type: 'array', minItems: 1, maxItems: 100, items: STAGE_SCHEMA,
    },
  },
  required: ['id', 'name', 'groupId', 'requirement', 'sortOrder', 'stages'],
  additionalProperties: false,
} as const
const COMMON_COMMAND_PROPERTIES = {
  requestId: UUID_SCHEMA,
  commandId: UUID_SCHEMA,
  expectedState: { type: ['string', 'null'] },
  controlRevision: { type: 'integer', minimum: 0 },
} as const
const COMMAND_SCHEMA = {
  type: 'object', properties: COMMON_COMMAND_PROPERTIES,
  required: ['requestId', 'commandId', 'expectedState', 'controlRevision'], additionalProperties: false,
} as const
const PLAN_LIBRARY_ITEM_SCHEMA = {
  type: 'object',
  properties: {
    requestId: UUID_SCHEMA, operationId: UUID_SCHEMA,
    expectedRevision: { type: 'integer', minimum: 0 },
  },
  required: ['requestId', 'operationId', 'expectedRevision'],
  additionalProperties: true,
} as const

const TOOLS = [
  { name: 'watch_get_status', scope: WATCH_READ_SCOPE, description: 'Read cloud-authoritative live state and synchronization freshness.', inputSchema: EMPTY_SCHEMA },
  { name: 'watch_get_sync_overview', scope: WATCH_READ_SCOPE, description: 'Read V3 device, cursor, freshness, and migration state.', inputSchema: EMPTY_SCHEMA },
  { name: 'watch_list_plan_groups', scope: WATCH_READ_SCOPE, description: 'List cloud-authoritative training plan groups.', inputSchema: EMPTY_SCHEMA },
  { name: 'watch_list_plans', scope: WATCH_READ_SCOPE, description: 'List cloud-authoritative plans and selected plan.', inputSchema: EMPTY_SCHEMA },
  { name: 'watch_get_plan', scope: WATCH_READ_SCOPE, description: 'Read one cloud-authoritative plan including every ordered workout stage.', inputSchema: { type: 'object', properties: { planId: ID_SCHEMA }, required: ['planId'], additionalProperties: false } },
  { name: 'watch_list_workouts', scope: WATCH_READ_SCOPE, description: 'List workout summaries; routes and per-sample heart rates remain local-only.', inputSchema: { type: 'object', properties: { limit: { type: 'integer', minimum: 1, maximum: 200 } }, additionalProperties: false } },
  { name: 'watch_get_workout', scope: WATCH_READ_SCOPE, description: 'Read one detailed workout summary, splits, aggregate heart rates, and source summary.', inputSchema: { type: 'object', properties: { workoutId: { type: 'string', minLength: 1, maxLength: 128 } }, required: ['workoutId'], additionalProperties: false } },
  { name: 'watch_summarize_workouts', scope: WATCH_READ_SCOPE, description: 'Summarize workout count, duration, distance, and steps.', inputSchema: EMPTY_SCHEMA },
  { name: 'watch_list_sleep_records', scope: WATCH_READ_SCOPE, description: 'Read sleep records with sessions, stages, score, SpO2, heart and breathing aggregates.', inputSchema: { type: 'object', properties: { limit: { type: 'integer', minimum: 1, maximum: 31 } }, additionalProperties: false } },
  { name: 'watch_get_latest_sleep', scope: WATCH_READ_SCOPE, description: 'Read the latest cloud sleep record.', inputSchema: EMPTY_SCHEMA },
  { name: 'watch_summarize_sleep', scope: WATCH_READ_SCOPE, description: 'Summarize the latest 31 cloud sleep records.', inputSchema: EMPTY_SCHEMA },
  { name: 'watch_list_health_records', scope: WATCH_READ_SCOPE, description: 'Read manufacturer health summary records (steps, activity duration, heart-rate statistics).', inputSchema: { type: 'object', properties: { limit: { type: 'integer', minimum: 1, maximum: 120 } }, additionalProperties: false } },
  { name: 'watch_summarize_health', scope: WATCH_READ_SCOPE, description: 'Summarize health records: per-kind counts, max steps/calories, average resting/heart-rate, maximum heart rate.', inputSchema: EMPTY_SCHEMA },
  { name: 'watch_upsert_plan_group', scope: WATCH_WRITE_SCOPE, description: 'Create or replace a plan group using expected plan-library revision.', inputSchema: { ...PLAN_LIBRARY_ITEM_SCHEMA, properties: { ...PLAN_LIBRARY_ITEM_SCHEMA.properties, group: { type: 'object', properties: { id: { type: 'string' }, name: { type: 'string' }, sortOrder: { type: 'integer', minimum: 0 } }, required: ['id', 'name', 'sortOrder'], additionalProperties: false } }, required: [...PLAN_LIBRARY_ITEM_SCHEMA.required, 'group'], additionalProperties: false } },
  { name: 'watch_delete_plan_group', scope: WATCH_WRITE_SCOPE, description: 'Delete a plan group and all plans in it using expected plan-library revision. This is an explicit cascade delete; other groups and plans are preserved.', inputSchema: { ...PLAN_LIBRARY_ITEM_SCHEMA, properties: { ...PLAN_LIBRARY_ITEM_SCHEMA.properties, groupId: ID_SCHEMA }, required: [...PLAN_LIBRARY_ITEM_SCHEMA.required, 'groupId'], additionalProperties: false } },
  { name: 'watch_upsert_plan', scope: WATCH_WRITE_SCOPE, description: 'Create or replace one plan, including all ordered stages, using expected plan-library revision.', inputSchema: { ...PLAN_LIBRARY_ITEM_SCHEMA, properties: { ...PLAN_LIBRARY_ITEM_SCHEMA.properties, plan: PLAN_SCHEMA }, required: [...PLAN_LIBRARY_ITEM_SCHEMA.required, 'plan'], additionalProperties: false } },
  { name: 'watch_move_plan', scope: WATCH_WRITE_SCOPE, description: 'Move one existing plan to a plan group or ungrouped position without changing its stages.', inputSchema: { ...PLAN_LIBRARY_ITEM_SCHEMA, properties: { ...PLAN_LIBRARY_ITEM_SCHEMA.properties, planId: ID_SCHEMA, groupId: { anyOf: [ID_SCHEMA, { type: 'null' }] }, sortOrder: { type: 'integer', minimum: 0 } }, required: [...PLAN_LIBRARY_ITEM_SCHEMA.required, 'planId', 'groupId', 'sortOrder'], additionalProperties: false } },
  { name: 'watch_replace_plan_stages', scope: WATCH_WRITE_SCOPE, description: 'Replace every ordered stage of one existing daily plan.', inputSchema: { ...PLAN_LIBRARY_ITEM_SCHEMA, properties: { ...PLAN_LIBRARY_ITEM_SCHEMA.properties, planId: ID_SCHEMA, stages: { type: 'array', minItems: 1, maxItems: 100, items: STAGE_SCHEMA } }, required: [...PLAN_LIBRARY_ITEM_SCHEMA.required, 'planId', 'stages'], additionalProperties: false } },
  { name: 'watch_delete_plan', scope: WATCH_WRITE_SCOPE, description: 'Delete exactly one plan by ID using expected plan-library revision. Other plans and groups are unchanged.', inputSchema: { ...PLAN_LIBRARY_ITEM_SCHEMA, properties: { ...PLAN_LIBRARY_ITEM_SCHEMA.properties, planId: ID_SCHEMA }, required: [...PLAN_LIBRARY_ITEM_SCHEMA.required, 'planId'], additionalProperties: false } },
  { name: 'watch_select_plan', scope: WATCH_WRITE_SCOPE, description: 'Select an existing cloud plan, or null to clear selection, using expected plan-library revision.', inputSchema: { ...PLAN_LIBRARY_ITEM_SCHEMA, properties: { ...PLAN_LIBRARY_ITEM_SCHEMA.properties, planId: { anyOf: [ID_SCHEMA, { type: 'null' }] } }, required: [...PLAN_LIBRARY_ITEM_SCHEMA.required, 'planId'], additionalProperties: false } },
  { name: 'watch_delete_workout', scope: WATCH_WRITE_SCOPE, description: 'Request device-confirmed workout deletion; cloud tombstone is written only after Watch ACK.', inputSchema: { ...COMMAND_SCHEMA, properties: { ...COMMON_COMMAND_PROPERTIES, workoutId: { type: 'string' } }, required: [...COMMAND_SCHEMA.required, 'workoutId'] } },
  { name: 'watch_start_workout', scope: WATCH_CONTROL_SCOPE, description: 'Start a workout and wait up to 10 seconds for Watch ACK.', inputSchema: { ...COMMAND_SCHEMA, properties: { ...COMMON_COMMAND_PROPERTIES, planId: { type: ['string', 'null'] } }, required: [...COMMAND_SCHEMA.required, 'planId'] } },
  { name: 'watch_pause_workout', scope: WATCH_CONTROL_SCOPE, description: 'Pause the active workout and wait up to 10 seconds for Watch ACK.', inputSchema: COMMAND_SCHEMA },
  { name: 'watch_resume_workout', scope: WATCH_CONTROL_SCOPE, description: 'Resume the active workout and wait up to 10 seconds for Watch ACK.', inputSchema: COMMAND_SCHEMA },
  { name: 'watch_stop_workout', scope: WATCH_CONTROL_SCOPE, description: 'Stop the active workout and wait up to 10 seconds for Watch ACK.', inputSchema: COMMAND_SCHEMA },
  { name: 'watch_get_command_status', scope: WATCH_CONTROL_SCOPE, description: 'Read pending, delivered, succeeded, failed, or expired command status.', inputSchema: { type: 'object', properties: { commandId: UUID_SCHEMA }, required: ['commandId'], additionalProperties: false } },
] as const

type ToolName = typeof TOOLS[number]['name']

const zId = z.string().min(1).max(128)
  .regex(/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/)
const zUuid = z.string().uuid()
const zStage = z.object({
  kind: z.enum(['RUN', 'WALK', 'REST']),
  unit: z.enum(['DISTANCE', 'TIME']),
  target: z.number().int().min(1).max(1_000_000),
}).strict()
const zPlan = z.object({
  id: zId,
  name: z.string().min(1).max(200),
  groupId: zId.nullable(),
  requirement: z.string().max(2_000),
  sortOrder: z.number().int().nonnegative(),
  stages: z.array(zStage).min(1).max(100),
}).strict()
const zGroup = z.object({
  id: zId,
  name: z.string().min(1).max(200),
  sortOrder: z.number().int().nonnegative(),
}).strict()
const writeMeta = {
  requestId: zUuid,
  operationId: zUuid,
  expectedRevision: z.number().int().nonnegative(),
}
const commandMeta = {
  requestId: zUuid,
  commandId: zUuid,
  expectedState: z.string().nullable(),
  controlRevision: z.number().int().nonnegative(),
}

function toolInputShape(name: ToolName): Record<string, z.ZodType> {
  switch (name) {
    case 'watch_get_status':
    case 'watch_get_sync_overview':
    case 'watch_list_plan_groups':
    case 'watch_list_plans':
    case 'watch_summarize_workouts':
    case 'watch_get_latest_sleep':
    case 'watch_summarize_sleep':
      return {}
    case 'watch_get_plan':
      return { planId: zId }
    case 'watch_list_workouts':
      return { limit: z.number().int().min(1).max(200).optional() }
    case 'watch_get_workout':
      return { workoutId: zId }
    case 'watch_list_sleep_records':
      return { limit: z.number().int().min(1).max(31).optional() }
    case 'watch_list_health_records':
      return { limit: z.number().int().min(1).max(120).optional() }
    case 'watch_summarize_health':
      return {};
    case 'watch_upsert_plan_group':
      return { ...writeMeta, group: zGroup }
    case 'watch_delete_plan_group':
      return { ...writeMeta, groupId: zId }
    case 'watch_upsert_plan':
      return { ...writeMeta, plan: zPlan }
    case 'watch_move_plan':
      return {
        ...writeMeta,
        planId: zId,
        groupId: zId.nullable(),
        sortOrder: z.number().int().nonnegative(),
      }
    case 'watch_replace_plan_stages':
      return { ...writeMeta, planId: zId, stages: z.array(zStage).min(1).max(100) }
    case 'watch_delete_plan':
    case 'watch_select_plan':
      return {
        ...writeMeta,
        planId: name === 'watch_select_plan' ? zId.nullable() : zId,
      }
    case 'watch_delete_workout':
      return { ...commandMeta, workoutId: zId }
    case 'watch_start_workout':
      return { ...commandMeta, planId: zId.nullable() }
    case 'watch_pause_workout':
    case 'watch_resume_workout':
    case 'watch_stop_workout':
      return commandMeta
    case 'watch_get_command_status':
      return { commandId: zUuid }
  }
}

function text(payload: unknown) {
  return { content: [{ type: 'text' as const, text: JSON.stringify(payload, null, 2) }] }
}

function isRecord(value: unknown): value is JsonRecord {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

class WatchMcpServer {
  server = new McpServer({ name: PROJECT.name, version: PROJECT.version })

  constructor(
    private readonly env: Env,
    private readonly scopes: readonly string[],
  ) {
    this.init()
  }

  private authorizationError(scope: WatchScope) {
    const audience = this.env.OAUTH_AUDIENCE ?? 'https://watch-mcp.focuslink-poyi-6465e9.workers.dev/mcp'
    return oauthToolError(new Request(audience), `The ${scope} scope is required.`)
  }

  private authorized(scope: WatchScope): boolean {
    return this.scopes.includes(scope)
  }

  private async execute(name: ToolName, args: JsonRecord): Promise<ReturnType<typeof text>> {
    if (name === 'watch_get_status' || name === 'watch_get_sync_overview') {
      return text(await cloudStatus(this.env.DB))
    }
    if (name === 'watch_list_plan_groups' || name === 'watch_list_plans') {
      const library = await cloudPlans(this.env.DB)
      return text(name === 'watch_list_plan_groups'
        ? { revision: library.revision, groups: library.groups }
        : library)
    }
    if (name === 'watch_get_plan') {
      const library = await cloudPlans(this.env.DB)
      const plan = Array.isArray(library.plans)
        ? library.plans.find((item) => isRecord(item) && item.id === args.planId) ?? null
        : null
      return text({ revision: library.revision, plan })
    }
    if (name === 'watch_list_workouts') return text(await cloudWorkouts(this.env.DB, Number(args.limit ?? 100)))
    if (name === 'watch_get_workout') {
      return text({ workout: await cloudWorkout(this.env.DB, String(args.workoutId ?? '')) })
    }
    if (name === 'watch_summarize_workouts') return text(await summarizeCloudWorkouts(this.env.DB))
    if (name === 'watch_list_sleep_records') return text(await cloudSleepRecords(this.env.DB, Number(args.limit ?? 31)))
    if (name === 'watch_get_latest_sleep') {
      const records = await cloudSleepRecords(this.env.DB, 1)
      return text({ record: Array.isArray(records.records) ? records.records[0] ?? null : null })
    }
    if (name === 'watch_summarize_sleep') return text(await summarizeCloudSleep(this.env.DB))
    if (name === 'watch_list_health_records') return text(await cloudHealthRecords(this.env.DB, Number(args.limit ?? 31)))
    if (name === 'watch_summarize_health') return text(await summarizeCloudHealth(this.env.DB))

    if (name.startsWith('watch_') && [
      'watch_upsert_plan_group', 'watch_delete_plan_group', 'watch_upsert_plan',
      'watch_move_plan', 'watch_replace_plan_stages', 'watch_delete_plan',
      'watch_select_plan',
    ].includes(name)) {
      const current = await cloudPlans(this.env.DB)
      const groups = Array.isArray(current.groups) ? [...current.groups] as JsonRecord[] : []
      const plans = Array.isArray(current.plans) ? [...current.plans] as JsonRecord[] : []
      let selectedPlanId = typeof current.selectedPlanId === 'string' ? current.selectedPlanId : null
      if (name === 'watch_upsert_plan_group') {
        if (!isRecord(args.group)) throw new Error('invalid_group')
        const groupValue = args.group
        const index = groups.findIndex((group) => group.id === groupValue.id)
        if (index >= 0) groups[index] = groupValue
        else groups.push(groupValue)
      } else if (name === 'watch_delete_plan_group') {
        const index = groups.findIndex((group) => group.id === args.groupId)
        if (index < 0) {
          return text({ outcome: 'conflict', error: 'group_not_found', groupId: args.groupId })
        }
        groups.splice(index, 1)
        const removedPlanIds = new Set(
          plans.filter((plan) => plan.groupId === args.groupId).map((plan) => String(plan.id)),
        )
        for (let index = plans.length - 1; index >= 0; index--) {
          if (removedPlanIds.has(String(plans[index].id))) plans.splice(index, 1)
        }
        if (selectedPlanId !== null && removedPlanIds.has(selectedPlanId)) {
          selectedPlanId = plans.length > 0 ? String(plans[0].id) : null
        }
      } else if (name === 'watch_upsert_plan') {
        if (!isRecord(args.plan)) throw new Error('invalid_plan')
        const planValue = args.plan
        if (planValue.groupId !== null
          && !groups.some((group) => group.id === planValue.groupId)) {
          return text({ outcome: 'conflict', error: 'group_not_found', groupId: planValue.groupId })
        }
        const index = plans.findIndex((plan) => plan.id === planValue.id)
        if (index >= 0) plans[index] = planValue
        else plans.push(planValue)
      } else if (name === 'watch_move_plan') {
        const index = plans.findIndex((plan) => plan.id === args.planId)
        if (index < 0) {
          return text({ outcome: 'conflict', error: 'plan_not_found', planId: args.planId })
        }
        if (args.groupId !== null && !groups.some((group) => group.id === args.groupId)) {
          return text({ outcome: 'conflict', error: 'group_not_found', groupId: args.groupId })
        }
        plans[index] = {
          ...plans[index],
          groupId: args.groupId,
          sortOrder: Number(args.sortOrder),
        }
      } else if (name === 'watch_replace_plan_stages') {
        const index = plans.findIndex((plan) => plan.id === args.planId)
        if (index < 0) {
          return text({ outcome: 'conflict', error: 'plan_not_found', planId: args.planId })
        }
        plans[index] = { ...plans[index], stages: args.stages }
      } else if (name === 'watch_delete_plan') {
        const index = plans.findIndex((plan) => plan.id === args.planId)
        if (index < 0) {
          return text({ outcome: 'conflict', error: 'plan_not_found', planId: args.planId })
        }
        plans.splice(index, 1)
        if (selectedPlanId === args.planId) selectedPlanId = null
      } else {
        if (args.planId !== null && !plans.some((plan) => plan.id === args.planId)) {
          return text({ outcome: 'conflict', error: 'plan_not_found', planId: args.planId })
        }
        selectedPlanId = args.planId === null ? null : String(args.planId)
      }
      return text(await replaceCloudPlanLibrary(
        this.env,
        null,
        String(args.operationId ?? ''),
        Number(args.expectedRevision),
        { schemaVersion: 1, selectedPlanId, groups, plans },
      ))
    }

    if (name === 'watch_get_command_status') {
      return text({ command: await getCommand(this.env, String(args.commandId ?? '')) })
    }
    const commandType = name === 'watch_delete_workout' ? 'delete_workout'
      : name === 'watch_start_workout' ? 'start'
        : name === 'watch_pause_workout' ? 'pause'
          : name === 'watch_resume_workout' ? 'resume' : 'stop'
    const commandArgs = commandType === 'delete_workout' ? { workoutId: args.workoutId }
      : commandType === 'start' ? { planId: args.planId } : {}
    return text(await createCommand(this.env, {
      requestId: String(args.requestId ?? ''),
      commandId: String(args.commandId ?? ''),
      type: commandType,
      expectedState: args.expectedState === null ? null : String(args.expectedState ?? ''),
      controlRevision: Number(args.controlRevision),
      arguments: commandArgs,
    }))
  }

  private init(): void {
    for (const definition of TOOLS) {
      this.server.registerTool(
        definition.name,
        {
          description: definition.description,
          inputSchema: toolInputShape(definition.name),
          _meta: { securitySchemes: watchToolSecuritySchemes(definition.scope) },
        },
        async (args) => {
          if (!this.authorized(definition.scope)) return this.authorizationError(definition.scope)
          try {
            return await this.execute(definition.name, args as JsonRecord)
          } catch (caught) {
            return text({ error: caught instanceof Error ? caught.message : 'tool_failed' })
          }
        },
      )
    }

  }
}

function createWatchMcpServer(env: Env, scopes: readonly string[]): McpServer {
  return new WatchMcpServer(env, scopes).server
}

export { WatchCommandChannel }

/** Named entrypoint reachable only by an explicitly configured Cloudflare service binding. */
export class WatchAuthorityObservation extends WorkerEntrypoint<Env> {
  async fetch(request: Request): Promise<Response> {
    return authorityObservation(request, this.env)
  }
}

async function storageReady(env: Env): Promise<boolean> {
  try {
    const row = await env.DB.prepare(
      'SELECT 1 AS ready, (SELECT COUNT(label) + COUNT(last_cursor) FROM sync_devices) AS devices, (SELECT COUNT(result_json) + COUNT(reservation_id) FROM sync_operations) AS operations, (SELECT COUNT(payload_json) + COUNT(last_operation_id) FROM watch_entities) AS entities, (SELECT COUNT(change_seq) + COUNT(operation_id) FROM watch_changes) AS changes, (SELECT COUNT(conflict_id) + COUNT(candidate_json) FROM plan_conflicts) AS conflicts, (SELECT COUNT(ciphertext) + COUNT(last_operation_id) FROM encrypted_sync_entities) AS encrypted_entities, (SELECT COUNT(change_seq) + COUNT(operation_id) FROM encrypted_sync_changes) AS encrypted_changes, (SELECT COUNT(result_json) + COUNT(reservation_id) FROM encrypted_sync_operations) AS encrypted_operations, (SELECT COUNT(payload_json) + COUNT(synced_at) FROM watch_read_projection) AS read_projection, (SELECT COUNT(synced_at) + COUNT(plan_count) + COUNT(workout_count) FROM watch_read_projection_state) AS read_projection_state, (SELECT COUNT(revision) FROM encrypted_sync_authority_checkpoints) AS authority_checkpoints, (SELECT COUNT(observation_json) FROM encrypted_sync_authority_observations) AS authority_observations, (SELECT COUNT(owner_id) + COUNT(cursor) FROM watch_v3_device_state) AS v3_devices, (SELECT COUNT(last_operation_id) FROM watch_v3_plan_libraries) AS v3_plans, (SELECT COUNT(payload_json) + COUNT(created_operation_id) FROM watch_v3_workouts) AS v3_workouts, (SELECT COUNT(command_id) FROM watch_v3_workout_tombstones) AS v3_workout_tombstones, (SELECT COUNT(payload_json) FROM watch_v3_sleep_records) AS v3_sleep, (SELECT COUNT(command_id) + COUNT(request_hash) FROM watch_v3_commands) AS v3_commands, (SELECT COUNT(revision) FROM watch_v3_authority_checkpoints) AS v3_authority_checkpoints, (SELECT COUNT(observation_json) FROM watch_v3_authority_observations) AS v3_authority_observations',
    ).first<{ ready: number | string }>()
    return Number(row?.ready) === 1
  } catch {
    return false
  }
}

async function ready(env: Env): Promise<Response> {
  const storage = await storageReady(env)
  const secretsSeparated = oauthSecretsSeparated(env)
  const oauth = secretsSeparated && await verifyOAuthDependencies(env, oauthFetcher(env))
  const observation = authorityObservationConfigured(env)
  const revisionDomain = cloudV3Configured(env)
  if (!storage || !oauth || !observation || !revisionDomain) {
    return Response.json({
      ok: false,
      ready: false,
      service: 'watch-cloud-mcp',
      storage: storage ? 'ready' : 'unavailable',
      oauth: oauth ? 'ready' : 'unavailable',
      authorityObservation: observation ? 'ready' : 'unavailable',
      revisionDomain: revisionDomain ? 'ready' : 'unavailable',
    }, { status: 503, headers: { 'Cache-Control': 'no-store' } })
  }
  return Response.json({
    ok: true,
    ready: true,
    service: 'watch-cloud-mcp',
    storage: 'ready',
    oauth: 'ready',
    authorityObservation: 'ready',
    revisionDomain: 'ready',
  }, { headers: { 'Cache-Control': 'no-store' } })
}

function oauthSecretsSeparated(env: Env): boolean {
  return Boolean(env.OAUTH_RS_CLIENT_SECRET)
    && env.OAUTH_RS_CLIENT_SECRET !== env.SYNC_KEY
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url)
    if (url.pathname === WATCH_AUTHORITY_PATH) {
      return Response.json(
        { error: 'service_binding_required' },
        { status: 403, headers: { 'Cache-Control': 'no-store' } },
      )
    }
    if (url.pathname === '/healthz' && request.method === 'GET') {
      return Response.json({
        ok: true,
        service: 'watch-cloud-mcp',
        buildCommit: COMMIT_PATTERN.test(env.BUILD_COMMIT ?? '')
          ? env.BUILD_COMMIT
          : 'unknown',
        syncProtocolVersion: 2,
        cloudSyncProtocolVersion: 3,
        legacySyncProtocolVersion: 1,
        envelopeVersion: 1,
        authorityObservationSchemaVersion: 1,
      })
    }
    if (url.pathname === '/readyz' && request.method === 'GET') return ready(env)
    if (
      (url.pathname === '/.well-known/oauth-protected-resource/mcp'
        || url.pathname === '/.well-known/oauth-protected-resource')
      && request.method === 'GET'
    ) {
      return protectedResourceMetadata(request, env, oauthFetcher(env))
    }

    const v3Response = await routeCloudV3(request, env)
    if (v3Response) return v3Response
    const syncResponse = await routeSync(request, env)
    if (syncResponse) return syncResponse

    if (url.pathname === '/mcp') {
      if (!oauthSecretsSeparated(env)) {
        return Response.json(
          { error: 'oauth_secret_boundary_invalid' },
          { status: 503, headers: { 'Cache-Control': 'no-store' } },
        )
      }
      const verified = await verifyMcpRequest(request, env, WATCH_SCOPES, oauthFetcher(env))
      if (!verified.ok) return oauthHttpError(request, verified)
      const handler = createMcpHandler(
        () => createWatchMcpServer(env, verified.authInfo.scopes),
        {
          route: '/mcp',
          legacy: 'stateless',
          allowedHostnames: [
            new URL(env.OAUTH_AUDIENCE!).hostname,
            'watch-staging.pyzzgk.dpdns.org',
            'localhost',
            '127.0.0.1',
          ],
        },
      )
      return handler.fetch(request, { authInfo: verified.authInfo })
    }
    return new Response('not found', { status: 404 })
  },
}
