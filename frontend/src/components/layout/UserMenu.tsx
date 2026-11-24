import { ChevronDown, LogOut } from 'lucide-react'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import type { Me } from '@/types/api'

type UserMenuProps = {
  me: Me
  /** Sign-out entry point (the shell's `useLogout`). */
  onSignOut?: () => void
}

const initials = (name: string) =>
  name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join('')

/** `BUSINESS_AUDITOR` → "Business auditor". */
const roleLabel = (role: string) => {
  const words = role.toLowerCase().split('_').join(' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

/** The header's user menu (frontend.md §3 Application shell): display name, username, role badges and "Sign out". */
export function UserMenu({ me, onSignOut }: UserMenuProps) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="sm">
          <Avatar aria-hidden="true">
            <AvatarFallback>{initials(me.displayName)}</AvatarFallback>
          </Avatar>
          <span className="sr-only sm:not-sr-only">{me.displayName}</span>
          <ChevronDown aria-hidden="true" className="size-4 text-muted-foreground" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuLabel className="flex flex-col gap-1">
          <span className="text-sm font-semibold">{me.displayName}</span>
          <span className="text-sm font-normal text-muted-foreground">{me.username}</span>
          <span className="flex flex-wrap gap-1 pt-1">
            {me.roles.map((role) => (
              <Badge key={role}>{roleLabel(role)}</Badge>
            ))}
          </span>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={onSignOut}>
          <LogOut aria-hidden="true" className="size-4" />
          Sign out
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
