import { zodResolver } from '@hookform/resolvers/zod'
import type { UseQueryResult } from '@tanstack/react-query'
import { useEffect, useId, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { showErrorToast } from '@/app/queryClient'
import { SectionErrorState } from '@/components/states/SectionErrorState'
import { FormRootError } from '@/components/states/FormRootError'
import { LoadingState } from '@/components/states/LoadingState'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/services/api/errors'
import {
  IDEMPOTENCY_MISMATCH_MESSAGE,
  isIdempotencyMismatch,
  useIdempotencyKey,
  useSubmitGuard,
  type IdempotencyKey,
} from '@/services/api/idempotency'
import type { Promotion, Subscription } from '@/types/api'
import { promotionClaimPath, useClaimPromotion, useRefetchPromotions } from './queries'

export const OFFER_GONE_MESSAGE = 'This offer is no longer available.'
export const CLAIM_STILL_PROCESSING_MESSAGE = 'Your claim is still processing. Try again in a moment.'
export const NUMBERS_ERROR_TITLE = "We couldn't load your numbers"

// frontend.md §6 Promotion claim: `subscriptionId` required · `acceptTerms: z.literal(true)` (a checkbox starts unchecked).
const claimSchema = z.object({
  subscriptionId: z.string().min(1, 'Choose a number.').transform(Number),
  acceptTerms: z.boolean().pipe(z.literal(true, { error: 'Accept the terms and conditions to claim this offer.' })),
})

type ClaimInput = z.input<typeof claimSchema>
type ClaimOutput = z.output<typeof claimSchema>

type RootProblem = { message: string; error: ApiError | null }

type ClaimFormProps = {
  promotion: Promotion
  subscriptions: UseQueryResult<Subscription[]>
  idempotency: IdempotencyKey
  onDone: () => void
}

/** The dialog body; mounted only while the dialog is open, so every opening starts with a clean form. */
function ClaimForm({ promotion, subscriptions, idempotency, onDone }: ClaimFormProps) {
  const claim = useClaimPromotion()
  const refetchPromotions = useRefetchPromotions()
  const guard = useSubmitGuard()
  const termsHeadingId = useId()
  const numbersLabelId = useId()
  const eligible = promotion.eligibleSubscriptionIds
  const form = useForm<ClaimInput, unknown, ClaimOutput>({
    resolver: zodResolver(claimSchema),
    defaultValues: { subscriptionId: eligible.length === 1 ? String(eligible[0]) : '', acceptTerms: false },
  })
  const { control, handleSubmit, setValue } = form
  const [root, setRoot] = useState<RootProblem | null>(null)
  const rootRef = useRef<HTMLDivElement>(null)
  const [rootFocusRequest, setRootFocusRequest] = useState(0)

  useEffect(() => {
    if (rootFocusRequest > 0) rootRef.current?.focus()
  }, [rootFocusRequest])

  // New terms (a refetch after a stale-hash 422) must be accepted again: the acceptance was for the old text.
  const termsHash = promotion.termsHash
  const shownHash = useRef(termsHash)
  useEffect(() => {
    if (shownHash.current === termsHash) return
    shownHash.current = termsHash
    setValue('acceptTerms', false)
  }, [termsHash, setValue])

  function showRoot(message: string, error: ApiError | null) {
    setRoot({ message, error })
    setRootFocusRequest((n) => n + 1)
  }

  function showServerError(error: unknown) {
    if (!(error instanceof ApiError)) {
      showRoot(GENERIC_ERROR_MESSAGE, null)
      return
    }
    if (error.status === 403) return // the global mutation handler shows the forbidden toast (01 §7)
    if (isIdempotencyMismatch(error)) {
      showRoot(IDEMPOTENCY_MISMATCH_MESSAGE, error)
      return
    }
    switch (error.status) {
      case 404:
        refetchPromotions()
        showErrorToast(OFFER_GONE_MESSAGE, error.correlationId)
        onDone()
        return
      case 409:
        showRoot(CLAIM_STILL_PROCESSING_MESSAGE, error)
        return
      case 422:
        showRoot(error.message, error)
        refetchPromotions()
        return
      default:
        showRoot(GENERIC_ERROR_MESSAGE, error)
    }
  }

  const onSubmit = handleSubmit((input) =>
    guard.run(async () => {
      setRoot(null)
      const body = { subscriptionId: input.subscriptionId, acceptTerms: true as const, termsHash: promotion.termsHash }
      const idempotencyKey = idempotency.keyFor(body)
      try {
        await claim.mutateAsync({ promotionCode: promotion.code, idempotencyKey, body })
        idempotency.succeeded()
        toast.success('Offer claimed')
        onDone()
      } catch (error) {
        idempotency.failed(error)
        showServerError(error)
      }
    }),
  )

  const busy = guard.busy
  const byId = new Map((subscriptions.data ?? []).map((subscription) => [subscription.id, subscription]))

  return (
    <>
      <DialogHeader>
        <DialogTitle>{promotion.title}</DialogTitle>
        <DialogDescription>{promotion.shortDescription}</DialogDescription>
      </DialogHeader>
      <section aria-labelledby={termsHeadingId} className="flex flex-col gap-1">
        <h3 id={termsHeadingId} className="text-sm font-semibold">
          Terms and conditions
        </h3>
        <p className="rounded-md border bg-slate-50 p-3 text-sm whitespace-pre-line">{promotion.terms}</p>
      </section>
      {subscriptions.isPending ? (
        <LoadingState label="Loading your numbers" />
      ) : subscriptions.isError ? (
        <SectionErrorState
          title={NUMBERS_ERROR_TITLE}
          error={subscriptions.error}
          onRetry={() => void subscriptions.refetch()}
        />
      ) : (
        <Form {...form}>
          <form noValidate onSubmit={onSubmit} aria-label="Claim offer" aria-busy={busy} className="flex flex-col gap-4">
            <FormRootError ref={rootRef} message={root?.message} error={root?.error ?? null} />
            <FormField
              control={control}
              name="subscriptionId"
              render={({ field }) => (
                <FormItem>
                  <p id={numbersLabelId} className="text-sm font-medium">
                    Which number?
                  </p>
                  <FormControl>
                    <RadioGroup
                      aria-labelledby={numbersLabelId}
                      value={field.value}
                      onValueChange={field.onChange}
                      disabled={busy}
                      ref={field.ref}
                    >
                      {eligible.map((id) => {
                        const itemId = `${numbersLabelId}-${id}`
                        return (
                          <div key={id} className="flex items-center gap-2">
                            <RadioGroupItem id={itemId} value={String(id)} />
                            <Label htmlFor={itemId}>{byId.get(id)?.msisdn ?? `Number ${id}`}</Label>
                          </div>
                        )
                      })}
                    </RadioGroup>
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField
              control={control}
              name="acceptTerms"
              render={({ field }) => (
                <FormItem>
                  <div className="flex items-center gap-2">
                    <FormControl>
                      <Checkbox
                        checked={field.value}
                        onCheckedChange={(checked) => field.onChange(checked === true)}
                        onBlur={field.onBlur}
                        disabled={busy}
                        ref={field.ref}
                      />
                    </FormControl>
                    <FormLabel>I accept the terms and conditions</FormLabel>
                  </div>
                  <FormMessage />
                </FormItem>
              )}
            />
            <DialogFooter>
              <DialogClose asChild>
                <Button type="button" variant="outline" disabled={busy}>
                  Cancel
                </Button>
              </DialogClose>
              <Button type="submit" disabled={busy}>
                {busy ? 'Claiming…' : 'Confirm claim'}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      )}
    </>
  )
}

type ClaimDialogProps = {
  promotion: Promotion
  /** E5, for the MSISDN labels (frontend.md §3 Promotions). */
  subscriptions: UseQueryResult<Subscription[]>
}

/**
 * "View & claim" and its dialog (frontend.md §3 Promotions, §6; CUS-04.3/04.4, XC-06, NFR-REL): full terms, the number to claim
 * for (from `eligibleSubscriptionIds`, preselected when only one), the required terms acceptance, and Confirm → E9 with the
 * terms hash. Nothing is claimed without Confirm. The idempotency key is scoped to the promotion path (the body carries the
 * number) and lives here, so it survives closing and reopening the dialog. With no claimable number the CTA is disabled, and
 * reads "Claimed" once a number has claimed it.
 */
export function ClaimDialog({ promotion, subscriptions }: ClaimDialogProps) {
  const [open, setOpen] = useState(false)
  const idempotency = useIdempotencyKey('PROMOTION_CLAIM', promotionClaimPath(promotion.code))
  const claimable = promotion.eligibleSubscriptionIds.length > 0
  const label = claimable || promotion.claimedSubscriptionIds.length === 0 ? 'View & claim' : 'Claimed'
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button className="self-start" disabled={!claimable} aria-label={`${label}: ${promotion.title}`}>
          {label}
        </Button>
      </DialogTrigger>
      <DialogContent>
        <ClaimForm
          promotion={promotion}
          subscriptions={subscriptions}
          idempotency={idempotency}
          onDone={() => setOpen(false)}
        />
      </DialogContent>
    </Dialog>
  )
}
