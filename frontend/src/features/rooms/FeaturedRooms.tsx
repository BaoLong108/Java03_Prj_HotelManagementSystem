import { useEffect, useState } from 'react'
import { roomApi } from './api'
import type { RoomTypeResponse } from './api'
import { apiAssetUrl, errorMessage } from '../../shared/api/client'
import { Alert, Button, LinkButton, PriceDisplay, Skeleton } from '../../shared/ui'
import { RoomPhoto } from './RoomCard'
import './bookingPortal.css'

export function FeaturedRooms() {
  const [rooms, setRooms] = useState<RoomTypeResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setError('')
    roomApi.list(controller.signal).then(data => { if (!controller.signal.aborted) setRooms(data) })
      .catch(reason => { if (!controller.signal.aborted) setError(errorMessage(reason)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [retry])
  if (loading) return <div className="featured-rooms" role="status" aria-label="Đang tải hạng phòng">{[1, 2, 3].map(key => <div key={key} className="room-result-skeleton"><Skeleton lines={6} /></div>)}</div>
  if (error) return <><Alert>{error}</Alert><Button variant="secondary" onClick={() => setRetry(value => value + 1)}>Tải lại hạng phòng</Button></>
  if (!rooms.length) return <p>Khách sạn chưa cập nhật danh mục phòng. Vui lòng quay lại sau.</p>
  const amenities = [...new Set(rooms.flatMap(room => room.amenities ?? []))].slice(0, 8)
  return <><div className="featured-rooms">{rooms.slice(0, 3).map(room => <article key={room.id} className="featured-room">
    <div className="featured-room-photo"><RoomPhoto key={room.coverImageUrl} src={room.coverImageUrl ? apiAssetUrl(room.coverImageUrl) : null} alt={`Ảnh bìa ${room.name}`} thumbnail /></div>
    <div className="featured-room-copy"><h3>{room.name}</h3><p className="small muted">Tối đa {room.maxOccupancy} khách</p><p>{room.description}</p><PriceDisplay amount={room.basePricePerNight} suffix="/ đêm · giá cơ bản" /><LinkButton to={`/rooms/${room.id}`} variant="secondary">Khám phá phòng</LinkButton></div>
  </article>)}</div>{amenities.length > 0 && <div className="hotel-amenity-strip" aria-label="Tiện nghi có trong các hạng phòng">{amenities.map(amenity => <span key={amenity}>{amenity}</span>)}</div>}</>
}
