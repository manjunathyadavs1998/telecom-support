import { zodResolver } from '@hookform/resolvers/zod'
import { X } from 'lucide-react'
import { useId, useState, type Ref } from 'react'
import { useForm } from 'react-hook-form'
import { DateRangeFilter, type DateRangeFilterHandle } from '@/components/data/DateRangeFilter'
import { statusLabel } from '@/components/data/statusLabel'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectGroup, SelectItem, SelectLabel, SelectTrigger, SelectValue } from '@/components/ui/select'
import { useOutboundQueueStore } from '@/stores/outboundQueueStore'
import type { CaseStatus, QueueAgent, QueueCategory, Severity } from '@/types/api'
import { queueFilterSchema, type QueueFilterInput, type QueueFilterValues } from './queueFilterSchema'
import { CASE_STATUSES, EMPTY_FILTERS, SEVERITIES, type QueueFilters as Filters, type QueueParams } from './useQueueParams'

const ANY = 'any'

type QueueFiltersProps = {
  params: QueueParams
  setFilters: (filters: Filters) => void
  categories: readonly QueueCategory[]
  agents: readonly QueueAgent[]
  createdRef: Ref<DateRangeFilterHandle>
}

function current(p: QueueParams): Filters {
  const { page: _page, ...filters } = p
  return filters
}

/**
 * The queue filter panel (04-outbound/frontend.md §3.1; OUT-02): date ranges apply on their own; the rest apply together with
 * [Apply]. Every filter except the customer mobile is in the URL; the mobile number goes to the memory-only store (NFR-PRIV).
 * Applied filters show as removable chips; [Clear all] resets the URL and the store.
 */
