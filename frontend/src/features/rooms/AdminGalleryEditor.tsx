import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import type { RoomImage, RoomTypeResponse } from './api'
import { adminRoomApi } from './adminApi'
import { apiAssetUrl, errorMessage } from '../../shared/api/client'
import { Alert, Badge, Button, Card, Field } from '../../shared/ui'
import './bookingPortal.css'

type Draft = { key: string; image?: RoomImage; file?: File }
export function galleryRevision(images: RoomImage[]) {
  return [...images].sort((a, b) => a.sortOrder - b.sortOrder).map(image => `${image.id}:${image.sortOrder}:${image.cover}`).join('|')
}
function Preview({ item }: { item: Draft }) {
  const [url, setUrl] = useState('')
  useEffect(() => {
    if (!item.file) return
    const objectUrl = URL.createObjectURL(item.file); setUrl(objectUrl)
    return () => URL.revokeObjectURL(objectUrl)
  }, [item.file])
  const src = item.file ? url : `${apiAssetUrl(item.image!.url)}?size=thumb`
  return src ? <img src={src} alt={item.file ? `Xem trước ${item.file.name}` : 'Ảnh đã lưu của hạng phòng'} /> : null
}

export function AdminGalleryEditor({ type, onSaved, onReload }: { type: RoomTypeResponse; onSaved: () => void; onReload: () => void }) {
  const initial = () => [...type.images].sort((a, b) => a.sortOrder - b.sortOrder).map(image => ({ key: `image-${image.id}`, image }))
  const [items, setItems] = useState<Draft[]>(initial)
  const [cover, setCover] = useState(() => `image-${type.images.find(image => image.cover)?.id}`)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  function validate(files: File[]) {
    if (!files.length || files.some(file => !['image/jpeg', 'image/png'].includes(file.type) || file.size === 0 || file.size > 5242880)) {
      setError('Chọn ảnh JPEG/PNG không rỗng, tối đa 5 MB mỗi ảnh.'); return false
    }
    setError(''); return true
  }
  function add(files: File[]) {
    if (!validate(files)) return
    if (items.length + files.length > 5) { setError('Mỗi hạng phòng có tối đa 5 ảnh. Xóa hoặc thay ảnh trước khi thêm.'); return }
    const added = files.map(file => ({ key: crypto.randomUUID(), file }))
    setItems(current => [...current, ...added]); if (!items.length) setCover(added[0].key)
  }
  function move(index: number, offset: number) {
    setItems(current => { const next = [...current]; [next[index], next[index + offset]] = [next[index + offset], next[index]]; return next })
  }
  async function save(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    const coverIndex = items.findIndex(item => item.key === cover)
    if (items.length > 5 || (items.length > 0 && coverIndex < 0)) { setError('Bộ ảnh có tối đa 5 ảnh và cần một ảnh bìa khi có ảnh.'); return }
    setBusy(true); setError('')
    const files: File[] = []
    const entries = items.map(item => item.image ? { imageId: item.image.id } : { uploadIndex: files.push(item.file!) - 1 })
    try { await adminRoomApi.gallery(type.id, { items: entries, coverIndex, expectedRevision: galleryRevision(type.images) }, files); onSaved() }
    catch (reason) { setError(errorMessage(reason)) } finally { setBusy(false) }
  }
  return <Card className="admin-gallery-editor"><h2>Ảnh hạng phòng · {type.name}</h2><p>Ảnh dùng chung cho tất cả phòng vật lý thuộc hạng này. Thay đổi chỉ được lưu khi bấm “Lưu bộ ảnh”.</p>
    <p className="small muted">1–5 ảnh JPEG/PNG, tối đa 5 MB/ảnh. Chọn ảnh bìa và dùng nút lên/xuống để đổi thứ tự ảnh khách nhìn thấy.</p>
    {error && <Alert>{error}</Alert>}<form onSubmit={save}>
      <div className="admin-gallery-grid">{items.map((item, index) => <div key={item.key} className="admin-gallery-item"><Preview item={item} /><div className="portal-heading"><strong>Ảnh {index + 1}</strong>{cover === item.key && <Badge tone="success">Ảnh bìa</Badge>}</div>
        <label><input type="radio" name="gallery-cover" checked={cover === item.key} disabled={busy} onChange={() => setCover(item.key)} /> Chọn làm ảnh bìa</label>
        <div className="button-row"><Button variant="secondary" disabled={busy || index === 0} aria-label={`Đưa ảnh ${index + 1} lên`} onClick={() => move(index, -1)}>Lên</Button><Button variant="secondary" disabled={busy || index === items.length - 1} aria-label={`Đưa ảnh ${index + 1} xuống`} onClick={() => move(index, 1)}>Xuống</Button>
          <Button variant="ghost" disabled={busy} aria-label={`Xóa ảnh ${index + 1}`} onClick={() => { const next = items.filter(value => value.key !== item.key); setItems(next); if (cover === item.key) setCover(next[0]?.key ?? '') }}>Xóa</Button></div>
        <Field label={`Thay ảnh ${index + 1}`} type="file" accept="image/jpeg,image/png" disabled={busy} onChange={event => {
          const file = event.target.files?.[0]; if (!file || !validate([file])) { event.target.value = ''; return }
          const replacement = { key: crypto.randomUUID(), file }
          setItems(current => current.map(value => value.key === item.key ? replacement : value)); if (cover === item.key) setCover(replacement.key)
        }} />
      </div>)}</div>
      {!items.length && <p>Chưa có ảnh. Thêm ảnh để tạo bộ sưu tập của hạng phòng.</p>}
      <Field label="Thêm ảnh vào bộ sưu tập" type="file" accept="image/jpeg,image/png" multiple disabled={busy || items.length === 5} onChange={event => { add(Array.from(event.target.files ?? [])); event.target.value = '' }} />
      <div className="button-row"><Button type="submit" busy={busy} disabled={!items.length && !type.images.length}>Lưu bộ ảnh</Button><Button variant="secondary" disabled={busy} onClick={() => {
        setItems(initial()); setCover(`image-${type.images.find(image => image.cover)?.id}`); setError(''); onReload()
      }}>Tải lại bộ ảnh</Button></div>
    </form></Card>
}

export function RoomAmenitiesEditor({ type, onSaved }: { type: RoomTypeResponse; onSaved: () => void }) {
  const [text, setText] = useState(() => (type.amenities ?? []).join('\n'))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function save(event: FormEvent) {
    event.preventDefault(); if (busy) return
    const values = [...new Set(text.split('\n').map(value => value.trim()).filter(Boolean))]
    if (values.length > 20 || values.some(value => value.length > 80)) { setError('Tối đa 20 tiện nghi, 80 ký tự mỗi tiện nghi.'); return }
    setBusy(true); setError('')
    try { await adminRoomApi.amenities(type, values); onSaved() } catch (reason) { setError(errorMessage(reason)) } finally { setBusy(false) }
  }
  return <Card className="room-type-amenities-edit"><h2>Tiện nghi thực tế · {type.name}</h2><p>Nhập một tiện nghi mỗi dòng. Chỉ công bố các tiện nghi khách sạn thực sự cung cấp.</p>{error && <Alert>{error}</Alert>}<form onSubmit={save}><label className="field">Tiện nghi<textarea aria-label="Tiện nghi của hạng phòng" value={text} maxLength={1620} disabled={busy} onChange={event => setText(event.target.value)} /></label><Button type="submit" busy={busy}>Lưu tiện nghi</Button></form></Card>
}
