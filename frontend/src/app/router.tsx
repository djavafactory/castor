import { createBrowserRouter, redirect } from 'react-router'
import { getEnv } from '@/lib/env'
import { canViewDataQuality, canViewIntegrations } from '@/lib/roles'
import { useAuthStore } from '@/lib/stores/auth-store'

function requireSession() {
  if (!useAuthStore.getState().credential) throw redirect('/login')
  return null
}

function localOrDevOnly() {
  const env = getEnv().VITE_APP_ENV
  if (env !== 'local' && env !== 'dev') throw redirect('/')
  return requireSession()
}

/** Integrations are for OPERATOR and ADMIN; anyone else is sent back to the port. */
function integrationsOnly() {
  requireSession()
  if (!canViewIntegrations(useAuthStore.getState().roles)) throw redirect('/')
  return null
}

/** Data quality is for OPERATOR and ADMIN; anyone else is sent back to the port. */
function dataQualityOnly() {
  requireSession()
  if (!canViewDataQuality(useAuthStore.getState().roles)) throw redirect('/')
  return null
}

// Route-level code splitting: each route module is its own chunk.
export const router = createBrowserRouter([
  {
    path: '/login',
    lazy: () => import('@/features/auth/login-page').then((m) => ({ Component: m.LoginPage })),
  },
  {
    path: '/',
    loader: requireSession,
    lazy: () => import('@/features/shell/app-shell').then((m) => ({ Component: m.AppShell })),
  },
  {
    path: '/integrations',
    loader: integrationsOnly,
    lazy: () =>
      import('@/features/integrations/integrations-page').then((m) => ({
        Component: m.IntegrationsPage,
      })),
  },
  {
    path: '/data-quality',
    loader: dataQualityOnly,
    lazy: () =>
      import('@/features/data-quality/data-quality-page').then((m) => ({
        Component: m.DataQualityPage,
      })),
  },
  {
    path: '/design-system',
    loader: localOrDevOnly,
    lazy: () =>
      import('@/features/design-system/design-system-page').then((m) => ({
        Component: m.DesignSystemPage,
      })),
  },
  { path: '*', loader: () => redirect('/') },
])
