import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { apiAssetUrl, errorMessage } from '../../shared/api/client'
import { Alert, Badge, Button, Card, Field, LinkButton, LoadingState, PageHeader, PriceDisplay, Tabs } from '../../shared/ui'
import { adminRoomApi } from './adminApi'
import type { PhysicalRoom } from './adminApi'
import type { RoomTypeResponse } from './api'
import { AdminGalleryEditor, galleryRevision, RoomAmenitiesEditor } from './AdminGalleryEditor'
import { RoomInventoryItem } from './RoomInventoryItem'
import './rooms.css'

export function AdminRoomsPage() {
  const [params] = useSearchParams()
  const requestedId = params.get('roomTypeId')
  const [tab, setTab] = useState<'physical' | 'types' | 'rates' | 'images'>('physical')
  const [types, setTypes] = useState<RoomTypeResponse[]>([])
  const [typeId, setTypeId] = useState('')
  const [rooms, setRooms] = useState<PhysicalRoom[]>([])
  const [loading, setLoading] = useState(true)
  const [loadingRooms, setLoadingRooms] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [refresh, setRefresh] = useState(0)
  const [number, setNumber] = useState('')
  const [floor, setFloor] = useState('')
  const [active, setActive] = useState(true)
  const [busy, setBusy] = useState(false)
  const [price, setPrice] = useState('')
  const [showCreate, setShowCreate] = useState(false)
  const selected = types.find((type) => type.id === Number(typeId))

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    adminRoomApi.types(controller.signal).then((data) => {
      if (controller.signal.aborted) return
      setTypes(data)
      setTypeId((current) => data.some(type => String(type.id) === current) ? current
        : String((data.find(type => type.active && String(type.id) === requestedId)
          ?? data.find(type => type.active) ?? data[0])?.id ?? ''))
    }).catch((reason) => { if (!controller.signal.aborted) setError(errorMessage(reason)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [refresh, requestedId])

  useEffect(() => {
    if (!typeId) return
    const controller = new AbortController()
    setLoadingRooms(true)
    setRooms([])
    adminRoomApi.physical(Number(typeId), controller.signal).then((data) => { if (!controller.signal.aborted) setRooms(data) })
      .catch((reason) => { if (!controller.signal.aborted) setError(errorMessage(reason)) })
      .finally(() => { if (!controller.signal.aborted) setLoadingRooms(false) })
    return () => controller.abort()
  }, [typeId, refresh])

  useEffect(() => { setPrice(selected ? String(selected.basePricePerNight) : '') }, [selected])

  function clearDraft() {
    setNumber(''); setFloor(''); setActive(true)
  }

  async function create(event: FormEvent) {
    event.preventDefault()
    setError(''); setNotice('')
    if (busy) return
    const floorNumber = Number(floor)
    if (!selected?.active || !number.trim() || !Number.isInteger(floorNumber) || floorNumber < 1 || floorNumber > 32767) {
      setError('Chọn hạng phòng, nhập số phòng và tầng hợp lệ.'); return
    }
    setBusy(true)
    try {
      const room = await adminRoomApi.create({ roomTypeId: selected.id, roomNumber: number.trim(), floorNumber, active })
      setNotice(`Đã tạo phòng ${room.roomNumber}.`)
      clearDraft(); setShowCreate(false)
      setRefresh((value) => value + 1)
    } catch (reason) { setError(errorMessage(reason)) } finally { setBusy(false) }
  }

  async function createType(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    const fields = new window.FormData(event.currentTarget)
    const field = (key: string) => String(fields.get(key) ?? '').trim()
    const amenities = [...new Set(field('amenities').split('\n').map(value => value.trim()).filter(Boolean))]
    setError(''); setNotice('')
    if (!field('code') || !field('name') || amenities.length > 20 || amenities.some(value => value.length > 80)) {
      setError('Nhập mã, tên hạng phòng và tối đa 20 tiện nghi, mỗi tiện nghi tối đa 80 ký tự.'); return
    }
    setBusy(true)
    try {
      const type = await adminRoomApi.createType({ code: field('code'), name: field('name'), description: field('description') || null,
        maxOccupancy: Number(field('maxOccupancy')), basePricePerNight: Number(field('price')), amenities })
      setTypes(current => [...current, type]); setTypeId(String(type.id)); setTab('images')
      setNotice(`Đã tạo hạng phòng ${type.name}. Bạn có thể thêm ảnh dùng chung tại đây.`)
      setRefresh(value => value + 1)
    } catch (reason) { setError(errorMessage(reason)) } finally { setBusy(false) }
  }

  async function savePrice(event: FormEvent) {
    event.preventDefault()
    if (!selected) return
    setError(''); setNotice(''); setBusy(true)
    try { await adminRoomApi.price(selected, Number(price)); setNotice('Đã cập nhật giá cơ bản của hạng phòng.'); setRefresh((value) => value + 1) }
    catch (reason) { setError(errorMessage(reason)) } finally { setBusy(false) }
  }

  return <><PageHeader eyebrow="ROOM INVENTORY" title="Danh mục phòng" description="Tạo phòng, quản lý ảnh dùng chung và giá cơ bản theo hạng phòng."
    action={<LinkButton to={`/admin/rate-calendar${typeId ? `?roomTypeId=${typeId}` : ''}`} variant="secondary">Lịch giá phòng</LinkButton>} />
    {error && <Alert>{error}</Alert>}{notice && <Alert tone="success">{notice}</Alert>}
    <Tabs label="Danh mục quản lý" value={tab} onChange={setTab} items={[{ value: 'physical', label: 'Phòng vật lý' }, { value: 'types', label: 'Loại phòng' }, { value: 'images', label: 'Ảnh hạng phòng' }, { value: 'rates', label: 'Giá cơ bản' }]} />
    {loading ? <LoadingState message="Đang tải hạng phòng…" /> : <>
      {types.length === 0 && tab !== 'types' && <Card><p>Chưa có hạng phòng trong cơ sở dữ liệu.</p><Button onClick={() => setTab('types')}>Tạo hạng phòng</Button></Card>}
      {types.length > 0 &&
      <div className="field room-type-select"><label htmlFor="admin-room-type">Hạng phòng</label><select id="admin-room-type" value={typeId} disabled={busy} onChange={(event) => { setTypeId(event.target.value); clearDraft(); setError(''); setNotice('') }}>
        <option value="" disabled>Chọn hạng phòng</option>{types.map((type) => <option key={type.id} value={type.id} disabled={tab === 'physical' && !type.active}>{type.name} ({type.code}){type.active ? '' : ' · Đã ẩn'}</option>)}</select></div>}
      {selected && <Card className="room-admin-summary"><div><h2>{selected.name}</h2><p>{selected.description}</p><p>Tối đa {selected.maxOccupancy} khách · {selected.activeRoomCount} phòng đang hoạt động</p><PriceDisplay amount={selected.basePricePerNight} suffix="/ đêm · giá cơ bản" /></div>
        {selected.coverImageUrl && <img src={apiAssetUrl(selected.coverImageUrl)} alt={`Ảnh bìa ${selected.name}`} />}<LinkButton to={`/rooms/${selected.id}`} variant="secondary">Xem giao diện khách</LinkButton></Card>}
      {tab === 'physical' && types.length > 0 && <div className="room-admin-grid"><Card>{!showCreate ? <><h2>Thêm phòng vào khách sạn</h2><p className="muted">Chọn hạng phòng phía trên, rồi nhập thông tin phòng mới.</p><Button disabled={!selected?.active} onClick={() => { clearDraft(); setError(''); setNotice(''); setShowCreate(true) }}>Thêm phòng mới</Button></> : <><h2>Tạo phòng mới</h2><form onSubmit={create} className="room-create-form">
        <Field label="Số phòng" required maxLength={30} value={number} disabled={busy} onChange={(event) => setNumber(event.target.value)} hint="Số phòng là duy nhất trong khách sạn." />
        <Field label="Tầng" type="number" min={1} max={32767} step={1} required value={floor} disabled={busy} onChange={(event) => setFloor(event.target.value)} />
        <label className="field">Trạng thái<select value={active ? 'active' : 'hidden'} disabled={busy} onChange={event => setActive(event.target.value === 'active')}><option value="active">Hoạt động</option><option value="hidden">Đã ẩn · không mở bán</option></select></label>
        <p className="small muted">Phòng dùng chung giá và bộ ảnh của hạng phòng đã chọn.</p>
        <div className="button-row"><Button type="submit" busy={busy} disabled={!selected?.active}>Tạo phòng</Button><Button variant="secondary" disabled={busy} onClick={() => { clearDraft(); setShowCreate(false); setError('') }}>Hủy tạo phòng</Button></div>
      </form></>}</Card><Card><h2>Phòng thuộc hạng đã chọn</h2>{loadingRooms ? <LoadingState /> : rooms.length === 0 ? <p>Chưa có phòng vật lý thuộc hạng này.</p> : <div className="room-inventory-list">{rooms.map((room) => <RoomInventoryItem key={room.id} room={room} onChange={() => setRefresh(value => value + 1)} />)}</div>}</Card></div>}
      {tab === 'types' && <><Card><h2>Tạo hạng phòng</h2><form className="room-create-form" onSubmit={createType}>
        <Field name="code" label="Mã hạng phòng" required maxLength={30} disabled={busy} />
        <Field name="name" label="Tên hạng phòng" required maxLength={120} disabled={busy} />
        <label className="field">Mô tả<textarea name="description" disabled={busy} /></label>
        <Field name="maxOccupancy" label="Số khách tối đa" type="number" min={1} max={20} step={1} required disabled={busy} />
        <Field name="price" label="Giá cơ bản mỗi đêm (VND)" type="number" min={0} max={9999999999999.99} step="0.01" required disabled={busy} />
        <label className="field">Tiện nghi (mỗi dòng một mục)<textarea name="amenities" maxLength={1620} disabled={busy} /></label>
        <Button type="submit" busy={busy}>Tạo hạng phòng</Button>
      </form></Card><div className="room-type-admin-list">{types.map((type) => <Card key={type.id}><h2>{type.name} <Badge>{type.code}</Badge></h2><p>{type.description}</p><p>Tối đa {type.maxOccupancy} khách · {type.activeRoomCount} phòng · {type.images.length} ảnh</p><PriceDisplay amount={type.basePricePerNight} suffix="/ đêm" /><div className="button-row"><Button variant="secondary" onClick={() => { setTypeId(String(type.id)); setTab('images') }}>Quản lý ảnh</Button>{type.active && <LinkButton to={`/rooms/${type.id}`} variant="secondary">Xem chi tiết</LinkButton>}</div></Card>)}</div></>}
      {tab === 'rates' && selected && <Card><h2>Giá cơ bản · {selected.name}</h2><form className="room-price-form" onSubmit={savePrice}><Field label="Giá mỗi đêm (VND)" type="number" min={0} max={9999999999999.99} step="0.01" required value={price} disabled={busy} onChange={(event) => setPrice(event.target.value)} /><p className="muted">Pricing sử dụng giá của hạng phòng, sau đó áp dụng ưu đãi thành viên và voucher. Booking đã tạo giữ nguyên giá đã chốt.</p><Button type="submit" busy={busy}>Lưu giá cơ bản</Button></form></Card>}
      {tab === 'images' && selected && <AdminGalleryEditor key={`${selected.id}-${galleryRevision(selected.images)}`} type={selected} onSaved={() => { setNotice('Đã lưu bộ ảnh. Khách sẽ thấy bộ ảnh mới khi mở lại trang phòng.'); setRefresh(value => value + 1) }} onReload={() => { setError(''); setRefresh(value => value + 1) }} />}
      {tab === 'types' && selected && <RoomAmenitiesEditor key={`${selected.id}-${JSON.stringify(selected.amenities)}`} type={selected} onSaved={() => { setNotice('Đã cập nhật tiện nghi.'); setRefresh(value => value + 1) }} />}
    </>}
  </>
}
