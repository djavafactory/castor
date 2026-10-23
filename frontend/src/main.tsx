import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { EnvError } from '@/app/env-error'
import { envResult } from '@/lib/env'
import './index.css'

const root = createRoot(document.getElementById('root')!)

if (envResult.ok) {
  // Loaded only with a valid config, so nothing reads an invalid environment.
  void import('@/app/app').then(({ App }) =>
    root.render(
      <StrictMode>
        <App />
      </StrictMode>,
    ),
  )
} else {
  root.render(<EnvError issues={envResult.issues} />)
}
