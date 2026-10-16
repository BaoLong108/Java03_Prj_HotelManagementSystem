import { createContext, useCallback, useContext, useEffect, useId, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { Icon } from './Icon'

export function Dialog({ open, onClose, title, children }: { open: boolean; onClose: () => void; title: string; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  useEffect(() => {
    const dialog = ref.current
    if (!open || !dialog) return
    const previouslyFocused = document.activeElement as HTMLElement | null
    dialog.showModal()
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => { dialog.close(); document.body.style.overflow = overflow; previouslyFocused?.focus() }
  }, [open])
  return <dialog ref={ref} className="dialog" aria-labelledby={titleId} onCancel={(event) => { event.preventDefault(); onClose() }}
    onClick={(event) => { if (event.target === event.currentTarget) { const rect = event.currentTarget.getBoundingClientRect(); if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) onClose() } }}>
    <div className="dialog-heading"><h2 id={titleId}>{title}</h2><button type="button" className="icon-button" aria-label="Đóng hộp thoại" onClick={onClose}><Icon name="close" /></button></div>{children}
  </dialog>
}

export function ConfirmDialog({ open, title, description, onConfirm, onClose, confirmLabel = 'Xác nhận' }: {
  open: boolean; title: string; description: string; onConfirm: () => void; onClose: () => void; confirmLabel?: string
}) {
  return <Dialog open={open} title={title} onClose={onClose}><p className="muted">{description}</p><div className="dialog-actions">
    <button className="button button--secondary" onClick={onClose} autoFocus>Quay lại</button>
    <button className="button button--primary" onClick={() => { onConfirm(); onClose() }}>{confirmLabel}</button>
  </div></Dialog>
}

type ToastMessage = { id: number; message: string }
const ToastContext = createContext<((message: string) => void) | null>(null)
export function ToastProvider({ children }: { children: ReactNode }) {
  const [messages, setMessages] = useState<ToastMessage[]>([])
  const timers = useRef(new Map<number, number>())
  const serial = useRef(0)
  const dismiss = useCallback((id: number) => { window.clearTimeout(timers.current.get(id)); timers.current.delete(id); setMessages((items) => items.filter((item) => item.id !== id)) }, [])
  const notify = useCallback((message: string) => {
    const id = ++serial.current
    setMessages((items) => [...items, { id, message }])
    timers.current.set(id, window.setTimeout(() => dismiss(id), 5000))
  }, [dismiss])
  useEffect(() => { const active = timers.current; return () => { active.forEach(window.clearTimeout); active.clear() } }, [])
  return <ToastContext.Provider value={notify}>{children}<div className="toast-stack" aria-live="polite" aria-atomic="false">{messages.map((item) =>
    <div className="toast" key={item.id}><Icon name="check" /><p>{item.message}</p><button type="button" className="icon-button" onClick={() => dismiss(item.id)} aria-label="Đóng thông báo"><Icon name="close" size={16} /></button></div>)}</div></ToastContext.Provider>
}
export function useToast() { const notify = useContext(ToastContext); if (!notify) throw new Error('useToast requires ToastProvider'); return notify }
