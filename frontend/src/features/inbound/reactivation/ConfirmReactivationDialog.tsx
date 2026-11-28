import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'

type ConfirmReactivationDialogProps = {
  open: boolean
  numbers: number
  hours: number
  onConfirm: () => void
  onCancel: () => void
}

/** §3.9: the confirmation before I14 (XC-07: nothing is restored until the server says so). */
export function ConfirmReactivationDialog({ open, numbers, hours, onConfirm, onCancel }: ConfirmReactivationDialogProps) {
  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        if (!next) onCancel()
      }}
    >
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>
            Restore {numbers} {numbers === 1 ? 'number' : 'numbers'} for {hours} {hours === 1 ? 'hour' : 'hours'}?
          </AlertDialogTitle>
          <AlertDialogDescription>Service is suspended again automatically when the time ends.</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction onClick={onConfirm}>Restore service</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
