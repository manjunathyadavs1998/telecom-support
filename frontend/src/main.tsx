import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '@/app/AppProviders'
import { createAppRouter, registerRouter } from '@/app/router'
// Self-hosted fonts (ui-refresh U1, OQ 3): no external CDN.
import '@fontsource-variable/inter/index.css'
import '@fontsource/jetbrains-mono/400.css'
import '@fontsource/jetbrains-mono/500.css'
import '@/styles/globals.css'

const root = document.getElementById('root')
if (!root) throw new Error('#root element missing from index.html')

const router = createAppRouter()
registerRouter(router)

createRoot(root).render(
  <StrictMode>
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>
  </StrictMode>,
)
