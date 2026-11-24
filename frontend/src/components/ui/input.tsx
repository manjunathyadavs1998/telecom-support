import type { InputHTMLAttributes, SelectHTMLAttributes, TextareaHTMLAttributes } from 'react'
import { cn } from '@/utils/cn'

const base =
  'w-full rounded-lg border border-border bg-white px-3 text-sm text-slate-800 placeholder:text-slate-400 focus-visible:border-primary focus-visible:outline-2 focus-visible:outline-primary/30 disabled:bg-slate-50 disabled:text-slate-500 aria-[invalid=true]:border-danger'

export function Input({ className, ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return <input className={cn(base, 'h-10', className)} {...props} />
}

export function Textarea({ className, ...props }: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea className={cn(base, 'min-h-24 py-2', className)} {...props} />
}

export function Select({ className, children, ...props }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select className={cn(base, 'h-10 pr-8', className)} {...props}>
      {children}
    </select>
  )
}
