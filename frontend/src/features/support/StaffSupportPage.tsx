import { useRef, useState } from 'react'
import { Alert, Badge, Button, Card, LoadingState, PageHeader, Tabs } from '../../shared/ui'
import { errorMessage } from '../../shared/api/client'
import { useAuth } from '../auth/AuthProvider'
import { formatDateTime } from '../booking/types'
import { supportApi } from './api'
import type { Conversation, MessageDraft } from './api'
import { SupportMessages, conversationLabels } from './SupportMessages'
import { useSupportSocket } from './useSupportSocket'
import { useSupportThreads } from './useSupportThreads'
import './support.css'

export function StaffSupportPage() {
  const { user } = useAuth()
  const data = useSupportThreads(true, true)
  const socket = useSupportSocket(user?.email, () => { void data.update() })
  const [tab, setTab] = useState<'waiting' | 'active' | 'closed'>('waiting')
  const [content, setContent] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [showThread, setShowThread] = useState(false)
  const pending = useRef<MessageDraft | undefined>(undefined)
  const { selected } = data
  const waiting = data.threads.filter(item => item.status === 'WAITING_STAFF')
  const active = data.threads.filter(item => item.status === 'STAFF_ACTIVE')
  const threads = tab === 'waiting' ? waiting : tab === 'active' ? active : data.threads.filter(item => item.status === 'CLOSED')
  const unread = (items: Conversation[]) => items.reduce((total, item) => total + item.unread, 0)

  async function change(command: 'claim' | 'close') {
    if (!selected || busy) return
    setBusy(true)
    setError('')
    try {
      await data.select(await supportApi[command](selected.id))
      setTab(command === 'claim' ? 'active' : 'closed')
    } catch (reason) { setError(errorMessage(reason)) }
    finally { setBusy(false); void data.update() }
  }

  async function reply() {
    const message = content.trim()
    if (!selected || !message || busy) return
    const clientId = pending.current?.content === message ? pending.current.clientId : crypto.randomUUID()
    pending.current = { clientId, content: message }
    setBusy(true)
    setError('')
    try {
      const body = { clientId, content: message }
      await socket.send(selected.id, true, body, () => supportApi.send(true, selected.id, body))
      setContent('')
      pending.current = undefined
    } catch (reason) { setError(errorMessage(reason)) }
    finally { setBusy(false); void data.update() }
  }

  return <>
    <PageHeader title="Hỗ trợ khách hàng" description="Nhận yêu cầu, trả lời khách và theo dõi lịch sử."
      action={<Badge tone={socket.connected ? 'success' : 'warning'}>{socket.connected ? 'Đang trực tuyến' : 'Đang kết nối lại'}</Badge>} />
    {error && <Alert>{error}</Alert>}
    {data.error && <Alert>{data.error} <button onClick={() => void data.update()}>Thử lại</button></Alert>}
    <div className={`support-inbox${showThread ? ' support-inbox--detail' : ''}`}>
      <Card className="support-inbox-list">
        <Tabs label="Trạng thái hội thoại" value={tab} onChange={setTab} items={[
          { value: 'waiting', label: `Chờ nhận (${waiting.length})` },
          { value: 'active', label: `Đang hỗ trợ (${active.length})` },
          { value: 'closed', label: 'Đã kết thúc' },
        ]} />
        <p className="small muted" role="status">{unread(threads)} tin nhắn chưa đọc</p>
        {data.loading ? <LoadingState /> : threads.length ? <ul className="support-thread-list">{threads.map(thread => <li key={thread.id}>
          <button type="button" aria-pressed={selected?.id === thread.id} disabled={busy} onClick={() => {
            setError(''); setContent(''); pending.current = undefined; setShowThread(true); void data.select(thread)
          }}>
            <strong>{thread.customerName}</strong>{thread.unread > 0 && <span className="support-count">{thread.unread}</span>}
            <span>{thread.preview || 'Chưa có tin nhắn'}</span><time dateTime={thread.updatedAt}>{formatDateTime(thread.updatedAt)}</time>
          </button>
        </li>)}</ul> : <p>Chưa có cuộc trò chuyện trong mục này.</p>}
        {data.moreThreads && <Button variant="secondary" onClick={() => void data.loadThreads()}>Xem thêm cuộc trò chuyện</Button>}
      </Card>
      <Card className="support-inbox-detail">
        {selected ? <>
          <Button className="support-mobile-back" variant="secondary" onClick={() => setShowThread(false)}>Về danh sách</Button>
          <div className="support-inbox-head">
            <div><h2>{selected.customerName}</h2><p>{conversationLabels[selected.status]}</p></div>
            {selected.status === 'WAITING_STAFF' && <Button busy={busy} onClick={() => void change('claim')}>Nhận cuộc trò chuyện</Button>}
            {selected.status === 'STAFF_ACTIVE' && selected.assignedToMe &&
              <Button variant="secondary" busy={busy} onClick={() => void change('close')}>Kết thúc cuộc trò chuyện</Button>}
          </div>
          {selected.status === 'WAITING_STAFF' ? <p>Nhận cuộc trò chuyện để xem lịch sử đầy đủ và trả lời.</p> :
            <SupportMessages key={selected.id} staff messages={data.messages} older={data.older} onOlder={() => void data.loadOlder()} />}
          {selected.status === 'STAFF_ACTIVE' && selected.assignedToMe && <form className="support-composer" onSubmit={event => { event.preventDefault(); void reply() }}>
            <textarea aria-label="Tin nhắn trả lời" placeholder="Nhập tin nhắn cho khách" rows={2} maxLength={1200} value={content}
              onChange={event => setContent(event.target.value)} />
            <Button type="submit" busy={busy} disabled={!content.trim()}>Gửi</Button>
          </form>}
        </> : <p>Chọn cuộc trò chuyện để xem yêu cầu hỗ trợ.</p>}
      </Card>
    </div>
  </>
}
