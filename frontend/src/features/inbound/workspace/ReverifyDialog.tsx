import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import type { CachedCheck } from '../verificationState'
import { VerifyPanel } from '../verification/VerifyPanel'

type ReverifyDialogProps = {
  open: boolean
  customerId: number
  /** True while a form on the page has unsaved input: Cancel asks first (§3.3). */
  dirty: boolean
  /** Lost verification: the dialog cannot be dismissed except by verifying or leaving. Voluntary "Verify again": it can. */
  required: boolean
  onVerified: (check: CachedCheck) => void
  /** Leave the customer (→ `/inbound`) when required, else just close. */
  onCancel: () => void
}

/**
 * Re-verification over the mounted page (03-inbound/frontend.md §3.3, OQ 1): modal, focus trapped, the page and its form input
 * stay mounted. The lookup token has always lapsed by then, so `VerifyPanel` normally opens on its Search step. No automatic
 * resubmit after success.
 */
export function ReverifyDialog({ open, customerId, dirty, required, onVerified, onCancel }: ReverifyDialogProps) {
  const [confirmLeave, setConfirmLeave] = useState(false)

  const cancel = () => {
    if (required && dirty) setConfirmLeave(true)
    else onCancel()
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) cancel()
      }}
    >
      <DialogContent className="max-w-xl" closeLabel={required ? 'Leave this customer' : 'Close'}>
        <DialogHeader>
          <DialogTitle>{required ? 'Verification ended' : 'Verify again'}</DialogTitle>
          <DialogDescription>
            {required
              ? 'Verify the caller again to keep working. What you typed on the page is kept.'
              : 'Start a new 30-minute verification for this caller.'}
          </DialogDescription>
        </DialogHeader>
        {confirmLeave ? (
          <div className="flex flex-col gap-4">
            <p>Leave this customer? What you typed on the page will be lost.</p>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setConfirmLeave(false)}>
                Stay
              </Button>
              <Button type="button" onClick={onCancel}>
                Leave
              </Button>
            </DialogFooter>
          </div>
        ) : (
          <>
            {open ? <VerifyPanel customerId={customerId} onVerified={onVerified} /> : null}
            <DialogFooter>
              <Button type="button" variant="outline" onClick={cancel}>
                {required ? 'Leave this customer' : 'Cancel'}
              </Button>
            </DialogFooter>
          </>
        )}
      </DialogContent>
    </Dialog>
  )
}
