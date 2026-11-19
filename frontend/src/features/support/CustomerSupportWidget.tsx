import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { errorMessage } from '../../shared/api/client'
import { Alert, Button, Field } from '../../shared/ui'
import { useAuth } from '../auth/AuthProvider'
import { hotelToday } from '../rooms/search'
import { supportApi } from './api'
import type { ChatMessage, MessageDraft } from './api'
import { SupportMessages, conversationLabels } from './SupportMessages'
import { useSupportSocket } from './useSupportSocket'
import { useSupportThreads } from './useSupportThreads'
import './support.css'

const welcome = 'Xin chào! Tôi có thể giúp bạn tìm phòng, kiểm tra giá và giải đáp thắc mắc về khách sạn. Bạn cũng có thể yêu cầu gặp nhân viên hỗ trợ.'
const intentKey = 'hotel-pms.support-intent'
type AskAction = 'CHAT' | 'ROOMS' | 'POLICY' | 'BOOKINGS'

function returning() {
  try { return sessionStorage.getItem(intentKey) } catch { return null }
}

export function CustomerSupportWidget() {
  const { status, user } = useAuth()
  if (status === 'loading') return null
  const identity = user?.roles.includes('CUSTOMER') ? user.email : undefined
  return <Widget key={identity ?? 'guest'} identity={identity} />
}

