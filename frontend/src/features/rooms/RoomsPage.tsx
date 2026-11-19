import { useParams, useSearchParams } from 'react-router-dom'
import { apiAssetUrl } from '../../shared/api/client'
import { Alert, Button, Card, LinkButton, LoadingState, PageHeader, PriceDisplay } from '../../shared/ui'
import { ImageGallery } from './RoomCard'
import { RoomSearchPage } from './RoomSearchPage'
import { StaySearchForm } from './StaySearchForm'
import { displayDate, hasStayDates, parseRoomTypeId, readRequestedStay, roomBookingPath, stayParams } from './search'
import { useSelectedRoom } from './useSelectedRoom'
import './rooms.css'
import './bookingPortal.css'

export function RoomsPage() { return <RoomSearchPage /> }

export function RoomDetailPage() {
  const { roomTypeId } = useParams()
  const id = parseRoomTypeId(roomTypeId)
  if (id === null) return <Card><Alert>Không tìm thấy hạng phòng.</Alert><LinkButton to="/rooms">Về danh mục phòng</LinkButton></Card>
  return <RoomDetail key={id} id={id} />
}

function RoomDetail({ id }: { id: number }) {
  const [params] = useSearchParams()
  const stay = hasStayDates(params) ? readRequestedStay(params) : null
  const catalog = stay ? `/rooms?${stayParams(stay)}` : '/rooms'
  const { room, roomError, current, validation, retry } = useSelectedRoom(id, stay)
  const quote = current?.quote
  return <><PageHeader title={room?.name ?? 'Chi tiết hạng phòng'} description="Xem ảnh, sức chứa và giá cho kỳ nghỉ của bạn."
    action={<LinkButton to={catalog} variant="secondary">Về danh mục phòng</LinkButton>} />
    {!room ? roomError ? <Card><Alert>{roomError}</Alert><Button onClick={retry}>Thử lại</Button></Card>
      : <LoadingState message="Đang tải thông tin và ảnh phòng…" /> : <>
      <Card className="room-detail-card">
        <ImageGallery key={room.images?.map(image => `${image.id}:${image.cover}`).join('-')} name={room.name} images={(room.images ?? []).map((image, index) => ({
          src: apiAssetUrl(image.url), cover: image.cover, alt: `${room.name} · ${image.cover ? 'Ảnh bìa' : `Ảnh ${index + 1}`}`,
        }))} />
        <div className="room-detail-copy"><div><p>{room.description}</p><p>Tối đa {room.maxOccupancy} khách</p>
          <ul className="amenity-list">{room.amenities?.map(amenity => <li key={amenity}>{amenity}</li>)}</ul>
          <PriceDisplay amount={room.basePricePerNight} suffix="/ đêm · giá cơ bản" /></div></div>
      </Card>
      <div className="room-detail-layout"><Card><h2>Lên kế hoạch kỳ nghỉ</h2><StaySearchForm compact destination={`/rooms/${id}`} />
        <p className="small muted">Nhận phòng từ 12:00 · Trả phòng trước 10:00.</p></Card>
        <Card className="detail-quote"><h2>Báo giá kỳ nghỉ</h2>
          {!stay ? <p>Chọn ngày và số khách để kiểm tra phòng trống.</p>
            : validation ? <Alert tone="warning">{validation}</Alert>
            : !current ? <LoadingState message="Đang kiểm tra phòng và giá…" />
            : current.error ? <><Alert>{current.error}</Alert><Button variant="secondary" onClick={retry}>Thử lại báo giá</Button></>
            : !current.available ? <Alert tone="warning">{stay.adults + stay.children > room.maxOccupancy
              ? 'Số khách vượt sức chứa của hạng phòng này.' : 'Hạng phòng này đã hết phòng trong khoảng ngày bạn chọn.'}</Alert>
            : quote && <>
              <p>{displayDate(stay.arrival)} → {displayDate(stay.departure)} · {quote.nights} đêm · {quote.guestCount} khách</p>
              <p><strong>Còn phòng theo lịch chọn</strong></p>
              {quote.nightlyRates?.length ? <div className="nightly-breakdown">{quote.nightlyRates.map(night =>
                <div key={night.date}><span>{displayDate(night.date)}</span><PriceDisplay amount={night.nightlyRate} /></div>)}</div> : null}
              {quote.nightlyBreakdownTruncated && <p className="small muted">Hiển thị 366 đêm đầu. Tổng bên dưới bao gồm toàn bộ kỳ nghỉ.</p>}
              <div className="detail-price-row"><span>Tiền phòng trước ưu đãi</span><PriceDisplay amount={quote.baseAmount} /></div>
              <div className="detail-price-row"><span>Ưu đãi thành viên</span><PriceDisplay amount={quote.discountAmount} /></div>
              <div className="detail-price-row detail-price-total"><span>Tổng tạm tính</span><PriceDisplay amount={quote.payableAmount} /></div>
              <p className="small muted">Voucher được áp dụng sau khi giữ phòng.</p>
              <LinkButton to={roomBookingPath(id, stay)}>Đặt ngay</LinkButton>
            </>}
        </Card>
      </div>
    </>}
  </>
}
