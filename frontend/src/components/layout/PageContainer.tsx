import { useEffect, type ReactNode } from 'react'

export const APP_TITLE = 'Telecom Support'

type PageContainerProps = {
  title: string
  description?: string
  actions?: ReactNode
  children?: ReactNode
}

/**
 * Every page's frame (frontend.md §4, §8): a focusable `<h1>` (the route-change focus target, `tabIndex={-1}`), optional
 * description and actions, and `document.title` "<title> · Telecom Support".
 */
export function PageContainer({ title, description, actions, children }: PageContainerProps) {
  useEffect(() => {
    document.title = `${title} · ${APP_TITLE}`
  }, [title])

  return (
    <div className="enter-soft mx-auto flex w-full max-w-screen-2xl flex-col gap-6 p-4 md:p-6 lg:p-8">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="flex flex-col gap-1">
          <h1
            tabIndex={-1}
            className="rounded-sm text-2xl font-semibold tracking-tight text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
          >
            {title}
          </h1>
          {description ? <p className="text-muted-foreground">{description}</p> : null}
        </div>
        {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
      </div>
      {children}
    </div>
  )
}
