import { Link } from 'react-router'
import { useMeQuery } from '@/features/authentication/meQuery'
import { landingFor } from '@/permissions/navConfig'

/**
 * "Go to your home page" link to the user's role landing route (frontend.md §2, §3 System pages); `/` (the landing redirect)
 * when the user has none.
 */
export function LandingLink() {
  const { data: me } = useMeQuery()
  return (
    <Link
      to={landingFor(me) ?? '/'}
      className="inline-flex rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
    >
      Go to your home page
    </Link>
  )
}
