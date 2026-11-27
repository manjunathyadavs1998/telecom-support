import { zodResolver } from '@hookform/resolvers/zod'
import { useImperativeHandle, useMemo, useRef, useState, type Ref } from 'react'
import { useForm } from 'react-hook-form'
import { Button } from '@/components/ui/button'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { defaultWindow, MAX_RANGE_DAYS, todayIst, type DateRange } from '@/lib/dateWindow'
import { dateRangeSchema } from './dateRangeSchema'

export const IST_HINT = 'Dates are in India Standard Time (IST)'

export type DateRangeFilterHandle = {
  /** "Change dates" (empty states, §3): select Custom range and focus its start date. */
  openCustomRange: () => void
}

type DateRangeFilterProps = {
  /** The applied range; no dates = the default "Last 3 months" window (no `from`/`to` sent). */
  value: { from?: string; to?: string }
  onApply: (range: DateRange) => void
  /** Back to the default window. */
  onReset: () => void
  maxDays?: number
  ref?: Ref<DateRangeFilterHandle>
}

type Mode = 'default' | 'custom'

/**
 * List date filter (frontend.md §3 Service requests, §4; CUS-05.1/05.3, BR-06, XC-05): **Last 3 months** (default) or **Custom range**
 * (native date inputs + Apply). Tabs use manual activation (F17): arrow keys only move focus; `Enter`/`Space`/click selects.
 */
export function DateRangeFilter({ value, onApply, onReset, maxDays = MAX_RANGE_DAYS, ref }: DateRangeFilterProps) {
  const appliedFrom = value.from && value.to ? value.from : undefined
  const appliedTo = appliedFrom ? value.to : undefined
  const [mode, setMode] = useState<Mode>(appliedFrom ? 'custom' : 'default')
  // The URL is the source of truth (Back, deep links): follow external changes of the applied range. The tab is adjusted while
  // rendering (no effect) and the form follows through RHF `values`.
  const appliedKey = appliedFrom ? `${appliedFrom}/${appliedTo}` : ''
  const [syncedKey, setSyncedKey] = useState(appliedKey)
  if (syncedKey !== appliedKey) {
    setSyncedKey(appliedKey)
    setMode(appliedKey ? 'custom' : 'default')
  }
  // "Change dates": focus the start date once it is mounted (the tab panel mounts a render after the tab is selected).
  const focusFromPending = useRef(false)
  const [, setFocusRequest] = useState(0)
  const today = todayIst()
  const schema = useMemo(() => dateRangeSchema({ today, maxDays }), [today, maxDays])
  const values = useMemo(
    () => (appliedFrom && appliedTo ? { from: appliedFrom, to: appliedTo } : defaultWindow()),
    [appliedFrom, appliedTo],
  )
  const form = useForm<DateRange>({ resolver: zodResolver(schema), values })
  const { control, handleSubmit } = form

  useImperativeHandle(ref, () => ({
    openCustomRange: () => {
      focusFromPending.current = true
      setMode('custom')
      setFocusRequest((n) => n + 1) // re-render even when Custom range is already selected
    },
  }))

  function changeMode(next: string) {
    const nextMode: Mode = next === 'custom' ? 'custom' : 'default'
    setMode(nextMode)
    if (nextMode === 'default' && appliedFrom) onReset()
  }

  const onSubmit = handleSubmit((range) => onApply(range), () => undefined)

  return (
    <Tabs value={mode} onValueChange={changeMode} activationMode="manual" className="flex flex-col">
      <TabsList aria-label="Date range" className="self-start">
        <TabsTrigger value="default">Last 3 months</TabsTrigger>
        <TabsTrigger value="custom">Custom range</TabsTrigger>
      </TabsList>
      <TabsContent value="default">
        <p className="text-sm text-muted-foreground">Showing the last 3 months.</p>
      </TabsContent>
      <TabsContent value="custom">
        <Form {...form}>
          <form noValidate onSubmit={onSubmit} aria-label="Custom date range" className="flex flex-col gap-2">
            <div className="flex flex-col gap-3 sm:flex-row sm:items-start">
              <FormField
                control={control}
                name="from"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>From</FormLabel>
                    <FormControl>
                      <Input
                        type="date"
                        max={today}
                        className="sm:w-44"
                        {...field}
                        ref={(element) => {
                          field.ref(element)
                          if (element && focusFromPending.current) {
                            focusFromPending.current = false
                            element.focus()
                          }
                        }}
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField
                control={control}
                name="to"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>To</FormLabel>
                    <FormControl>
                      <Input type="date" max={today} className="sm:w-44" {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <Button type="submit" variant="outline" className="sm:mt-7">
                Apply
              </Button>
            </div>
            <p className="text-sm text-muted-foreground">{IST_HINT}</p>
          </form>
        </Form>
      </TabsContent>
    </Tabs>
  )
}
