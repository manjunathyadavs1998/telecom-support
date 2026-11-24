import * as DropdownMenu from '@radix-ui/react-dropdown-menu'
import { ChevronDown, LogOut, Menu } from 'lucide-react'
import type { ReactNode } from 'react'
import type { Me } from '@/features/authentication/api'

const roleLabels: Record<string, string> = {
  CUSTOMER: 'Customer',
  INBOUND_AGENT: 'Inbound assistant',
  REACTIVATION_APPROVER: 'Reactivation approver',
  OUTBOUND_AGENT: 'Outbound assistant',
  OUTBOUND_SUPERVISOR: 'Outbound supervisor',
  BUSINESS_ANALYST: 'Business team',
  BUSINESS_AUDITOR: 'Business auditor',
}

export function Header({ me, onOpenNav, onSignOut, actions }: { me: Me; onOpenNav: () => void; onSignOut: () => void; actions?: ReactNode }) {
  const initials = me.displayName
    .split(' ')
    .map((p) => p[0])
    .slice(0, 2)
    .join('')
  return (
    <header className="sticky top-0 z-20 flex h-16 items-center gap-3 border-b border-border bg-white/95 px-4 backdrop-blur lg:px-8">
      <button type="button" onClick={onOpenNav} className="rounded-lg p-2 text-slate-600 hover:bg-slate-100 lg:hidden" aria-label="Open navigation">
        <Menu className="h-5 w-5" />
      </button>
      <div className="flex-1" />
      {actions}
      <DropdownMenu.Root>
        <DropdownMenu.Trigger className="flex items-center gap-2 rounded-lg px-2 py-1.5 hover:bg-slate-100" aria-label="Account menu">
          <span className="flex h-8 w-8 items-center justify-center rounded-full bg-primary text-xs font-semibold text-white">{initials}</span>
          <span className="hidden text-left sm:block">
            <span className="block text-sm font-medium text-slate-800">{me.displayName}</span>
            <span className="block text-xs text-muted">{me.roles.map((r) => roleLabels[r] ?? r).join(' · ')}</span>
          </span>
          <ChevronDown className="h-4 w-4 text-slate-500" aria-hidden />
        </DropdownMenu.Trigger>
        <DropdownMenu.Portal>
          <DropdownMenu.Content align="end" sideOffset={6} className="z-50 min-w-48 rounded-lg border border-border bg-white p-1 shadow-lg">
            <DropdownMenu.Label className="px-3 py-2 text-xs text-muted">Signed in as {me.username}</DropdownMenu.Label>
            <DropdownMenu.Separator className="my-1 h-px bg-border" />
            <DropdownMenu.Item
              onSelect={onSignOut}
              className="flex cursor-pointer items-center gap-2 rounded-md px-3 py-2 text-sm text-slate-700 outline-none data-[highlighted]:bg-slate-100"
            >
              <LogOut className="h-4 w-4" aria-hidden /> Sign out
            </DropdownMenu.Item>
          </DropdownMenu.Content>
        </DropdownMenu.Portal>
      </DropdownMenu.Root>
    </header>
  )
}
