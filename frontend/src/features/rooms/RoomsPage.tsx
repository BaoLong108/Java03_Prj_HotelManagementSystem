import { BookingPage } from '../booking/BookingPage'
import { useEffect, useState } from 'react'
import { useParams, useSearchParams } from 'react-router-dom'
import { apiAssetUrl, errorMessage } from '../../shared/api/client'
import { Alert, Button, Card, LinkButton, LoadingState, PageHeader, PriceDisplay } from '../../shared/ui'
import { ImageGallery } from './RoomCard'
import { roomApi } from './api'
import type { RoomTypeResponse } from './api'
import './rooms.css'

export function RoomsPage() { return <BookingPage /> }

export function RoomDetailPage() {
  const { roomTypeId } = useParams()
  const [params] = useSearchParams()
  const [room, setRoom] = useState<RoomTypeResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError(''); setRoom(null)
    if (!/^\d+$/.test(roomTypeId ?? '')) { setError('Không tìm thấy hạng phòng.'); setLoading(false); return }
    roomApi.get(Number(roomTypeId), controller.signal).then((data) => { if (!controller.signal.aborted) setRoom(data) })
      .catch((reason) => { if (!controller.signal.aborted) setError(errorMessage(reason)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [roomTypeId, retry])
  const search = new URLSearchParams(params)
  search.set('roomTypeId', String(room?.id ?? ''))
  return <><PageHeader eyebrow="PHÒNG & KHÔNG GIAN" title={room?.name ?? 'Chi tiết loại phòng'} description="Khám phá không gian trước khi chọn kỳ nghỉ của bạn." />
    {loading ? <LoadingState message="Đang tải thông tin và ảnh phòng…" /> : error ? <><Alert>{error}</Alert><Button onClick={() => setRetry((value) => value + 1)}>Thử lại</Button></> : room && <Card className="room-detail-card">
      <ImageGallery key={room.id} name={room.name} images={(room.images ?? []).map((image, index) => ({ src: apiAssetUrl(image.url), alt: `${room.name} · ${image.cover ? 'Ảnh bìa' : `Ảnh ${index + 1}`}` }))} />
      {room.images?.length > 0 && <p className="small muted room-gallery-hint">{room.images.length > 1 && <>Vuốt hoặc cuộn ngang để xem {room.images.length} ảnh. </>}Bấm vào ảnh để phóng to.</p>}
      <div className="room-detail-copy"><div><h2>{room.name}</h2><p>{room.description || 'Không gian nghỉ dưỡng thoải mái.'}</p><p>Tối đa {room.maxOccupancy} khách</p><PriceDisplay amount={room.basePricePerNight} suffix="/ đêm · giá cơ bản" /><p className="small muted">Giá cuối cùng và phòng trống được xác nhận theo lịch lưu trú, hạng thành viên và voucher ở bước đặt phòng.</p></div><LinkButton to={`/rooms?${search}`}>Chọn ngày & đặt phòng</LinkButton></div>
    </Card>}
    <div className="button-row"><LinkButton to={`/rooms?${params}`} variant="secondary">Về danh mục phòng</LinkButton></div>
  </>
}
