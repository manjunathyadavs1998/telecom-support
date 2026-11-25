import { PageContainer } from '@/components/layout/PageContainer'
import { LandingLink } from './LandingLink'

/** 404 (frontend.md §3 System pages): always inside the shell; signed-out users are sent to login first (§2 guard order). */
export function NotFoundPage() {
  return (
    <PageContainer title="Page not found" description="The page you asked for doesn't exist.">
      <div>
        <LandingLink />
      </div>
    </PageContainer>
  )
}
