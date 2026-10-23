import { QueryClientProvider } from '@tanstack/react-query'
import { useEffect } from 'react'
import { RouterProvider } from 'react-router'
import { Toaster } from '@/components/ui/toaster'
import { TooltipProvider } from '@/components/ui/overlays'
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
      <TooltipProvider delayDuration={300}>
        <RouterProvider router={router} />
      </TooltipProvider>
      <Toaster />
    </QueryClientProvider>
  )
}
