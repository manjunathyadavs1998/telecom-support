import { useOutletContext } from 'react-router'

/** What the protected shell hands its pages through `<Outlet context>`: the shell's single logout flow (F19). */
export type ShellContext = { logout: () => void }

/** The shell's context; `undefined` when a page renders outside `AppShell` (unit tests). */
export function useShellContext(): ShellContext | undefined {
  return useOutletContext<ShellContext | undefined>()
}
