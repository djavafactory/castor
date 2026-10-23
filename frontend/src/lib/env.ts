import { z } from 'zod'

const booleanString = z
  .enum(['true', 'false'])
  .default('false')
  .transform((value) => value === 'true')

const DEMO_ENVIRONMENTS = ['local', 'dev']

export const envSchema = z
  .object({
    VITE_APP_ENV: z.enum(['local', 'dev', 'test', 'staging', 'prod']),
    // Absolute URL, or a relative path such as /api when the app and API share an origin.
    VITE_API_URL: z
      .string()
      .min(1)
      .refine((value) => value.startsWith('/') || /^https?:\/\//.test(value), {
        message: 'must be an http(s) URL or a path starting with /',
      }),
    VITE_PORT_CODE: z.string().min(1),
    VITE_DEMO_LOGIN: booleanString,
    VITE_SATELLITE_TILE_URL: z.url(),
  })
  // Demo login is on only when explicitly true AND the environment is local or dev.
  .transform((value) => ({
    ...value,
    VITE_DEMO_LOGIN: value.VITE_DEMO_LOGIN && DEMO_ENVIRONMENTS.includes(value.VITE_APP_ENV),
  }))

export type Env = z.infer<typeof envSchema>

export type EnvResult = { ok: true; env: Env } | { ok: false; issues: string[] }

export function parseEnv(raw: Record<string, unknown>): EnvResult {
  const result = envSchema.safeParse(raw)
  if (result.success) return { ok: true, env: result.data }
  return {
    ok: false,
    issues: result.error.issues.map(
      (issue) => `${issue.path.join('.') || 'env'}: ${issue.message}`,
    ),
  }
}

export const envResult = parseEnv(import.meta.env)

/** Validated config. Only call after `envResult.ok` was checked (main.tsx does this). */
export function getEnv(): Env {
  if (!envResult.ok) throw new Error(`Invalid environment: ${envResult.issues.join('; ')}`)
  return envResult.env
}
