import { useEffect, useRef, useState } from 'react'
import type { TouchEvent } from 'react'
import { Badge, Button, Card, Dialog, LinkButton, PriceDisplay } from '../../shared/ui'
import { Icon } from '../../shared/ui/Icon'

// Presentation props: API metadata is mapped by the calling page.
export type RoomPresentation = {
  id: number; name: string; description: string; maxOccupancy: number
  images: { src: string; alt: string; cover?: boolean }[]; amenities: string[]
  basePricePerNight: number | null; availability: 'available' | 'unavailable' | 'unknown'
}

export function RoomPhoto({ src, alt, thumbnail = false, eager = false }: { src?: string | null; alt: string; thumbnail?: boolean; eager?: boolean }) {
  const [failed, setFailed] = useState(false)
  const [loaded, setLoaded] = useState(false)
  if (!src || failed) return <div className="room-image-missing" role="img" aria-label={failed ? 'Không tải được ảnh phòng' : 'Chưa có ảnh phòng'}><Icon name="bed" size={32} /><span>{failed ? 'Không tải được ảnh phòng' : 'Chưa có ảnh phòng'}</span></div>
  return <div className="gallery-placeholder">{!loaded && <span className="spinner" role="status" aria-label="Đang tải ảnh" />}
    <img src={thumbnail ? `${src}${src.includes('?') ? '&' : '?'}size=thumb` : src} alt={alt} loading={eager ? 'eager' : 'lazy'}
      onLoad={() => setLoaded(true)} onError={() => setFailed(true)} />
  </div>
}

export function ImageGallery({ images, name }: { images: RoomPresentation['images']; name: string }) {
  const [selected, setSelected] = useState(() => Math.max(0, images.findIndex(image => image.cover)))
  const [enlarged, setEnlarged] = useState(false)
  const touch = useRef<[number, number] | null>(null)
  const index = Math.min(selected, images.length - 1)
  const activeImage = images[index]
  function move(offset: number) { setSelected(current => Math.max(0, Math.min(images.length - 1, current + offset))) }
  useEffect(() => {
    if (!enlarged) return
    function keyboard(event: KeyboardEvent) {
      if (event.key === 'ArrowLeft') { event.preventDefault(); setSelected(current => Math.max(0, current - 1)) }
      if (event.key === 'ArrowRight') { event.preventDefault(); setSelected(current => Math.min(images.length - 1, current + 1)) }
    }
    document.addEventListener('keydown', keyboard)
    return () => document.removeEventListener('keydown', keyboard)
  }, [enlarged, images.length])
  function swipe(event: TouchEvent) {
    const start = touch.current; const end = event.changedTouches[0]; touch.current = null
    if (start && end && Math.abs(end.clientX - start[0]) > 45 && Math.abs(end.clientX - start[0]) > Math.abs(end.clientY - start[1]) * 1.2) move(end.clientX < start[0] ? 1 : -1)
  }
  if (images.length === 0) return <div className="room-image-missing"><Icon name="bed" size={36} /><span>Chưa có ảnh phòng</span></div>
  return <div className="room-gallery" onTouchStart={event => { const point = event.touches[0]; touch.current = point ? [point.clientX, point.clientY] : null }} onTouchEnd={swipe}>
    <div className="gallery-stage"><button className="gallery-main" type="button" aria-label={`Phóng to ảnh ${index + 1}: ${name}`} onClick={() => setEnlarged(true)}><RoomPhoto key={activeImage.src} src={activeImage.src} alt={activeImage.alt} eager /><span>{index + 1}/{images.length} · Phóng to</span></button>
      {images.length > 1 && <><button className="gallery-arrow gallery-arrow--previous" disabled={index === 0} aria-label="Ảnh trước trong bộ sưu tập" onClick={() => move(-1)}>←</button><button className="gallery-arrow gallery-arrow--next" disabled={index === images.length - 1} aria-label="Ảnh tiếp theo trong bộ sưu tập" onClick={() => move(1)}><Icon name="arrow" size={18} /></button></>}
    </div>
    {images.length > 1 && <><div className="gallery-navigation"><Button variant="secondary" aria-label="Ảnh phòng trước" disabled={index === 0} onClick={() => setSelected(index - 1)}>Ảnh trước</Button><span aria-live="polite">Ảnh {index + 1}/{images.length}</span><Button variant="secondary" aria-label="Ảnh phòng tiếp theo" disabled={index === images.length - 1} onClick={() => setSelected(index + 1)}>Ảnh sau</Button></div>
      <div className="image-gallery" aria-label={`Các ảnh ${name}`}>{images.map((image, imageIndex) => <button key={`${image.src}-${imageIndex}`} type="button" onClick={() => setSelected(imageIndex)} aria-pressed={index === imageIndex} aria-label={`Chọn ảnh ${imageIndex + 1}: ${name}`}><RoomPhoto key={image.src} src={image.src} alt={image.alt} thumbnail /></button>)}</div></>}
    <Dialog open={enlarged} onClose={() => setEnlarged(false)} title={`${name} · ${index + 1}/${images.length}`}>
      {activeImage && <img className="gallery-large" src={activeImage.src} alt={activeImage.alt} />}
      {images.length > 1 && <div className="dialog-actions"><Button variant="secondary" disabled={index <= 0} onClick={() => setSelected(index - 1)}>Ảnh trước</Button><Button variant="secondary" disabled={index >= images.length - 1} onClick={() => setSelected(index + 1)}>Ảnh sau</Button></div>}
    </Dialog></div>
}

export function RoomCard({ room, search = '' }: { room: RoomPresentation; search?: string }) {
  return <Card className="room-type-card"><ImageGallery name={room.name} images={room.images} /><div className="room-type-copy"><div className="card-heading"><h2>{room.name}</h2><Badge tone={room.availability === 'available' ? 'success' : 'neutral'}>{room.availability === 'available' ? 'Có phòng theo kết quả tìm' : room.availability === 'unavailable' ? 'Hết phòng theo lịch chọn' : 'Chưa xác nhận phòng trống'}</Badge></div>
    <p className="muted">{room.description}</p><p className="room-occupancy"><Icon name="users" size={17} />Tối đa {room.maxOccupancy} khách</p><ul className="amenity-list">{room.amenities.map((amenity) => <li key={amenity}><Icon name="check" size={14} />{amenity}</li>)}</ul><div className="room-card-footer"><PriceDisplay amount={room.basePricePerNight} suffix="/ đêm · giá cơ bản" /><LinkButton to={`/rooms/${room.id}${search ? `?${new URLSearchParams(search)}` : ''}`} variant="secondary">Chi tiết loại phòng <Icon name="arrow" size={16} /></LinkButton></div></div></Card>
}
