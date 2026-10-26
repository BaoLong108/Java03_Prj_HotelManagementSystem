export const HOTEL_TIME_ZONE = 'Asia/Ho_Chi_Minh'
const DAY_MS = 86_400_000

export function hotelDate(now = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: HOTEL_TIME_ZONE, year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(now)
  const part = (type: string) => parts.find(item => item.type === type)?.value
  return `${part('year')}-${part('month')}-${part('day')}`
}

export function isHotelDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const instant = new Date(`${value}T00:00:00+07:00`)
  return Number.isFinite(instant.getTime()) && hotelDate(instant) === value
}

export function addHotelDays(date: string, days: number): string {
  if (!isHotelDate(date) || !Number.isInteger(days)) throw new Error('Ngày không hợp lệ.')
  return hotelDate(new Date(Date.parse(`${date}T00:00:00+07:00`) + days * DAY_MS))
}

export function calendarRange(date: string) {
  if (!isHotelDate(date)) throw new Error('Ngày bắt đầu không hợp lệ.')
  return { from: `${date}T00:00:00+07:00`, to: `${addHotelDays(date, 7)}T00:00:00+07:00` }
}

export function isOffsetDateTime(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})$/.test(value)
    && Number.isFinite(Date.parse(value))
}

export function formatHotelTime(value: string): string {
  if (!isOffsetDateTime(value)) return 'Chưa có thời gian'
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: HOTEL_TIME_ZONE, year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(new Date(value))
  const part = (type: string) => parts.find(item => item.type === type)?.value
  return `${part('day')}/${part('month')}/${part('year')} ${part('hour')}:${part('minute')}`
}