export function QueueFilters({ params, setFilters, categories, agents, createdRef }: QueueFiltersProps) {
  const id = useId()
  const msisdn = useOutboundQueueStore((state) => state.msisdn)
  const setMsisdn = useOutboundQueueStore((state) => state.setMsisdn)
  const clearMsisdn = useOutboundQueueStore((state) => state.clear)
  const [status, setStatus] = useState<readonly CaseStatus[]>(params.status)
  const [severity, setSeverity] = useState<readonly Severity[]>(params.severity)
  const [category, setCategory] = useState(params.category ?? ANY)
  const [assignee, setAssignee] = useState(params.assignee ?? ANY)
  const [overdue, setOverdue] = useState(params.overdue)
  const form = useForm<QueueFilterInput, unknown, QueueFilterValues>({
    resolver: zodResolver(queueFilterSchema),
    values: { msisdn: msisdn ?? '', ref: params.ref ?? '' },
  })

  // Keep the drafts in step when the URL changes elsewhere (tiles, chips, Back).
  const [synced, setSynced] = useState(params)
  if (synced !== params) {
    setSynced(params)
    setStatus(params.status)
    setSeverity(params.severity)
    setCategory(params.category ?? ANY)
    setAssignee(params.assignee ?? ANY)
    setOverdue(params.overdue)
  }

  const apply = form.handleSubmit(
    (values) => {
      setMsisdn(values.msisdn === '' ? null : values.msisdn)
      setFilters({
        ...current(params),
        status,
        severity,
        category: category === ANY ? undefined : category,
        assignee: assignee === ANY ? undefined : assignee,
        overdue,
        ref: values.ref === '' ? undefined : values.ref,
      })
    },
    () => undefined,
  )

  const clearAll = () => {
    clearMsisdn()
    form.reset({ msisdn: '', ref: '' })
    setFilters(EMPTY_FILTERS)
  }

  const toggle = <T,>(list: readonly T[], value: T, on: boolean) =>
    on ? [...list, value] : list.filter((item) => item !== value)

  const agentName = (userId: string) => agents.find((a) => String(a.userId) === userId)?.displayName ?? `User ${userId}`
  const chips: { key: string; label: string; remove: () => void }[] = []
  const base = current(params)
  if (params.status.length) chips.push({ key: 'status', label: `Status: ${params.status.map((s) => statusLabel({ kind: 'caseInternal', value: s })).join(', ')}`, remove: () => setFilters({ ...base, status: [] }) })
  if (params.severity.length) chips.push({ key: 'severity', label: `Severity: ${params.severity.map((s) => statusLabel({ kind: 'severity', value: s })).join(', ')}`, remove: () => setFilters({ ...base, severity: [] }) })
  if (params.category) chips.push({ key: 'category', label: `Category: ${params.category}`, remove: () => setFilters({ ...base, category: undefined }) })
  if (params.assignee) chips.push({ key: 'assignee', label: `Assigned to: ${params.assignee === 'me' ? 'Me' : params.assignee === 'unassigned' ? 'Unassigned' : agentName(params.assignee)}`, remove: () => setFilters({ ...base, assignee: undefined }) })
  if (msisdn) chips.push({ key: 'msisdn', label: `Mobile ••••${msisdn.slice(-4)}`, remove: () => clearMsisdn() })
  if (params.ref) chips.push({ key: 'ref', label: `Case ID: ${params.ref}`, remove: () => setFilters({ ...base, ref: undefined }) })
  if (params.overdue) chips.push({ key: 'overdue', label: 'Overdue only', remove: () => setFilters({ ...base, overdue: false }) })
  if (params.ufrom && params.uto) chips.push({ key: 'updated', label: `Updated ${params.ufrom} – ${params.uto}`, remove: () => setFilters({ ...base, ufrom: undefined, uto: undefined }) })

  return (
    <section aria-label="Filters" className="flex flex-col gap-4 rounded-lg border bg-white p-4">
      <div className="flex flex-col gap-4 lg:flex-row lg:flex-wrap lg:gap-8">
        <div className="flex flex-col gap-1">
          <span className="text-sm font-medium">Created</span>
          <DateRangeFilter
            ref={createdRef}
            value={{ from: params.from, to: params.to }}
            onApply={(range) => setFilters({ ...base, ...range })}
            onReset={() => setFilters({ ...base, from: undefined, to: undefined })}
          />
        </div>
        <div className="flex flex-col gap-1">
          <span className="text-sm font-medium">Updated</span>
          <DateRangeFilter
            defaultLabel="Any time"
            value={{ from: params.ufrom, to: params.uto }}
            onApply={(range) => setFilters({ ...base, ufrom: range.from, uto: range.to })}
            onReset={() => setFilters({ ...base, ufrom: undefined, uto: undefined })}
          />
        </div>
      </div>
      <Form {...form}>
        <form noValidate onSubmit={apply} className="flex flex-col gap-4">
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
            <fieldset className="flex flex-col gap-2">
              <legend className="mb-1 text-sm font-medium">Status</legend>
              {CASE_STATUSES.map((value) => (
                <div key={value} className="flex items-center gap-2">
                  <Checkbox id={`${id}-s-${value}`} checked={status.includes(value)} onCheckedChange={(on) => setStatus((l) => toggle(l, value, on === true))} />
                  <Label htmlFor={`${id}-s-${value}`}>{statusLabel({ kind: 'caseInternal', value })}</Label>
                </div>
              ))}
            </fieldset>
            <div className="flex flex-col gap-4">
              <fieldset className="flex flex-col gap-2">
                <legend className="mb-1 text-sm font-medium">Severity</legend>
                {SEVERITIES.map((value) => (
                  <div key={value} className="flex items-center gap-2">
                    <Checkbox id={`${id}-v-${value}`} checked={severity.includes(value)} onCheckedChange={(on) => setSeverity((l) => toggle(l, value, on === true))} />
                    <Label htmlFor={`${id}-v-${value}`}>{statusLabel({ kind: 'severity', value })}</Label>
                  </div>
                ))}
              </fieldset>
              <div className="flex items-center gap-2">
                <Checkbox id={`${id}-overdue`} checked={overdue} onCheckedChange={(on) => setOverdue(on === true)} />
                <Label htmlFor={`${id}-overdue`}>Overdue only</Label>
              </div>
            </div>
            <div className="flex flex-col gap-4">
              <div className="flex flex-col gap-1">
                <Label htmlFor={`${id}-category`}>Category</Label>
                <Select value={category} onValueChange={setCategory}>
                  <SelectTrigger id={`${id}-category`}>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value={ANY}>Any category</SelectItem>
                    {categories.map((top) => (
                      <SelectGroup key={top.code}>
                        <SelectLabel>{top.name}</SelectLabel>
                        <SelectItem value={top.code}>
                          All of {top.name}
                          {top.audience === 'INTERNAL' ? ' (Internal)' : ''}
                        </SelectItem>
                        {top.children.map((child) => (
                          <SelectItem key={child.code} value={child.code}>
                            {child.name}
                          </SelectItem>
                        ))}
                      </SelectGroup>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="flex flex-col gap-1">
                <Label htmlFor={`${id}-assignee`}>Assigned to</Label>
                <Select value={assignee} onValueChange={setAssignee}>
                  <SelectTrigger id={`${id}-assignee`}>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value={ANY}>Anyone</SelectItem>
                    <SelectItem value="me">Me</SelectItem>
                    <SelectItem value="unassigned">Unassigned</SelectItem>
                    {agents.map((agent) => (
                      <SelectItem key={agent.userId} value={String(agent.userId)}>
                        {agent.displayName}
                        {agent.teamName ? ` — ${agent.teamName}` : ''}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </div>
            <div className="flex flex-col gap-4">
              <FormField
                control={form.control}
                name="msisdn"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Customer mobile</FormLabel>
                    <FormControl>
                      <Input type="tel" inputMode="tel" autoComplete="off" {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField
                control={form.control}
                name="ref"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Case ID</FormLabel>
                    <FormControl>
                      <Input placeholder="SR-20261128-000042" autoComplete="off" {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </div>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button type="submit">Apply</Button>
            <Button type="button" variant="outline" onClick={clearAll}>
              Clear all
            </Button>
          </div>
        </form>
      </Form>
      {chips.length > 0 ? (
        <ul aria-label="Applied filters" className="flex flex-wrap gap-2">
          {chips.map((chip) => (
            <li key={chip.key}>
              <Button type="button" variant="outline" size="sm" onClick={chip.remove} aria-label={`Remove ${chip.label}`}>
                {chip.label}
                <X aria-hidden="true" className="size-3.5" />
              </Button>
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  )
}
