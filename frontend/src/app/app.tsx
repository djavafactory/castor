import { QueryClientProvider } from '@tanstack/react-query'
import { useEffect } from 'react'
import { RouterProvider } from 'react-router'
import { Toaster } from 'sonner'
import { setUnauthorizedHandler } from '@/lib/api-client'
import { queryClient } from './query-client'
import { router } from './router'

export function App() {
  useEffect(() => {
    setUnauthorizedHandler(() => {
      queryClient.clear()
      void router.navigate('/login', { replace: true })
    })
    return () => setUnauthorizedHandler(null)
  }, [])

  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
      <Toaster />
    </QueryClientProvider>
  )
}
