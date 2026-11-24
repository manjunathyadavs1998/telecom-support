import { PageContainer } from '@/components/layout/PageContainer'
import { LandingLink } from './LandingLink'

/**
 * 403 (frontend.md §3 System pages, XC-02): rendered at the requested URL (no redirect), and by a page whose query gets a 403.
 * `document.title` "Access denied · Telecom Support".
 */
export function ForbiddenPage() {
  return (
    <PageContainer title="Access denied" description="You don't have access to this page.">
      <div>
        <LandingLink />
      </div>
    </PageContainer>
  )
}
