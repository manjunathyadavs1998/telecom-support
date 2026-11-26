import { Slot } from 'radix-ui'
import { createContext, use, useId, type ComponentProps } from 'react'
import {
  Controller,
  FormProvider,
  useFormContext,
  useFormState,
  type ControllerProps,
  type FieldPath,
  type FieldValues,
} from 'react-hook-form'
import { cn } from '@/lib/utils'
import { Label } from './label'

/**
 * shadcn/ui Form (frontend.md §4, §6): React Hook Form wiring for labelled fields. `FormControl` links the control to its
 * label, sets `aria-invalid` and points `aria-describedby` at the field's message only while it shows an error.
 */
export const Form = FormProvider

const FormFieldContext = createContext<{ name: string } | null>(null)
const FormItemContext = createContext<{ id: string } | null>(null)

export function FormField<
  TFieldValues extends FieldValues = FieldValues,
  TName extends FieldPath<TFieldValues> = FieldPath<TFieldValues>,
  TTransformedValues = TFieldValues,
>(props: ControllerProps<TFieldValues, TName, TTransformedValues>) {
  return (
    <FormFieldContext value={{ name: props.name }}>
      <Controller {...props} />
    </FormFieldContext>
  )
}

function useFormField() {
  const field = use(FormFieldContext)
  const item = use(FormItemContext)
  if (!field || !item) throw new Error('useFormField must be used inside <FormField> and <FormItem>')
  const { getFieldState } = useFormContext()
  const formState = useFormState({ name: field.name })
  const { error } = getFieldState(field.name, formState)
  return { error, formItemId: `${item.id}-control`, formMessageId: `${item.id}-message` }
}

export function FormItem({ className, ...props }: ComponentProps<'div'>) {
  const id = useId()
  return (
    <FormItemContext value={{ id }}>
      <div className={cn('flex flex-col gap-1.5', className)} {...props} />
    </FormItemContext>
  )
}

export function FormLabel(props: ComponentProps<typeof Label>) {
  const { formItemId } = useFormField()
  return <Label htmlFor={formItemId} {...props} />
}

export function FormControl(props: ComponentProps<typeof Slot.Root>) {
  const { error, formItemId, formMessageId } = useFormField()
  return (
    <Slot.Root
      id={formItemId}
      aria-invalid={error ? true : undefined}
      aria-describedby={error ? formMessageId : undefined}
      {...props}
    />
  )
}

export function FormMessage({ className, ...props }: ComponentProps<'p'>) {
  const { error, formMessageId } = useFormField()
  if (!error?.message) return null
  return (
    <p id={formMessageId} className={cn('text-sm text-destructive', className)} {...props}>
      {error.message}
    </p>
  )
}
