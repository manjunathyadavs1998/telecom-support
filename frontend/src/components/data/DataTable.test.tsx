import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { axe } from 'vitest-axe'
import { DataTable, type DataTableColumn } from './DataTable'

type Row = { reference: string; status: string; amount: string }

const rows: Row[] = [
  { reference: 'SR-20261125-000002', status: 'Open', amount: '₹999.00' },
  { reference: 'SR-20261124-000001', status: 'Closed', amount: '₹1,499.00' },
]

const columns: DataTableColumn<Row>[] = [
  { id: 'reference', header: 'Reference', cell: (row) => <a href={`/x/${row.reference}`}>{row.reference}</a> },
  { id: 'status', header: 'Status', cell: (row) => row.status },
  { id: 'amount', header: 'Amount (INR)', mobileLabel: 'Amount', cell: (row) => row.amount },
]

function renderTable(busy = false) {
  return render(
    <DataTable columns={columns} rows={rows} caption="My service requests" busy={busy} rowKey={(row) => row.reference} />,
  )
}

describe('DataTable (frontend.md §4, NFR-RESP, XC-08)', () => {
  it('≥ md: a captioned table with column headers and one row per item, shown only from md', () => {
    renderTable()
    const table = screen.getByRole('table', { name: 'My service requests' })
    expect(screen.getByTestId('data-table-wide')).toHaveClass('hidden', 'md:block')
    const headers = within(table).getAllByRole('columnheader')
    expect(headers.map((h) => h.textContent)).toEqual(['Reference', 'Status', 'Amount (INR)'])
    headers.forEach((h) => expect(h).toHaveAttribute('scope', 'col'))
    const bodyRows = within(table).getAllByRole('row').slice(1)
    expect(bodyRows).toHaveLength(2)
    expect(within(bodyRows[0]!).getByRole('link', { name: 'SR-20261125-000002' })).toBeInTheDocument()
    expect(within(bodyRows[1]!).getByText('Closed')).toBeInTheDocument()
  })

  it('< md: one stacked card per row with label/value pairs, hidden from md; mobileLabel overrides the header', () => {
    renderTable()
    const list = screen.getByRole('list', { name: 'My service requests' })
    expect(list).toHaveClass('md:hidden')
    const cards = within(list).getAllByRole('listitem')
    expect(cards).toHaveLength(2)
    const terms = within(cards[0]!).getAllByRole('term').map((t) => t.textContent)
    expect(terms).toEqual(['Reference', 'Status', 'Amount'])
    const values = within(cards[0]!).getAllByRole('definition').map((d) => d.textContent)
    expect(values).toEqual(['SR-20261125-000002', 'Open', '₹999.00'])
  })

  it('aria-busy reflects paging (keepPreviousData) while the previous rows stay visible', () => {
    const { container, rerender } = renderTable(true)
    const root = container.firstElementChild!
    expect(root).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByRole('table')).toBeInTheDocument()
    rerender(<DataTable columns={columns} rows={rows} caption="My service requests" />)
    expect(root).toHaveAttribute('aria-busy', 'false')
  })

  it('renders an empty body for no rows (the page shows EmptyState instead)', () => {
    render(<DataTable columns={columns} rows={[]} caption="Payments" />)
    expect(within(screen.getByRole('table')).getAllByRole('row')).toHaveLength(1)
  })

  it('is axe clean', async () => {
    const { container } = renderTable()
    expect(await axe(container)).toHaveNoViolations()
  })
})
