import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '@/styles/globals.css'

// F6 placeholder: providers and the router arrive in later tasks (frontend.md §2, §5).
const root = document.getElementById('root')
if (!root) throw new Error('#root element missing from index.html')

createRoot(root).render(
  <StrictMode>
    <main id="main" tabIndex={-1} className="p-4 md:p-6">
      <h1 className="text-2xl font-semibold">Telecom Support</h1>
    </main>
  </StrictMode>,
)
