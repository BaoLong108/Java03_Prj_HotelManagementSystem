import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useAuth } from '../auth/AuthProvider'
import { StaySearchForm } from './StaySearchForm'
import { hasStayDates, readRequestedStay, roomBookingPath, stayParams } from './search'
import { useRoomSearch } from './useRoomSearch'
import { apiAssetUrl } from '../../shared/api/client'
import { Alert, Badge, Button, Card, Field, LinkButton, PageHeader, PriceDisplay, Skeleton } from '../../shared/ui'
import { Icon } from '../../shared/ui/Icon'
import { RoomPhoto } from './RoomCard'
import './bookingPortal.css'

export function RoomSearchPage() {
  const [params] = useSearchParams()
  const searched = hasStayDates(params)
  const stay = readRequestedStay(params)
  const { user } = useAuth()
  const { rooms, loading, error, validation, retry } = useRoomSearch(stay, searched, String(user?.id ?? 'guest'))
  const pricedStay = searched && !validation
  const [type, setType] = useState('')
  const [min, setMin] = useState('')
  const [max, setMax] = useState('')
  const [capacity, setCapacity] = useState('')
  const [amenities, setAmenities] = useState<string[]>([])
  const [sort, setSort] = useState('recommended')
  const options = [...new Set(rooms.flatMap(room => room.amenities ?? []))].sort()
  const filtered = useMemo(() => rooms.filter(room => (!type || room.id === Number(type))
    && (!capacity || room.maxOccupancy >= Number(capacity))
    && (!pricedStay || !min || (room.quote && room.quote.payableAmount >= Number(min)))
    && (!pricedStay || !max || (room.quote && room.quote.payableAmount <= Number(max)))
    && amenities.every(amenity => room.amenities?.includes(amenity)))
    .sort((a, b) => {
      if (sort === 'recommended' || !pricedStay) {
        return Number(Boolean(b.available)) - Number(Boolean(a.available)) || a.name.localeCompare(b.name, 'vi')
      }
      if (!a.quote || !b.quote) return Number(Boolean(b.quote)) - Number(Boolean(a.quote))
      return (a.quote.payableAmount - b.quote.payableAmount) * (sort === 'price-desc' ? -1 : 1)
    }),
  [rooms, type, capacity, min, max, amenities, sort, pricedStay])
  function reset() { setType(''); setMin(''); setMax(''); setCapacity(''); setAmenities([]) }
  return <div className="booking-portal"><PageHeader title="Phòng & không gian"
    description="Chọn lịch nghỉ để xem phòng trống và giá cho cả kỳ nghỉ." />
    <StaySearchForm compact />
    {validation && <Alert tone="warning">{validation}</Alert>}
    <div className="room-search-layout"><aside className="room-search-filters" aria-label="Bộ lọc phòng">
      <Card><div className="portal-heading"><h2>Lọc phòng</h2><Button variant="ghost" onClick={reset}>Xóa bộ lọc</Button></div>
        <label className="field">Hạng phòng<select value={type} onChange={event => setType(event.target.value)}><option value="">Tất cả hạng phòng</option>{rooms.map(room => <option key={room.id} value={room.id}>{room.name}</option>)}</select></label>
        {pricedStay && <><Field label="Giá thấp nhất cho kỳ nghỉ (₫)" type="number" min={0} value={min} onChange={event => setMin(event.target.value)} />
          <Field label="Giá cao nhất cho kỳ nghỉ (₫)" type="number" min={0} value={max} onChange={event => setMax(event.target.value)} /></>}
        <Field label="Sức chứa tối thiểu" type="number" min={1} max={20} value={capacity} onChange={event => setCapacity(event.target.value)} />
        {options.length > 0 ? <fieldset className="amenity-filter"><legend>Tiện nghi</legend>{options.map(amenity => <label key={amenity}><input type="checkbox" checked={amenities.includes(amenity)} onChange={event => setAmenities(current => event.target.checked ? [...current, amenity] : current.filter(item => item !== amenity))} />{amenity}</label>)}</fieldset>
          : <p className="small muted">Tiện nghi sẽ hiển thị khi khách sạn cập nhật cho hạng phòng.</p>}
      </Card></aside><section className="room-search-results" aria-label="Kết quả tìm phòng" aria-busy={loading}>
        <div className="portal-heading"><div><h2>{loading ? 'Đang tải danh mục…' : `${filtered.length} hạng phòng`}</h2>{pricedStay && <p className="small muted">{stay.adults + stay.children} khách · Giá cho toàn bộ kỳ nghỉ</p>}</div>
          <label className="portal-sort">Sắp xếp<select value={pricedStay ? sort : 'recommended'} onChange={event => setSort(event.target.value)}><option value="recommended">Theo hạng phòng</option>{pricedStay && <><option value="price-asc">Giá tăng dần</option><option value="price-desc">Giá giảm dần</option></>}</select></label></div>
        {loading && <div className="room-result-skeletons" role="status" aria-label="Đang tải phòng và báo giá">{[1, 2, 3].map(key => <div key={key} className="room-result-skeleton"><Skeleton lines={6} /></div>)}</div>}
        {error && <Card><Alert>{error}</Alert><Button onClick={retry}>Thử lại</Button></Card>}
        {!loading && !error && filtered.length === 0 && <Card><h3>Chưa tìm thấy phòng phù hợp</h3><p>Thử đổi lịch nghỉ, số khách hoặc mở rộng bộ lọc.</p><Button variant="secondary" onClick={reset}>Xóa bộ lọc</Button></Card>}
        {!loading && !error && filtered.map(room => {
          const detailPath = `/rooms/${room.id}${searched ? `?${stayParams(stay)}` : ''}`
          const exceedsCapacity = stay.adults + stay.children > room.maxOccupancy
          const status = room.available ? `Còn ${room.availableRoomCount} phòng` : exceedsCapacity ? 'Vượt sức chứa' : 'Còn 0 phòng'
          return <Card key={room.id} className="portal-room-card">
          <div className="portal-room-photo"><RoomPhoto key={room.coverImageUrl} src={room.coverImageUrl ? apiAssetUrl(room.coverImageUrl) : null} alt={`Ảnh bìa ${room.name}`} thumbnail /></div>
          <div className="portal-room-copy">{pricedStay && <Badge tone={room.available ? 'success' : 'warning'}>{status}</Badge>}<h3>{room.name}</h3><p>{room.description}</p><p className="room-occupancy"><Icon name="users" size={17} />Tối đa {room.maxOccupancy} khách</p>
            <ul className="amenity-list">{room.amenities?.map(amenity => <li key={amenity}><Icon name="check" size={14} />{amenity}</li>)}</ul><LinkButton to={detailPath} variant="secondary">Xem ảnh & chi tiết</LinkButton></div>
          <div className="portal-room-price">{room.quote ? <><span className="small muted">Tổng {room.quote.nights} đêm</span><PriceDisplay amount={room.quote.payableAmount} />{room.quote.discountAmount > 0 && <p className="portal-benefit">Đã giảm {room.quote.discountRate * 100}% theo hạng {room.quote.appliedTier}</p>}
            <LinkButton to={roomBookingPath(room.id, stay)}>Đặt ngay <Icon name="arrow" size={17} /></LinkButton></>
            : room.quoteError ? <><Alert>{room.quoteError}</Alert><Button variant="secondary" onClick={retry}>Lấy lại báo giá</Button></>
              : !pricedStay ? <><PriceDisplay amount={room.basePricePerNight} suffix="/ đêm · giá cơ bản" /><LinkButton to={detailPath}>Chọn ngày nghỉ</LinkButton></>
                : <p className="small muted">{exceedsCapacity ? 'Số khách vượt sức chứa của hạng phòng này.' : 'Hạng phòng này đã hết phòng trong khoảng ngày bạn chọn.'}</p>}</div>
        </Card> })}
      </section></div></div>
}
