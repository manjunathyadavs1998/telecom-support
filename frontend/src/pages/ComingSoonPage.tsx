import { Construction } from 'lucide-react'
import { EmptyState } from '@/components/states/EmptyState'
import { PageContainer } from '@/components/layout/PageContainer'
import type { NavItem } from '@/permissions/navConfig'

type ComingSoonPageProps = { item: NavItem }

/** Placeholder for every later-slice nav entry (frontend.md §2, §3): title, BRD id and slice; makes no API call. */
export function ComingSoonPage({ item }: ComingSoonPageProps) {
  return (
    <PageContainer title={item.label} description={item.brd}>
      <EmptyState
        icon={<Construction className="size-10" />}
        title="Coming soon"
        description={`Available in slice ${item.slice}`}
      />
    </PageContainer>
  )
}
