import { useState } from 'react'
import { Badge, Button, Card, Dialog, LinkButton, PriceDisplay } from '../../shared/ui'
import { Icon } from '../../shared/ui/Icon'

// Presentation props: API metadata is mapped by the calling page.
export type RoomPresentation = {
  id: number; name: string; description: string; maxOccupancy: number
  images: { src: string; alt: string }[]; amenities: string[]
  basePricePerNight: number | null; availability: 'available' | 'unavailable' | 'unknown'
}

export function ImageGallery({ images, name }: { images: RoomPresentation['images']; name: string }) {
  const [selected, setSelected] = useState(0)
  const [enlarged, setEnlarged] = useState(false)
  const index = Math.min(selected, images.length - 1)
  const activeImage = images[index]
  if (images.length === 0) return <div className="room-image-missing"><Icon name="bed" size={36} /><span>Chưa có ảnh phòng</span></div>
  return <div className="room-gallery"><button className="gallery-main" type="button" aria-label={`Phóng to ảnh ${index + 1}: ${name}`} onClick={() => setEnlarged(true)}><img src={activeImage.src} alt={activeImage.alt} /><span>{index + 1}/{images.length} · Phóng to</span></button>
    {images.length > 1 && <><div className="gallery-navigation"><Button variant="secondary" aria-label="Ảnh phòng trước" disabled={index === 0} onClick={() => setSelected(index - 1)}>Ảnh trước</Button><span aria-live="polite">Ảnh {index + 1}/{images.length}</span><Button variant="secondary" aria-label="Ảnh phòng tiếp theo" disabled={index === images.length - 1} onClick={() => setSelected(index + 1)}>Ảnh sau</Button></div>
      <div className="image-gallery" aria-label={`Các ảnh ${name}`}>{images.map((image, imageIndex) => <button key={`${image.src}-${imageIndex}`} type="button" onClick={() => setSelected(imageIndex)} aria-pressed={index === imageIndex} aria-label={`Chọn ảnh ${imageIndex + 1}: ${name}`}><img src={image.src} alt={image.alt} loading="lazy" /></button>)}</div></>}
    <Dialog open={enlarged} onClose={() => setEnlarged(false)} title={`${name} · ${index + 1}/${images.length}`}>
      {activeImage && <img className="gallery-large" src={activeImage.src} alt={activeImage.alt} />}
      {images.length > 1 && <div className="dialog-actions"><Button variant="secondary" disabled={index <= 0} onClick={() => setSelected(index - 1)}>Ảnh trước</Button><Button variant="secondary" disabled={index >= images.length - 1} onClick={() => setSelected(index + 1)}>Ảnh sau</Button></div>}
    </Dialog></div>
}

export function RoomCard({ room, search = '' }: { room: RoomPresentation; search?: string }) {
  return <Card className="room-type-card"><ImageGallery name={room.name} images={room.images} /><div className="room-type-copy"><div className="card-heading"><h2>{room.name}</h2><Badge tone={room.availability === 'available' ? 'success' : 'neutral'}>{room.availability === 'available' ? 'Có phòng theo kết quả tìm' : room.availability === 'unavailable' ? 'Hết phòng theo lịch chọn' : 'Chưa xác nhận phòng trống'}</Badge></div>
    <p className="muted">{room.description}</p><p className="room-occupancy"><Icon name="users" size={17} />Tối đa {room.maxOccupancy} khách</p><ul className="amenity-list">{room.amenities.map((amenity) => <li key={amenity}><Icon name="check" size={14} />{amenity}</li>)}</ul><div className="room-card-footer"><PriceDisplay amount={room.basePricePerNight} suffix="/ đêm · giá cơ bản" /><LinkButton to={`/rooms/${room.id}${search ? `?${new URLSearchParams(search)}` : ''}`} variant="secondary">Chi tiết loại phòng <Icon name="arrow" size={16} /></LinkButton></div></div></Card>
}
