import { useId } from 'react'
import { useWatch, type Control } from 'react-hook-form'
import { statusLabel } from '@/components/data/statusLabel'
import { Checkbox } from '@/components/ui/checkbox'
import { FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Can } from '@/permissions/Can'
import type { AgentCaseCreateRequest, Severity } from '@/types/api'
import { REASON_MAX, SEVERITIES, type RaiseRequestInput } from './raiseRequestSchema'

type SeverityFieldProps = {
  control: Control<RaiseRequestInput, unknown, AgentCaseCreateRequest>
  derived: Severity | null
  disabled: boolean
}

/**
 * Priority (03-inbound/frontend.md §3.8, §14 #4): the derived value as text; with `case.severity.override`, "Override priority"
 * reveals Severity + a required Reason. Without the override the body sends `severity: null` (the server derives it).
 */
export function SeverityField({ control, derived, disabled }: SeverityFieldProps) {
  const toggleId = useId()
  const override = useWatch({ control, name: 'override' })
  return (
    <div className="flex flex-col gap-3 rounded-lg border p-4">
      <p>
        Priority:{' '}
        <strong>{derived ? `${statusLabel({ kind: 'severity', value: derived })} (from the category)` : 'set automatically'}</strong>
      </p>
      <Can allOf={['case.severity.override']}>
        <FormField
          control={control}
          name="override"
          render={({ field }) => (
            <div className="flex items-center gap-2">
              <Checkbox
                id={toggleId}
                checked={field.value}
                disabled={disabled}
                onCheckedChange={(on) => field.onChange(on === true)}
              />
              <Label htmlFor={toggleId}>Override priority</Label>
            </div>
          )}
        />
        {override ? (
          <div className="flex flex-col gap-3 sm:flex-row sm:items-start">
            <FormField
              control={control}
              name="severity"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Priority</FormLabel>
                  <Select value={field.value} onValueChange={field.onChange} disabled={disabled}>
                    <FormControl>
                      <SelectTrigger className="sm:w-40">
                        <SelectValue placeholder="Choose" />
                      </SelectTrigger>
                    </FormControl>
                    <SelectContent>
                      {SEVERITIES.filter((value) => value !== derived).map((value) => (
                        <SelectItem key={value} value={value}>
                          {statusLabel({ kind: 'severity', value })}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField
              control={control}
              name="severityReason"
              render={({ field }) => (
                <FormItem className="flex-1">
                  <FormLabel>Reason for the override</FormLabel>
                  <FormControl>
                    <Input maxLength={REASON_MAX} autoComplete="off" disabled={disabled} {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
          </div>
        ) : null}
      </Can>
    </div>
  )
}
