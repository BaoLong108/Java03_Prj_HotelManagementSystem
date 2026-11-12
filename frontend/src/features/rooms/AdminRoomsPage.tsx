import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { apiAssetUrl, errorMessage } from '../../shared/api/client'
import { Alert, Badge, Button, Card, Field, LinkButton, LoadingState, PageHeader, PriceDisplay, Tabs } from '../../shared/ui'
import { adminRoomApi } from './adminApi'
import type { PhysicalRoom } from './adminApi'
import type { RoomTypeResponse } from './api'
import { RoomInventoryItem } from './RoomInventoryItem'
import './rooms.css'

function UploadPreview({ files, cover, onCover }: { files: File[]; cover: number; onCover: (index: number) => void }) {
  const [urls, setUrls] = useState<string[]>([])
  useEffect(() => {
    const next = files.map((file) => URL.createObjectURL(file))
    setUrls(next)
    return () => next.forEach((url) => URL.revokeObjectURL(url))
  }, [files])
  return <fieldset className="room-upload-preview"><legend>Chọn ảnh bìa</legend>{files.map((file, index) => <label key={`${index}-${file.name}`}>
    {urls[index] && <img src={urls[index]} alt={`Ảnh tải lên ${index + 1}`} />}
    <span><input type="radio" name="cover" checked={cover === index} onChange={() => onCover(index)} />Ảnh {index + 1}{cover === index ? ' · Ảnh bìa' : ''}</span>
    <small>{file.name}</small></label>)}</fieldset>
}

