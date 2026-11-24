import { Construction, ShieldX, SearchX } from 'lucide-react'
import { Link, Navigate } from 'react-router'
import { EmptyState, LoadingState } from '@/components/feedback/states'
import { PageHeader } from '@/components/layout/PageHeader'
import { Button } from '@/components/ui/button'
import { homePathFor, useMe } from '@/features/authentication/api'

/** Sends the user to the workspace for their role. */
export function RoleHome() {
  const { data: me, isPending } = useMe()
  if (isPending) return <LoadingState variant="inline" />
  if (!me) return <Navigate to="/login" replace />
  return <Navigate to={homePathFor(me)} replace />
}

export function ForbiddenPage() {
  return (
    <EmptyState
      icon={<ShieldX className="h-6 w-6" aria-hidden />}
      title="You don't have access to this page"
      description="Your role does not include the permission this page needs. Contact your administrator if you think this is a mistake."
      action={
        <Button asChild variant="secondary">
          <Link to="/">Go to my workspace</Link>
        </Button>
      }
    />
  )
}

export function NotFoundPage() {
  return (
    <div className="p-8">
      <EmptyState
        icon={<SearchX className="h-6 w-6" aria-hidden />}
        title="Page not found"
        description="The page you are looking for does not exist."
        action={
          <Button asChild variant="secondary">
            <Link to="/">Go home</Link>
          </Button>
        }
      />
    </div>
  )
}

/** Placeholder for workspaces delivered in later BRD phases (§15). Replaced feature by feature. */
export function ComingSoon({ title, phase }: { title: string; phase: string }) {
  return (
    <>
      <PageHeader title={title} />
      <EmptyState
        icon={<Construction className="h-6 w-6" aria-hidden />}
        title="Coming in a later delivery phase"
        description={`This screen is scheduled for ${phase} of the BRD delivery plan.`}
      />
    </>
  )
}
