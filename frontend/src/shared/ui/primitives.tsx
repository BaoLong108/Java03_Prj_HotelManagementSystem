import { useId } from 'react'
import type { ReactNode, SelectHTMLAttributes } from 'react'

export function Select({ label, children, ...props }: SelectHTMLAttributes<HTMLSelectElement> & { label: string }) {
  const generatedId = useId()
  const id = props.id ?? generatedId
  return <div className="field"><label htmlFor={id}>{label}</label><select {...props} id={id}>{children}</select></div>
}

// A button group that filters content; intentionally not an ARIA tab widget without tab panels.
export function Tabs<T extends string>({ items, value, onChange, label }: {
  items: { value: T; label: string }[]; value: T; onChange: (value: T) => void; label: string
}) {
  return <div className="tabs" role="group" aria-label={label}>{items.map((item) =>
    <button type="button" key={item.value} aria-pressed={item.value === value} onClick={() => onChange(item.value)}>{item.label}</button>)}</div>
}

export function Skeleton({ lines = 3 }: { lines?: number }) {
  return <div className="skeleton-group" role="status" aria-label="Đang tải dữ liệu">{Array.from({ length: lines }, (_, i) => <div key={i} className="skeleton" />)}</div>
}

export function PriceDisplay({ amount, suffix }: { amount?: number | null; suffix?: string }) {
  return <span className="price">{amount == null ? <span aria-label="Chưa có giá">—</span> : new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 }).format(amount)}{suffix && <small>{suffix}</small>}</span>
}

export function DataTable({ caption, columns, children, empty }: {
  caption: string; columns: string[]; children?: ReactNode; empty?: ReactNode
}) {
  return <div className="table-scroll" role="region" aria-label={caption} tabIndex={0}><table><caption className="sr-only">{caption}</caption>
    <thead><tr>{columns.map((column) => <th scope="col" key={column}>{column}</th>)}</tr></thead>
    <tbody>{children ?? <tr><td colSpan={columns.length}>{empty}</td></tr>}</tbody>
  </table></div>
}

export function Pagination({ page, pageCount, onChange }: { page: number; pageCount: number; onChange: (page: number) => void }) {
  if (pageCount <= 1) return null
  return <nav className="pagination" aria-label="Phân trang"><button type="button" className="button button--secondary button--small" disabled={page <= 1} onClick={() => onChange(page - 1)}>Trang trước</button><span aria-live="polite">Trang {page} / {pageCount}</span><button type="button" className="button button--secondary button--small" disabled={page >= pageCount} onClick={() => onChange(page + 1)}>Trang sau</button></nav>
}
