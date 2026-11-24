import { CircleSlash } from 'lucide-react'
import { EmptyState } from '@/components/states/EmptyState'
import { PageContainer } from '@/components/layout/PageContainer'

type NoAccessPageProps = {
  /** Sign out (the shell's `useLogout`, via the outlet context); the button shows only when given. */
  onSignOut?: () => void
}

/** A signed-in user with no visible nav item lands here (frontend.md §2 `/`, §3 System pages). */
export function NoAccessPage({ onSignOut }: NoAccessPageProps) {
  return (
    <PageContainer title="No access">
      <EmptyState
        icon={<CircleSlash className="size-10" />}
        title="No features are assigned to your account"
        description="Ask your administrator to assign you a role."
        action={
          onSignOut ? (
            <button
              type="button"
              onClick={onSignOut}
              className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
            >
              Sign out
            </button>
          ) : undefined
        }
      />
    </PageContainer>
  )
}
