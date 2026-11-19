import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { errorMessage } from '../../shared/api/client'
import { Alert, Badge, Button, Card, Field, LoadingState, PageHeader } from '../../shared/ui'
import { formatDateTime } from '../booking/types'
import { supportApi } from './api'
import type { Faq, FaqDraft } from './api'
import './support.css'

const categories = ['Nhận / trả phòng', 'Đặt phòng', 'Hủy phòng', 'Thanh toán', 'Thành viên', 'Tiện ích', 'Thông tin chung']
const empty: FaqDraft = { category: categories[0], question: '', answer: '', active: true }

export function AdminFaqPage() {
  const [entries, setEntries] = useState<Faq[]>([])
  const [filter, setFilter] = useState('')
  const [query, setQuery] = useState('')
  const [draft, setDraft] = useState<FaqDraft>(empty)
  const [editing, setEditing] = useState<number>()
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [more, setMore] = useState(false)
  const [reload, setReload] = useState(0)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  useEffect(() => {
    let obsolete = false
    setLoading(true)
    supportApi.faqList(query).then(page => {
      if (!obsolete) { setEntries(page); setMore(page.length === 40) }
    }).catch(reason => { if (!obsolete) setError(errorMessage(reason)) })
      .finally(() => { if (!obsolete) setLoading(false) })
    return () => { obsolete = true }
  }, [query, reload])

  async function save(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    setBusy(true); setError(''); setNotice('')
    try {
      await supportApi.saveFaq(draft, editing)
      setNotice(editing ? 'Đã cập nhật FAQ.' : 'Đã tạo FAQ.')
      setEditing(undefined); setDraft(empty); setReload(value => value + 1)
    } catch (reason) { setError(errorMessage(reason)) }
    finally { setBusy(false) }
  }

  return <>
    <PageHeader title="FAQ và chính sách khách sạn" description="Quản lý câu trả lời công khai cho trợ lý khách sạn." />
    {error && <Alert>{error}</Alert>}{notice && <Alert tone="success">{notice}</Alert>}
    <div className="support-faq">
      <Card>
        <form className="support-actions" role="search" onSubmit={event => { event.preventDefault(); setError(''); setQuery(filter); setReload(value => value + 1) }}>
          <Field label="Tìm câu hỏi hoặc danh mục" type="search" maxLength={120} value={filter} onChange={event => setFilter(event.target.value)} />
          <Button type="submit" variant="secondary">Tìm FAQ</Button>
        </form>
        {loading ? <LoadingState /> : !entries.length ? <p>Chưa có câu hỏi phù hợp.</p> : <ul className="support-faq-list">{entries.map(entry => <li key={entry.id}>
          <div><Badge tone={entry.active ? 'success' : 'neutral'}>{entry.active ? 'Đang công khai' : 'Đã ẩn'}</Badge><p className="small muted">{entry.category}</p>
            <strong>{entry.question}</strong><p>{entry.answer}</p><time className="small muted" dateTime={entry.updatedAt}>{formatDateTime(entry.updatedAt)}</time></div>
          <Button variant="secondary" disabled={busy} onClick={() => {
            setDraft({ category: entry.category, question: entry.question, answer: entry.answer, active: entry.active })
            setEditing(entry.id); setNotice('')
          }}>Sửa</Button>
        </li>)}</ul>}
        {more && <Button variant="secondary" onClick={async () => {
          try {
            const next = await supportApi.faqList(query, entries.at(-1)?.id)
            setEntries(current => [...new Map([...current, ...next].map(entry => [entry.id, entry])).values()])
            setMore(next.length === 40)
          } catch (reason) { setError(errorMessage(reason)) }
        }}>Xem thêm</Button>}
      </Card>
      <Card><h2>{editing ? 'Sửa FAQ' : 'Tạo FAQ'}</h2>
        <form className="support-faq-form" onSubmit={save}>
          <label className="field">Danh mục<select value={draft.category} onChange={event => setDraft({ ...draft, category: event.target.value })}>
            {[...new Set([...categories, draft.category])].map(category => <option key={category}>{category}</option>)}
          </select></label>
          <Field label="Câu hỏi" required maxLength={240} value={draft.question} onChange={event => setDraft({ ...draft, question: event.target.value })} />
          <label className="field">Câu trả lời<textarea required rows={5} maxLength={1600} value={draft.answer}
            onChange={event => setDraft({ ...draft, answer: event.target.value })} /></label>
          <label><input type="checkbox" checked={draft.active} onChange={event => setDraft({ ...draft, active: event.target.checked })} /> Công khai cho trợ lý</label>
          {draft.answer && <div className="support-preview"><strong>Xem trước câu trả lời</strong><p>{draft.answer}</p></div>}
          <div className="support-actions"><Button type="submit" busy={busy}>Lưu FAQ</Button>
            {editing && <Button variant="secondary" onClick={() => { setEditing(undefined); setDraft(empty) }}>Hủy sửa</Button>}</div>
        </form>
      </Card>
    </div>
  </>
}
