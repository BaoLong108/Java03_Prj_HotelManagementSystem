import { useState } from 'react'
import { Badge, Button, Card, Dialog, LinkButton, PriceDisplay } from '../../shared/ui'
import { Icon } from '../../shared/ui/Icon'

// Presentation props only. Map the agreed API response here once the room service exists.
export type RoomPresentation = {
  id: number; name: string; description: string; maxOccupancy: number
  images: { src: string; alt: string }[]; amenities: string[]
  basePricePerNight: number | null; availability: 'available' | 'unavailable' | 'unknown'
}

export function ImageGallery({ images, name }: { images: RoomPresentation['images']; name: string }) {
  const [selected, setSelected] = useState<number | null>(null)
  const activeImage = selected === null ? undefined : images[selected]
  if (images.length === 0) return <div className="room-image-missing"><Icon name="bed" size={36} /><span>Chưa có ảnh phòng</span></div>
  return <><div className="image-gallery">{images.map((image, index) => <button key={`${image.src}-${index}`} type="button" onClick={() => setSelected(index)} aria-label={`Xem ảnh ${index + 1}: ${name}`}><img src={image.src} alt={image.alt} loading="lazy" /></button>)}</div>
    <Dialog open={Boolean(activeImage)} onClose={() => setSelected(null)} title={`${name} · ${(selected ?? 0) + 1}/${images.length}`}>
      {activeImage && <img className="gallery-large" src={activeImage.src} alt={activeImage.alt} />}
      <div className="dialog-actions"><Button variant="secondary" disabled={selected === null || selected <= 0} onClick={() => setSelected((value) => Math.max(0, (value ?? 0) - 1))}>Ảnh trước</Button><Button variant="secondary" disabled={selected === null || selected >= images.length - 1} onClick={() => setSelected((value) => Math.min(images.length - 1, (value ?? 0) + 1))}>Ảnh sau</Button></div>
    </Dialog></>
}

export function RoomCard({ room, search = '' }: { room: RoomPresentation; search?: string }) {
  return <Card className="room-type-card"><ImageGallery name={room.name} images={room.images} /><div className="room-type-copy"><div className="card-heading"><h2>{room.name}</h2><Badge tone={room.availability === 'available' ? 'success' : 'neutral'}>{room.availability === 'available' ? 'Có phòng theo kết quả tìm' : room.availability === 'unavailable' ? 'Hết phòng theo lịch chọn' : 'Chưa xác nhận phòng trống'}</Badge></div>
    <p className="muted">{room.description}</p><p className="room-occupancy"><Icon name="users" size={17} />Tối đa {room.maxOccupancy} khách</p><ul className="amenity-list">{room.amenities.map((amenity) => <li key={amenity}><Icon name="check" size={14} />{amenity}</li>)}</ul><div className="room-card-footer"><PriceDisplay amount={room.basePricePerNight} suffix="/ đêm · giá cơ bản" /><LinkButton to={`/rooms/${room.id}${search ? `?${new URLSearchParams(search)}` : ''}`} variant="secondary">Chi tiết loại phòng <Icon name="arrow" size={16} /></LinkButton></div></div></Card>
}
