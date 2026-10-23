import { createBrowserRouter, redirect } from 'react-router'
import { useAuthStore } from '@/lib/stores/auth-store'

function requireSession() {
  if (!useAuthStore.getState().credential) throw redirect('/login')
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
    lazy: () => import('@/features/shell/home-page').then((m) => ({ Component: m.HomePage })),
  },
  { path: '*', loader: () => redirect('/') },
])
