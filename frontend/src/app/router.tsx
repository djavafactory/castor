import { createBrowserRouter, redirect } from 'react-router'
import { getEnv } from '@/lib/env'
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
    path: '/design-system',
    loader: localOrDevOnly,
    lazy: () =>
      import('@/features/design-system/design-system-page').then((m) => ({
        Component: m.DesignSystemPage,
      })),
  },
  { path: '*', loader: () => redirect('/') },
])
