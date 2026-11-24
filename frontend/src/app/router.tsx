import { createBrowserRouter } from 'react-router'
import { registerNavigator } from './navigation'
import { appRoutes } from './routes'

export type AppRouter = ReturnType<typeof createBrowserRouter>

/** The app's data router (frontend.md §1: React Router v7 data mode). */
export function createAppRouter(): AppRouter {
  return createBrowserRouter(appRoutes)
}

/**
 * Makes `router` the navigator for code outside React (the API client's 401 handler). While a navigation is pending its
 * target is the current location, so a 401 from a loader's `/me` re-check returns the user to the page being opened.
 * Returns an unregister function.
 */
export function registerRouter(router: AppRouter): () => void {
  return registerNavigator({
    navigate: (to) => router.navigate(to),
    location: () => router.state.navigation.location ?? router.state.location,
  })
}
