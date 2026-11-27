import type { ReactNode } from 'react'
import { Table, TableBody, TableCaption, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { cn } from '@/lib/utils'

export type DataTableColumn<T> = {
  id: string
  header: ReactNode
  cell: (row: T) => ReactNode
  /** Label in the stacked card (< md); defaults to `header`. */
  mobileLabel?: ReactNode
}

type DataTableProps<T> = {
  columns: DataTableColumn<T>[]
  rows: T[]
  /** Describes the table (`<caption>`) and names the card list (< md). */
  caption: string
  /** A page change is loading: `aria-busy` while the previous rows stay visible (keepPreviousData). */
  busy?: boolean
  /** Stable React key per row; defaults to its position. */
  rowKey?: (row: T, index: number) => string
  className?: string
}

/**
 * DataTable (frontend.md §4, NFR-RESP, XC-08): a semantic `<table>` with a `<caption>` from `md` up; below `md` each row is a stacked
 * card (`<dl>` of label/value pairs) in a list named by the caption. Both are in the DOM; CSS shows one, so only one is ever exposed to
 * assistive technology and the tab order.
 */
export function DataTable<T>({ columns, rows, caption, busy = false, rowKey, className }: DataTableProps<T>) {
  const keyOf = (row: T, index: number) => (rowKey ? rowKey(row, index) : String(index))
  return (
    <div aria-busy={busy} className={cn(busy && 'opacity-70 transition-opacity', className)}>
      <div className="hidden md:block" data-testid="data-table-wide">
        <Table>
          <TableCaption className="sr-only">{caption}</TableCaption>
          <TableHeader>
            <TableRow>
              {columns.map((column) => (
                <TableHead key={column.id}>{column.header}</TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((row, index) => (
              <TableRow key={keyOf(row, index)}>
                {columns.map((column) => (
                  <TableCell key={column.id}>{column.cell(row)}</TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      <ul aria-label={caption} className="flex flex-col gap-3 md:hidden" data-testid="data-table-cards">
        {rows.map((row, index) => (
          <li key={keyOf(row, index)} className="rounded-lg border border-slate-200 bg-white p-4">
            <dl className="grid grid-cols-[minmax(0,2fr)_minmax(0,3fr)] gap-x-3 gap-y-2 text-sm">
              {columns.map((column) => (
                <div key={column.id} className="contents">
                  <dt className="font-medium text-muted-foreground">{column.mobileLabel ?? column.header}</dt>
                  <dd className="min-w-0 break-words">{column.cell(row)}</dd>
                </div>
              ))}
            </dl>
          </li>
        ))}
      </ul>
    </div>
  )
}
