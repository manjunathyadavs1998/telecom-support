import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '@/app/AppProviders'
import { createAppRouter, registerRouter } from '@/app/router'
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
