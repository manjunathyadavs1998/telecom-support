import { zodResolver } from '@hookform/resolvers/zod'
import { useId, useMemo, type Ref } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { DateRangeFilter, type DateRangeFilterHandle } from '@/components/data/DateRangeFilter'
import { statusLabel } from '@/components/data/statusLabel'
import type { ListFilters, ListParams } from '@/components/data/useListParams'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { requestReferenceSchema } from '@/features/customer/requests/queries'
import type { CaseStatus } from '@/types/api'
import { CASE_SORTS, CASE_STATUSES } from './queries'

const searchSchema = z.object({ reference: z.union([z.literal(''), requestReferenceSchema]) })
type SearchValues = z.infer<typeof searchSchema>

type CaseFiltersProps = {
  params: ListParams
  setFilters: (filters: ListFilters) => void
  filterRef: Ref<DateRangeFilterHandle>
}

/**
 * Case list toolbar (03-inbound/frontend.md §3.6, §6): date range (3-month default) · reference search · status checkbox group
 * (the 8 internal statuses) · sort (the I9 whitelist). Everything lives in the URL; any change resets the page.
 */
export function CaseFilters({ params, setFilters, filterRef }: CaseFiltersProps) {
  const hintId = useId()
  const sortId = useId()
  const values = useMemo(() => ({ reference: params.reference ?? '' }), [params.reference])
  const form = useForm<SearchValues>({ resolver: zodResolver(searchSchema), values })
  const { from, to, reference, status, sort } = params
  const current: ListFilters = { ...(from && to ? { from, to } : {}), reference, status, sort }
  const selected = new Set(status ?? [])

  const onSearch = form.handleSubmit(
    ({ reference: next }) => setFilters({ ...current, reference: next || undefined }),
    () => undefined,
  )

  const toggleStatus = (value: CaseStatus, on: boolean) => {
    const next = CASE_STATUSES.filter((item) => (item === value ? on : selected.has(item)))
    setFilters({ ...current, status: next.length > 0 ? next : undefined })
  }

  return (
    <div className="flex flex-col gap-4 rounded-lg border bg-white p-4">
      <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
        <DateRangeFilter
          ref={filterRef}
          value={params}
          onApply={(range) => setFilters({ ...current, ...range })}
          onReset={() => setFilters({ ...current, from: undefined, to: undefined })}
        />
        <Form {...form}>
          <form noValidate aria-label="Search requests" onSubmit={onSearch} className="flex flex-col gap-1">
            <div className="flex flex-col gap-2 sm:flex-row sm:items-start">
              <FormField
                control={form.control}
                name="reference"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Reference</FormLabel>
                    <FormControl aria-describedby={hintId}>
                      <Input placeholder="SR-20261125-000042" autoComplete="off" className="sm:w-60" {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <div className="flex gap-2 sm:mt-7">
                <Button type="submit" variant="outline">
                  Search
                </Button>
                {reference ? (
                  <Button type="button" variant="ghost" onClick={() => setFilters({ ...current, reference: undefined })}>
                    Clear search
                  </Button>
                ) : null}
              </div>
            </div>
            <p id={hintId} className="text-sm text-muted-foreground">
              Searched within the selected dates.
            </p>
          </form>
        </Form>
      </div>
      <div className="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-sm font-medium">Status</legend>
          <div className="flex flex-wrap gap-x-4 gap-y-2">
            {CASE_STATUSES.map((value) => {
              const id = `${hintId}-${value}`
              return (
                <div key={value} className="flex items-center gap-2">
                  <Checkbox id={id} checked={selected.has(value)} onCheckedChange={(on) => toggleStatus(value, on === true)} />
                  <Label htmlFor={id}>{statusLabel({ kind: 'caseInternal', value })}</Label>
                </div>
              )
            })}
          </div>
          {selected.size === 0 ? <p className="text-sm text-muted-foreground">All statuses</p> : null}
        </fieldset>
        <div className="flex flex-col gap-1">
          <Label htmlFor={sortId}>Sort</Label>
          <Select value={sort ?? CASE_SORTS[0].value} onValueChange={(value) => setFilters({ ...current, sort: value === CASE_SORTS[0].value ? undefined : value })}>
            <SelectTrigger id={sortId} className="sm:w-48">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {CASE_SORTS.map((option) => (
                <SelectItem key={option.value} value={option.value}>
                  {option.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>
    </div>
  )
}
