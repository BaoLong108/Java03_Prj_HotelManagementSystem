import { statusLabels } from '../shared/bookingStatus'
import { addHotelDays, formatHotelTime, HOTEL_TIME_ZONE, hotelDate } from '../shared/staffTime'
import { visibleCalendarEntries } from './calendarGeometry'
import type { CalendarEntry, CalendarRoom, RoomCalendar } from './roomCalendarTypes'

export type CalendarSelection = { room: CalendarRoom; entry: CalendarEntry }
export function entryLabel(entry: CalendarEntry) {
  return entry.kind === 'BLOCK' ? 'Chặn' : statusLabels[entry.status]
}
export function entrySummary(entry: CalendarEntry) {
  return `${entry.kind === 'BLOCK' ? entry.reason : entry.bookingCode} · ${entryLabel(entry)}\n${formatHotelTime(entry.startAt)} – ${formatHotelTime(entry.endAt)}`
    + (entry.kind === 'BOOKING' && entry.status === 'PENDING_PAYMENT' ? `\nHạn giữ: ${entry.holdExpiresAt ? formatHotelTime(entry.holdExpiresAt) : 'Chưa được cung cấp'}` : '')
}

export function RoomCalendarGrid({ calendar, onSelect }: { calendar: RoomCalendar; onSelect: (selection: CalendarSelection) => void }) {
  const firstDate = hotelDate(new Date(calendar.from))
  const dates = Array.from({ length: 7 }, (_, index) => addHotelDays(firstDate, index))
  return <div className="calendar-scroll" tabIndex={0} role="region" aria-label="Lịch phòng 7 ngày, cuộn ngang để xem các ngày">
    <table className="room-calendar-grid">
      <caption className="sr-only">Lịch theo phòng vật lý. Múi giờ Asia/Ho_Chi_Minh. Khoảng trống không xác nhận phòng còn trống.</caption>
      <colgroup><col className="calendar-room-column" />{dates.map(date => <col key={date} />)}</colgroup>
      <thead><tr><th scope="col" className="calendar-room-cell">Phòng vật lý<small>Loại phòng · Tầng</small></th>
        {dates.map(date => <th scope="col" key={date}>
          <strong>{new Intl.DateTimeFormat('vi-VN', { timeZone: HOTEL_TIME_ZONE, weekday: 'short' }).format(new Date(`${date}T00:00:00+07:00`))}</strong>
          <span>{date.slice(8)}/{date.slice(5, 7)}</span><small className="calendar-hours"><span>00:00</span><span>12:00</span></small>
        </th>)}
      </tr></thead>
      <tbody>{calendar.rooms.map(room => {
        const bars = visibleCalendarEntries(room.entries, calendar.from, calendar.to)
        const lanes = Math.max(1, ...bars.map(bar => bar.lane + 1))
        return <tr key={room.physicalRoomId} className={room.isActive ? '' : 'calendar-inactive'}>
          <th scope="row" className="calendar-room-cell"><strong>Phòng {room.roomNumber}</strong>
            <span>{room.roomTypeName}</span><small>{room.floorNumber === null ? 'Chưa có tầng' : `Tầng ${room.floorNumber}`}</small>
            {!room.isActive && <span className="inactive-label">Ngừng bán</span>}
          </th>
          <td colSpan={7} className="calendar-timeline-cell"><div className="calendar-track" style={{ height: Math.max(108, lanes * 68 + 16) }}>
            {room.blockError && <p className="calendar-room-error" role="status">{room.blockError}</p>}
            {bars.map(({ entry, position, lane }) => <button key={`${entry.kind}-${entry.id}`} type="button"
              className={`calendar-bar calendar-bar--${entry.kind === 'BLOCK' ? 'block' : entry.status.toLowerCase()}`}
              style={{ left: `${position.leftPercent}%`, width: `${position.widthPercent}%`, top: 8 + lane * 68 }}
              aria-label={`Phòng ${room.roomNumber}: ${entrySummary(entry)}`} title={entrySummary(entry)}
              onClick={() => onSelect({ room, entry })}>
              <strong>{entryLabel(entry)}{entry.kind === 'BOOKING' ? ` · ${entry.bookingCode}` : ''}</strong>
              <span>{entry.kind === 'BLOCK' ? entry.reason : entry.status === 'PENDING_PAYMENT'
                ? `Hạn giữ: ${entry.holdExpiresAt ? formatHotelTime(entry.holdExpiresAt) : 'Chưa được cung cấp'}`
                : `${formatHotelTime(entry.startAt)} – ${formatHotelTime(entry.endAt)}`}</span>
              {(position.clippedStart || position.clippedEnd) && <span className="sr-only">Khoảng lịch kéo dài ngoài phạm vi đang xem.</span>}
            </button>)}
          </div></td>
        </tr>
      })}</tbody>
    </table>
  </div>
}
