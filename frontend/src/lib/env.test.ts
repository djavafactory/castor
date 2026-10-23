import { describe, expect, it } from 'vitest'
import { parseEnv } from './env'

const valid = {
  VITE_APP_ENV: 'local',
  VITE_API_URL: '/api',
  VITE_PORT_CODE: 'WESTPORTS',
  VITE_SATELLITE_TILE_URL: 'https://tiles.example.test/{z}/{y}/{x}.jpg',
}

describe('parseEnv', () => {
  it('accepts a valid config and defaults VITE_DEMO_LOGIN to false', () => {
    const result = parseEnv(valid)
    expect(result).toMatchObject({ ok: true, env: { VITE_DEMO_LOGIN: false } })
  })

  it('parses VITE_DEMO_LOGIN=true', () => {
    expect(parseEnv({ ...valid, VITE_DEMO_LOGIN: 'true' })).toMatchObject({
      ok: true,
      env: { VITE_DEMO_LOGIN: true },
    })
  })

  it('keeps demo login off outside local and dev even when the flag is true', () => {
    for (const VITE_APP_ENV of ['test', 'staging', 'prod']) {
      expect(parseEnv({ ...valid, VITE_APP_ENV, VITE_DEMO_LOGIN: 'true' })).toMatchObject({
        ok: true,
        env: { VITE_DEMO_LOGIN: false },
      })
    }
    expect(parseEnv({ ...valid, VITE_APP_ENV: 'dev', VITE_DEMO_LOGIN: 'true' })).toMatchObject({
      ok: true,
      env: { VITE_DEMO_LOGIN: true },
    })
  })

  it('reports every invalid value', () => {
    const result = parseEnv({
      ...valid,
      VITE_APP_ENV: 'moon',
      VITE_API_URL: 'api',
      VITE_PORT_CODE: '',
    })
    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.issues.join('\n')).toMatch(/VITE_APP_ENV/)
      expect(result.issues.join('\n')).toMatch(/VITE_API_URL/)
      expect(result.issues.join('\n')).toMatch(/VITE_PORT_CODE/)
    }
  })
})