export function AdminRoomsPage() {
  const [params] = useSearchParams()
  const requestedId = params.get('roomTypeId')
  const [tab, setTab] = useState<'physical' | 'types' | 'rates'>('physical')
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
  const [files, setFiles] = useState<File[]>([])
  const [cover, setCover] = useState(0)
  const [replace, setReplace] = useState(false)
  const [busy, setBusy] = useState(false)
  const [uploadKey, setUploadKey] = useState(0)
  const [price, setPrice] = useState('')
  const [showCreate, setShowCreate] = useState(false)
  const selected = types.find((type) => type.id === Number(typeId))

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    adminRoomApi.types(controller.signal).then((data) => {
      if (controller.signal.aborted) return
      setTypes(data)
      setTypeId((current) => current || String((data.find(type => type.active && String(type.id) === requestedId)
        ?? data.find(type => type.active))?.id ?? ''))
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

  useEffect(() => { setPrice(selected ? String(selected.basePricePerNight) : ''); setReplace(false) }, [selected])

  function clearDraft() {
    setNumber(''); setFloor(''); setFiles([]); setCover(0); setReplace(false); setUploadKey((value) => value + 1)
  }

  async function create(event: FormEvent) {
    event.preventDefault()
    setError(''); setNotice('')
    if (busy) return
    if (!selected?.active || !number.trim() || (!selected.images.length && files.length === 0)
      || files.length > 5 || (files.length > 0 && cover >= files.length)) { setError('Chọn hạng phòng, số phòng và từ 1 đến 5 ảnh hợp lệ.'); return }
    if (selected.images.length > 0 && files.length > 0 && !replace) { setError('Cần xác nhận thay bộ ảnh dùng chung của hạng phòng này.'); return }
    setBusy(true)
    try {
      const room = await adminRoomApi.create({ roomTypeId: selected.id, roomNumber: number.trim(), floorNumber: floor ? Number(floor) : null }, files, cover, replace)
      setNotice(`Đã tạo phòng ${room.roomNumber}. Ảnh đã được lưu và hiển thị cho khách.`)
      clearDraft(); setShowCreate(false)
      setRefresh((value) => value + 1)
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
    <Tabs label="Danh mục quản lý" value={tab} onChange={setTab} items={[{ value: 'physical', label: 'Phòng vật lý' }, { value: 'types', label: 'Loại phòng' }, { value: 'rates', label: 'Giá cơ bản' }]} />
    {loading ? <LoadingState message="Đang tải hạng phòng…" /> : types.length === 0 ? <Card><p>Chưa có hạng phòng trong cơ sở dữ liệu.</p><Button onClick={() => setRefresh((value) => value + 1)}>Tải lại</Button></Card> : <>
      <div className="field room-type-select"><label htmlFor="admin-room-type">Hạng phòng</label><select id="admin-room-type" value={typeId} disabled={busy} onChange={(event) => { setTypeId(event.target.value); clearDraft(); setError(''); setNotice('') }}>
        <option value="" disabled>Chọn hạng phòng</option>{types.map((type) => <option key={type.id} value={type.id} disabled={!type.active}>{type.name} ({type.code}){type.active ? '' : ' · Đã ẩn'}</option>)}</select></div>
      {selected && <Card className="room-admin-summary"><div><h2>{selected.name}</h2><p>{selected.description}</p><p>Tối đa {selected.maxOccupancy} khách · {selected.activeRoomCount} phòng đang hoạt động</p><PriceDisplay amount={selected.basePricePerNight} suffix="/ đêm · giá cơ bản" /></div>
        {selected.coverImageUrl && <img src={apiAssetUrl(selected.coverImageUrl)} alt={`Ảnh bìa ${selected.name}`} />}<LinkButton to={`/rooms/${selected.id}`} variant="secondary">Xem giao diện khách</LinkButton></Card>}
      {tab === 'physical' && <div className="room-admin-grid"><Card>{!showCreate ? <><h2>Thêm phòng vào khách sạn</h2><p className="muted">Chọn hạng phòng phía trên, rồi nhập thông tin phòng mới.</p><Button disabled={!selected?.active} onClick={() => { clearDraft(); setError(''); setNotice(''); setShowCreate(true) }}>Thêm phòng mới</Button></> : <><h2>Tạo phòng mới</h2><form onSubmit={create} className="room-create-form">
        <Field label="Số phòng" required maxLength={30} value={number} disabled={busy} onChange={(event) => setNumber(event.target.value)} hint="Số phòng là duy nhất trong khách sạn." />
        <Field label="Tầng" type="number" min={1} max={32767} step={1} value={floor} disabled={busy} onChange={(event) => setFloor(event.target.value)} />
        {Boolean(selected?.images.length) && <p className="small muted">Phòng mới sẽ dùng bộ ảnh hiện tại của hạng phòng. Chỉ chọn ảnh bên dưới nếu muốn thay bộ ảnh dùng chung.</p>}
        <Field key={uploadKey} label="Ảnh phòng" type="file" accept="image/jpeg,image/png" multiple required={!selected?.images.length} disabled={busy} hint="Chọn 1–5 ảnh JPEG/PNG, tối đa 5 MB/ảnh, 6000 px mỗi chiều và 16 MP." onChange={(event) => {
          const next = Array.from(event.target.files ?? [])
          setError('')
          if (next.length < 1 || next.length > 5 || next.some((file) => !['image/jpeg', 'image/png'].includes(file.type) || file.size > 5 * 1024 * 1024 || file.size === 0)) {
            setError('Chọn 1–5 ảnh JPEG/PNG hợp lệ, tối đa 5 MB mỗi ảnh.'); setFiles([]); event.target.value = ''; return
          }
          setFiles(next); setCover(0)
        }} />
        {files.length > 0 && <UploadPreview files={files} cover={cover} onCover={setCover} />}
        <p className="small muted">Giá và ảnh được dùng chung theo hạng phòng; khách chọn hạng phòng khi đặt.</p>
        {Boolean(selected?.images.length) && files.length > 0 && <label className="room-replace-confirm"><input type="checkbox" checked={replace} disabled={busy} onChange={(event) => setReplace(event.target.checked)} />Tôi xác nhận thay bộ ảnh hiện tại của hạng phòng này bằng các ảnh vừa chọn.</label>}
        <div className="button-row"><Button type="submit" busy={busy} disabled={!selected?.active || (!selected.images.length && files.length === 0)}>Tạo phòng & lưu ảnh</Button><Button variant="secondary" disabled={busy} onClick={() => { clearDraft(); setShowCreate(false); setError('') }}>Hủy tạo phòng</Button></div>
      </form></>}</Card><Card><h2>Phòng thuộc hạng đã chọn</h2>{loadingRooms ? <LoadingState /> : rooms.length === 0 ? <p>Chưa có phòng vật lý thuộc hạng này.</p> : <div className="room-inventory-list">{rooms.map((room) => <RoomInventoryItem key={room.id} room={room} onChange={() => setRefresh(value => value + 1)} />)}</div>}</Card></div>}
      {tab === 'types' && <div className="room-type-admin-list">{types.map((type) => <Card key={type.id}><h2>{type.name} <Badge>{type.code}</Badge></h2><p>{type.description}</p><p>Tối đa {type.maxOccupancy} khách · {type.activeRoomCount} phòng · {type.images.length} ảnh</p><PriceDisplay amount={type.basePricePerNight} suffix="/ đêm" /><LinkButton to={`/rooms/${type.id}`} variant="secondary">Xem chi tiết</LinkButton></Card>)}</div>}
      {tab === 'rates' && selected && <Card><h2>Giá cơ bản · {selected.name}</h2><form className="room-price-form" onSubmit={savePrice}><Field label="Giá mỗi đêm (VND)" type="number" min={0} max={9999999999999.99} step="0.01" required value={price} disabled={busy} onChange={(event) => setPrice(event.target.value)} /><p className="muted">Pricing sử dụng giá của hạng phòng, sau đó áp dụng ưu đãi thành viên và voucher. Booking đã tạo giữ nguyên giá đã chốt.</p><Button type="submit" busy={busy}>Lưu giá cơ bản</Button></form></Card>}
    </>}
  </>
}
