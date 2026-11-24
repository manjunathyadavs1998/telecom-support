---
name: react-feature-module
description: Scaffold or extend a frontend feature in this project (React + TS + TanStack Query + RHF/Zod + Tailwind) with typed API hooks, a page, all UI states, permission gating, routing and tests. Use when adding any screen, form, table or chart under frontend/src/features.
---

# React feature module recipe

## Layout
```
frontend/src/features/<area>/<feature>/
  api.ts            # query keys + useXxx hooks (TanStack Query) using services/http + generated types
  schema.ts         # zod schemas for forms (mirror backend validation messages)
  components/       # presentational pieces
  pages/XxxPage.tsx # route component
  XxxPage.test.tsx  # Vitest + Testing Library
```
`<area>` is `customer` | `inbound` | `outbound` | `business` | `authentication` (BRD §10.1).

## Steps
1. **Types**: use `components['schemas']['XxxResponse']` from `@/services/api-types`. If it's missing, the backend endpoint is new.
   Run `npm run gen:api` with the backend running. Don't hand-write API types.
2. **api.ts**
   ```ts
   export const xxxKeys = { all: ['xxx'] as const, list: (p: Params) => [...xxxKeys.all, 'list', p] as const }
   export function useXxxList(p: Params) {
     return useQuery({ queryKey: xxxKeys.list(p), queryFn: () => http.get<Page<Xxx>>('/api/v1/...', { query: p }),
                       placeholderData: keepPreviousData })
   }
   export function useCreateXxx() {
     const qc = useQueryClient()
     return useMutation({ mutationFn: (body: CreateXxx) => http.post<Xxx>('/api/v1/...', body, { idempotent: true }),
                          onSuccess: () => qc.invalidateQueries({ queryKey: xxxKeys.all }) })
   }
   ```
3. **Page**: wrap it in `<PageHeader title=… description=…/>`. Render states with `components/feedback`:
   `isPending → <LoadingState variant="table|cards|form"/>`, `isError → <ErrorState error={error} onRetry={refetch}/>`,
   empty → `<EmptyState title action/>` (XC-03). Tables use `components/data-table/DataTable` (server-side pagination).
4. **Forms**: `useForm({ resolver: zodResolver(schema) })`, `<FormField>` primitives (label + error + `aria-describedby`).
   Disable submit while `isPending`. Map ProblemDetail `errors[]` onto fields with `applyServerErrors(form, error)`.
   Show the result with `toast.success` only **after** the backend confirms it (XC-07).
5. **Gating**: add the route in `app/router.tsx` under the role's layout with `<RequirePermission permission=…>`, plus a nav
   item in `app/navigation.ts` with the same permission. Wrap buttons for privileged actions in `<Can permission=…>`.
6. **Dates**: show them with `formatDateTime()` from `utils/format` (Asia/Kolkata), and send ISO dates.
   Date-range inputs validate `from ≤ to` and a bounded range (XC-05).
7. **Tests**: render with `renderWithProviders` from `src/test/utils.tsx` and mock HTTP with `mockApi`. Cover:
   loading → data, empty state, a validation error, and hidden actions for a role without the permission.
8. Run `npm run typecheck && npm run lint && npm test` (inside the `web` container on this Windows host).

## UI kit (src/components/ui)
`Button`, `Input`, `Textarea`, `Select`, `Label`, `Card`, `Badge`, `StatusBadge`, `SeverityBadge`, `Dialog`, `ConfirmDialog`,
`Tabs`, `Skeleton`, `Alert`. Use these instead of raw elements so the design stays consistent.
