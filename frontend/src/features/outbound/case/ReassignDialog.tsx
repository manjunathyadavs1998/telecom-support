import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { statusLabel } from '@/components/data/statusLabel'
import { FormRootError } from '@/components/states/FormRootError'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import { caseProblemText } from '@/lib/outboundText'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/services/api/errors'
import { useSubmitGuard } from '@/services/api/idempotency'
import type { OutboundCaseDetail } from '@/types/api'
import { outboundKeys, useQueueAgents } from '../queue/queries'
import { problemReason, useReassignCase } from './queries'
import { reassignSchema, type ReassignInput } from './reassignSchema'

/**
 * Reassign (O7; 04-outbound/frontend.md §3.3; OUT-03.2/03.3): supervisors only (`allowedActions.reassign`). [Reassign] is the
 * confirmation (XC-06). Sends the case version as read at submit time; 409 keeps the dialog and its input open (L2).
 */
export function ReassignDialog({ detail, allowed }: { detail: OutboundCaseDetail; allowed: boolean }) {
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const [root, setRoot] = useState<{ message: string; error: ApiError | null } | null>(null)
  const agents = useQueueAgents()
  const reassign = useReassignCase(detail.reference)
  const guard = useSubmitGuard()
  const schema = useMemo(() => reassignSchema(detail.assignedUserId), [detail.assignedUserId])
  const form = useForm<ReassignInput, unknown, { assigneeUserId: string; reason: string }>({
    resolver: zodResolver(schema),
    defaultValues: { assigneeUserId: '', reason: '' },
  })
  const targets = (agents.data ?? []).filter((a) => a.userId !== detail.assignedUserId)

  // Review M3: while open the dialog stays mounted even if a refetch removed the action; only the trigger hides.
  const hidden = !allowed && !open

  const submit = form.handleSubmit((values) =>
    guard.run(async () => {
      setRoot(null)
      try {
        const version = queryClient.getQueryData<OutboundCaseDetail>(outboundKeys.case(detail.reference))?.version ?? detail.version
        const updated = await reassign.mutateAsync({ assigneeUserId: Number(values.assigneeUserId), reason: values.reason, version })
        toast.success(`${detail.reference} reassigned to ${updated.assigneeName ?? 'the agent'}`)
        setOpen(false)
        form.reset({ assigneeUserId: '', reason: '' })
      } catch (error) {
        const reason = problemReason(error)
        if (reason === 'invalid_assignee' || reason === 'no_change') {
          form.setError('assigneeUserId', { type: 'server', message: caseProblemText(reason) ?? '' })
          if (reason === 'invalid_assignee') void agents.refetch()
        } else if (error instanceof ApiError && error.status === 400 && error.fieldErrors.length > 0) {
          for (const v of error.fieldErrors) {
            if (v.field === 'reason' || v.field === 'assigneeUserId') form.setError(v.field, { type: 'server', message: v.message })
          }
        } else if (reason === 'case_closed') {
          setRoot({ message: "Resolved or closed cases can't be reassigned.", error: error as ApiError })
        } else {
          setRoot({
            message: (reason && caseProblemText(reason)) ?? (error instanceof ApiError ? error.message : GENERIC_ERROR_MESSAGE),
            error: error instanceof ApiError ? error : null,
          })
        }
        if (reason === 'stale_version' || reason === 'case_closed') {
          void queryClient.invalidateQueries({ queryKey: outboundKeys.case(detail.reference) })
        }
      }
    }),
  )

  if (hidden) return null
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      {allowed ? (
        <DialogTrigger asChild>
          <Button type="button" variant="outline">
            Reassign
          </Button>
        </DialogTrigger>
      ) : null}
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Reassign {detail.reference}</DialogTitle>
          <DialogDescription>
            Currently: {detail.assigneeName ?? 'Unassigned'} · {statusLabel({ kind: 'caseInternal', value: detail.status })}
          </DialogDescription>
        </DialogHeader>
        <Form {...form}>
          <form noValidate onSubmit={submit} className="flex flex-col gap-4">
            <FormRootError message={root?.message} error={root?.error ?? null} />
            <FormField
              control={form.control}
              name="assigneeUserId"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Assign to</FormLabel>
                  <Select value={field.value} onValueChange={field.onChange}>
                    <FormControl>
                      <SelectTrigger>
                        <SelectValue placeholder="Choose an agent" />
                      </SelectTrigger>
                    </FormControl>
                    <SelectContent>
                      {targets.map((agent) => (
                        <SelectItem key={agent.userId} value={String(agent.userId)}>
                          {agent.displayName}
                          {agent.teamName ? ` — ${agent.teamName}` : ''}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField
              control={form.control}
              name="reason"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Reason</FormLabel>
                  <FormControl>
                    <Textarea rows={3} maxLength={500} {...field} />
                  </FormControl>
                  <p className="text-sm text-muted-foreground">Saved in the audit log. Don&apos;t include personal data.</p>
                  <FormMessage />
                </FormItem>
              )}
            />
            <DialogFooter>
              <Button type="submit" disabled={guard.busy}>
                {guard.busy ? 'Reassigning…' : 'Reassign'}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  )
}