function Widget({ identity }: { identity?: string }) {
  const [open, setOpen] = useState(() => Boolean(identity && returning()))
  const [started, setStarted] = useState(open)
  const [text, setText] = useState('')
  const [sending, setSending] = useState(false)
  const [changing, setChanging] = useState(false)
  const [error, setError] = useState('')
  const [local, setLocal] = useState<ChatMessage[]>([])
  const [search, setSearch] = useState(false)
  const [history, setHistory] = useState(false)
  const [intent, setIntent] = useState<string | null>(() => identity ? returning() : null)
  const draft = useRef<MessageDraft | undefined>(undefined)
  const activity = useRef(false)
  const launcher = useRef<HTMLButtonElement>(null)
  const input = useRef<HTMLTextAreaElement>(null)
  const location = useLocation()
  const navigate = useNavigate()
  const threads = useSupportThreads(false, Boolean(identity && started), open)
  const { selected, select, update } = threads
  const socket = useSupportSocket(started ? identity : undefined, () => { void update() })
  const aiMode = !selected || selected.status === 'AI_ACTIVE'
  const staffMode = selected?.status === 'WAITING_STAFF' || selected?.status === 'STAFF_ACTIVE'
  const from = location.pathname + location.search + location.hash

  useEffect(() => {
    if (open) input.current?.focus()
  }, [open])

  function remember(value: string) {
    if (identity) return
    try { sessionStorage.setItem(intentKey, value) } catch { /* The user can reopen chat manually. */ }
  }

  function clearIntent() {
    setIntent(null)
    try { sessionStorage.removeItem(intentKey) } catch { /* Storage may be blocked. */ }
  }

  function minimize() {
    setOpen(false)
    launcher.current?.focus()
  }

  function openMyBookings() {
    minimize()
    navigate('/my-bookings')
  }

  async function send(value: string, action: AskAction = 'CHAT', dates?: { arrival: string; departure: string; guests: number }) {
    const content = value.trim()
    if (!content || activity.current) return
    activity.current = true
    const clientId = draft.current?.content === content ? draft.current.clientId : crypto.randomUUID()
    draft.current = { clientId, content }
    setSending(true)
    setError('')
    try {
      if (identity && staffMode && selected) {
        await socket.send(selected.id, false, { clientId, content }, () => supportApi.send(false, selected.id, { clientId, content }))
      } else {
        const thread = identity ? selected?.status === 'AI_ACTIVE' ? selected : await supportApi.open() : undefined
        if (thread && thread.id !== selected?.id) await select(thread)
        if (thread && thread.status !== 'AI_ACTIVE') {
          if (action === 'BOOKINGS') { openMyBookings(); return }
          throw new Error('Chế độ hỗ trợ vừa thay đổi. Bạn có thể gửi lại tin nhắn trong cuộc trò chuyện hiện tại.')
        }
        const reply = await supportApi.ask({ conversationId: thread?.id, clientId, text: content, action, ...dates }, !identity)
        if (!thread && !reply.suppressed) {
          const createdAt = new Date().toISOString()
          setLocal(items => {
            const id = (items.at(-1)?.id ?? 0) + 1
            return [...items, { id, sender: 'CUSTOMER', content, createdAt },
              { id: id + 1, sender: 'AI', content: reply.text, details: reply, createdAt }].slice(-40) as ChatMessage[]
          })
        }
      }
      setText('')
      draft.current = undefined
    } catch (reason) { setError(errorMessage(reason)) }
    finally {
      activity.current = false
      setSending(false)
      if (identity) void update()
      input.current?.focus()
    }
  }

  async function handoff() {
    if (!identity) { setIntent('staff'); remember('staff'); return }
    if (changing) return
    setChanging(true)
    setError('')
    try {
      const thread = selected && selected.status !== 'CLOSED' ? selected : await supportApi.open()
      await select(await supportApi.handoff(thread.id))
      void update()
      clearIntent()
    } catch (reason) { setError(errorMessage(reason)) }
    finally { setChanging(false) }
  }

  function roomSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const fields = new FormData(event.currentTarget)
    const dates = { arrival: String(fields.get('arrival')), departure: String(fields.get('departure')), guests: Number(fields.get('guests')) }
    setSearch(false)
    void send(`Tìm phòng cho ${dates.guests} khách từ ${dates.arrival} đến ${dates.departure}.`, 'ROOMS', dates)
  }

  function showBookings() {
    if (!identity) { setIntent('bookings'); remember('bookings'); return }
    clearIntent()
    if (staffMode) { openMyBookings(); return }
    void send('Đơn đặt phòng của tôi', 'BOOKINGS')
  }

  async function startNew() {
    try { setError(''); await select(await supportApi.open()); void update() }
    catch (reason) { setError(errorMessage(reason)) }
  }

  const unread = threads.threads.reduce((total, item) => total + item.unread, 0)
  return <aside className="customer-support" aria-label="Hỗ trợ Hotel PMS">
    {open && <section id="support-panel" className="support-panel" aria-label="Trò chuyện với Hotel PMS" onKeyDown={event => {
      if (event.key === 'Escape') minimize()
    }}>
      <header className="support-header">
        <div><h2>Hỗ trợ Hotel PMS</h2><p>{selected ? conversationLabels[selected.status] : 'Trợ lý khách sạn'}</p></div>
        <button type="button" aria-label="Thu gọn trò chuyện" onClick={minimize}>×</button>
      </header>
      <div className="support-controls">
        {aiMode && threads.status && !threads.status.aiConfigured && <p className="small muted">AI chưa được cấu hình. Bạn vẫn có thể tra cứu và nhắn nhân viên.</p>}
        {aiMode && threads.status && <p className="small muted" role="status">{threads.status.staffOnline
          ? `${threads.status.staffOnline} nhân viên đang trực tuyến` : 'Hiện chưa có nhân viên trực tuyến'}</p>}
        {selected?.status === 'WAITING_STAFF' && <p role="status">{threads.status?.staffOnline
          ? 'Yêu cầu của bạn đã được gửi đến nhân viên hỗ trợ.'
          : 'Hiện chưa có nhân viên trực tuyến. Tin nhắn sẽ hiển thị khi nhân viên truy cập hệ thống.'}</p>}
        {selected?.status === 'STAFF_ACTIVE' && <p role="status">{selected.staffOnline ?
          'Nhân viên đang trực tuyến.' : 'Nhân viên phụ trách đã ngắt kết nối. Bạn vẫn có thể để lại tin nhắn.'}</p>}
        {identity && <button className="support-text-button" onClick={() => setHistory(value => !value)} aria-expanded={history}>Lịch sử hỗ trợ</button>}
        {history && <div className="support-history">{threads.threads.map(thread => <button key={thread.id} onClick={() => {
          void select(thread); setHistory(false)
        }}>{conversationLabels[thread.status]} · {thread.preview || 'Chưa có tin nhắn'}{thread.unread > 0 && ` · ${thread.unread} mới`}</button>)}
          {threads.moreThreads && <button onClick={() => void threads.loadThreads()}>Xem thêm cuộc trò chuyện</button>}</div>}
        {intent === 'staff' && (identity ? <Button onClick={() => void handoff()} busy={changing}>Gửi yêu cầu gặp nhân viên</Button> :
          <Link to="/login" state={{ from }} onClick={minimize}>Đăng nhập để gặp nhân viên</Link>)}
        {intent === 'bookings' && (identity ? <Button onClick={showBookings} busy={sending}>Xem đơn đặt phòng của tôi</Button> :
          <Link to="/login" state={{ from }} onClick={minimize}>Đăng nhập để xem booking</Link>)}
        {(error || threads.error) && <Alert>{error || threads.error}</Alert>}
      </div>
      <SupportMessages key={selected?.id ?? 'guest'} messages={identity ? threads.messages : local} older={threads.older}
        onOlder={() => void threads.loadOlder()} onNavigate={minimize} onLogin={() => { remember('bookings'); minimize() }}
        pending={sending} welcome={welcome} />
      <div className="support-controls support-controls--bottom">
        {selected?.status === 'CLOSED' ? <Button onClick={() => void startNew()}>Bắt đầu cuộc trò chuyện mới</Button> : <>
          {aiMode && <div className="support-quick" aria-label="Thao tác nhanh">
            <button onClick={() => setSearch(value => !value)} aria-expanded={search}>Tìm phòng trống</button>
            <button onClick={() => setSearch(true)}>Xem giá phòng</button>
            <button disabled={sending} onClick={() => void send('Giờ nhận phòng và trả phòng là mấy giờ?', 'POLICY')}>Giờ nhận / trả phòng</button>
            <button disabled={sending} onClick={showBookings}>Kiểm tra đơn đặt phòng</button>
            <button disabled={changing} onClick={() => void handoff()}>Gặp nhân viên</button>
          </div>}
          {search && aiMode && <form className="support-stay" onSubmit={roomSearch}>
            <Field label="Ngày đến" type="date" name="arrival" min={hotelToday()} required />
            <Field label="Ngày đi" type="date" name="departure" min={hotelToday()} required />
            <Field label="Số khách" type="number" name="guests" min={1} max={20} defaultValue={2} required />
            <Button type="submit" disabled={sending}>Tìm phòng và giá</Button>
          </form>}
          <form className="support-composer" onSubmit={event => { event.preventDefault(); void send(text) }}>
            <textarea ref={input} value={text} onChange={event => setText(event.target.value)}
              maxLength={1200} rows={2} aria-label="Tin nhắn của bạn" placeholder="Bạn cần hỗ trợ gì?" />
            <Button type="submit" busy={sending} disabled={!text.trim()}>Gửi</Button>
          </form>
          {identity && !socket.connected && !aiMode && <p className="small muted" role="status">Đang kết nối lại hỗ trợ trực tuyến…</p>}
        </>}
      </div>
    </section>}
    <button ref={launcher} className="support-launcher" type="button" aria-expanded={open} aria-controls="support-panel"
      onClick={() => { if (open) minimize(); else { setStarted(true); setOpen(true) } }}>
      <svg viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true">
        <path d="M20 11.5a8 8 0 0 1-8.5 8l-5.8 1.8.9-4a8 8 0 1 1 13.4-5.8Z" /><path d="M8 11.5h8m-8 3h5" />
      </svg>
      Hỗ trợ{unread > 0 && <span className="support-count">{unread}</span>}
    </button>
  </aside>
}
