import { describe, expect, it } from 'vitest'
import {
  canAdministerIntegrations,
  canDesign,
  canPublish,
  canReplayDeadLetters,
  canViewDataQuality,
  canViewIntegrations,
} from './roles'

describe('roles', () => {
  it('lets an operator or an admin look at the integrations, nobody else', () => {
    expect(canViewIntegrations(['OPERATOR'])).toBe(true)
    expect(canViewIntegrations(['ADMIN'])).toBe(true)
    expect(canViewIntegrations(['VIEWER', 'EDITOR', 'PUBLISHER'])).toBe(false)
    expect(canViewIntegrations([])).toBe(false)
  })

  it('lets only an admin act on them', () => {
    expect(canAdministerIntegrations(['ADMIN'])).toBe(true)
    expect(canAdministerIntegrations(['OPERATOR'])).toBe(false)
    expect(canAdministerIntegrations(['VIEWER'])).toBe(false)
  })

  it('shows the Data quality panel to an operator or an admin and lets only an admin replay', () => {
    expect(canViewDataQuality(['OPERATOR'])).toBe(true)
    expect(canViewDataQuality(['ADMIN'])).toBe(true)
    expect(canViewDataQuality(['VIEWER', 'EDITOR', 'PUBLISHER'])).toBe(false)
    expect(canReplayDeadLetters(['ADMIN'])).toBe(true)
    expect(canReplayDeadLetters(['OPERATOR'])).toBe(false)
  })

  it('keeps the designer and publisher rules', () => {
    expect(canDesign(['EDITOR'])).toBe(true)
    expect(canDesign(['OPERATOR'])).toBe(false)
    expect(canPublish(['PUBLISHER'])).toBe(true)
    expect(canPublish(['ADMIN'])).toBe(true)
    expect(canPublish(['EDITOR'])).toBe(false)
  })
})
