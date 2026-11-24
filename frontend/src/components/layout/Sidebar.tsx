import { LogOut, RadioTower } from 'lucide-react'
import { NavLink } from 'react-router'
import type { NavSection } from '@/app/navigation'
import { cn } from '@/utils/cn'

export function Sidebar({ sections, onNavigate, onSignOut }: { sections: NavSection[]; onNavigate?: () => void; onSignOut: () => void }) {
  return (
    <div className="flex h-full flex-col bg-sidebar text-sidebar-foreground">
      <div className="flex h-16 items-center gap-2 px-5 text-lg font-semibold text-white">
        <RadioTower className="h-6 w-6 text-blue-400" aria-hidden /> Telecom
      </div>
      <nav aria-label="Main" className="flex-1 space-y-6 overflow-y-auto px-3 py-4">
        {sections.map((section) => (
          <div key={section.title}>
            {sections.length > 1 && (
              <p className="px-3 pb-2 text-[11px] font-semibold tracking-wider text-sidebar-foreground/60 uppercase">{section.title}</p>
            )}
            <ul className="space-y-1">
              {section.items.map(({ to, label, icon: Icon, end }) => (
                <li key={to}>
                  <NavLink
                    to={to}
                    end={end}
                    onClick={onNavigate}
                    className={({ isActive }) =>
                      cn(
                        'flex items-center gap-3 rounded-lg px-3 py-2 text-sm transition-colors',
                        isActive ? 'bg-sidebar-accent text-white' : 'hover:bg-white/10 hover:text-white',
                      )
                    }
                  >
                    <Icon className="h-4 w-4" aria-hidden />
                    {label}
                  </NavLink>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </nav>
      <div className="border-t border-white/10 p-3">
        <button
          type="button"
          onClick={onSignOut}
          className="flex w-full items-center gap-3 rounded-lg px-3 py-2 text-sm hover:bg-white/10 hover:text-white"
        >
          <LogOut className="h-4 w-4" aria-hidden /> Sign out
        </button>
      </div>
    </div>
  )
}
