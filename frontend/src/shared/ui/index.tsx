import { useId } from 'react'
import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Icon } from './Icon'
import type { IconName } from './Icon'

export { Select, Tabs, Skeleton, PriceDisplay, DataTable, Pagination } from './primitives'
export { Dialog, ConfirmDialog, ToastProvider, useToast } from './Feedback'

type Variant = 'primary' | 'secondary' | 'ghost'
type Tone = 'neutral' | 'success' | 'warning' | 'danger'

export function Button({ variant = 'primary', busy, children, className = '', disabled, ...props }:
  ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; busy?: boolean }) {
  return <button type="button" className={`button button--${variant} ${className}`} disabled={disabled || busy}
    aria-busy={busy || undefined} {...props}>{busy && <span className="spinner" aria-hidden="true" />}{children}</button>
}
export function LinkButton({ to, children, variant = 'primary' }: { to: string; children: ReactNode; variant?: Variant }) {
  return <Link to={to} className={`button button--${variant}`}>{children}</Link>
}
export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: Tone }) {
  return <span className={`badge badge--${tone}`}>{children}</span>
}
export function Card({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <section className={`card ${className}`}>{children}</section>
}
export function PageHeader({ eyebrow, title, description, action }: {
  eyebrow?: string; title: string; description: string; action?: ReactNode
}) {
  return <div className="page-heading"><div>{eyebrow && <p className="eyebrow">{eyebrow}</p>}
    <h1>{title}</h1><p className="muted">{description}</p></div>{action}</div>
}
export function EmptyState({ icon = 'bed', title, description, action }: {
  icon?: IconName; title: string; description: string; action?: ReactNode
}) {
  return <div className="empty-state"><span className="empty-icon"><Icon name={icon} size={28} /></span>
    <h2>{title}</h2><p className="muted">{description}</p>{action}</div>
}
export function Alert({ children, tone = 'danger' }: { children: ReactNode; tone?: Tone }) {
  return <div className={`alert alert--${tone}`} role={tone === 'danger' ? 'alert' : 'status'}>
    <Icon name="info" /><div>{children}</div></div>
}
export function Field({ label, hint, error, endAdornment, ...input }: InputHTMLAttributes<HTMLInputElement> & {
  label: string; hint?: string; error?: string; endAdornment?: ReactNode
}) {
  const generatedId = useId()
  const id = input.id ?? generatedId
  const descriptionId = `${id}-description`
  return <div className="field"><label htmlFor={id}>{label}{input.required && <span className="required-mark"> *</span>}</label>
    <div className={`input-wrap ${error ? 'input-wrap--error' : ''}`}>
      <input {...input} id={id} aria-invalid={error ? true : undefined}
        aria-describedby={error || hint ? descriptionId : undefined} />{endAdornment}</div>
    {(error || hint) && <p id={descriptionId} className={error ? 'field-error' : 'field-hint'}>{error || hint}</p>}
  </div>
}
export function LoadingState({ message = 'Đang tải dữ liệu…' }: { message?: string }) {
  return <div className="loading-state" role="status"><span className="spinner" />{message}</div>
}
