import { describe, expect, it } from 'vitest'
import { sameVersion, versionLine } from './version'

describe('sameVersion', () => {
  it('ignores build metadata after the plus sign', () => {
    expect(sameVersion('0.1.0-dev+abc1234', '0.1.0-dev+def5678')).toBe(true)
    expect(sameVersion('0.1.0', '0.1.0')).toBe(true)
  })

  it('tells different versions apart', () => {
    expect(sameVersion('0.2.0', '0.1.0')).toBe(false)
    expect(sameVersion('0.1.0', '0.1.0-dev+abc1234')).toBe(false)
  })
})

describe('versionLine', () => {
  it('is just the version, in every environment', () => {
    expect(versionLine()).toBe(`Castor v${__APP_VERSION__}`)
  })

  it('adds the server version only when it differs', () => {
    expect(versionLine(__APP_VERSION__)).toBe(`Castor v${__APP_VERSION__}`)
    expect(versionLine('9.9.9')).toBe(`Castor v${__APP_VERSION__} (server v9.9.9)`)
  })
})
