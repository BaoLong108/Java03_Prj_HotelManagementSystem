export type StaySearch = { arrival: string; departure: string; adults: number; children: number }

export function hotelToday(now = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Ho_Chi_Minh', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now)
  return ['year', 'month', 'day'].map((type) => parts.find((part) => part.type === type)?.value).join('-')
}
export function validDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const timestamp = Date.parse(`${value}T00:00:00Z`)
  return Number.isFinite(timestamp) && new Date(timestamp).toISOString().slice(0, 10) === value
}
export function addDays(value: string, days: number): string {
  if (!validDate(value)) return ''
  return new Date(Date.parse(`${value}T00:00:00Z`) + days * 86400000).toISOString().slice(0, 10)
}
export function nightsBetween(arrival: string, departure: string): number | null {
  if (!validDate(arrival) || !validDate(departure) || departure <= arrival) return null
  return (Date.parse(`${departure}T00:00:00Z`) - Date.parse(`${arrival}T00:00:00Z`)) / 86400000
}
export function validateStay(stay: StaySearch, today = hotelToday()): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!validDate(stay.arrival) || stay.arrival < today) errors.arrival = 'Chọn ngày đến từ hôm nay trở đi.'
  if (!validDate(stay.departure) || stay.departure <= stay.arrival) errors.departure = 'Ngày đi phải sau ngày đến.'
  if (!Number.isInteger(stay.adults) || stay.adults < 1 || stay.adults > 10 || !Number.isInteger(stay.children) || stay.children < 0 || stay.children > 10) errors.guests = 'Chọn 1–10 người lớn và 0–10 trẻ em.'
  return errors
}
export function readStay(params: URLSearchParams, today = hotelToday()): StaySearch {
  const count = (key: string, fallback: number, min: number) => {
    const raw = params.get(key)
    const value = raw == null || raw === '' ? fallback : Number(raw)
    return Number.isInteger(value) && value >= min && value <= 10 ? value : fallback
  }
  return { arrival: params.get('arrival') ?? today, departure: params.get('departure') ?? addDays(today, 1), adults: count('adults', 2, 1), children: count('children', 0, 0) }
}
export function stayParams(stay: StaySearch): string {
  return new URLSearchParams({ arrival: stay.arrival, departure: stay.departure, adults: String(stay.adults), children: String(stay.children) }).toString()
}
export function displayDate(value: string) {
  if (!validDate(value)) return 'Chưa chọn'
  return new Intl.DateTimeFormat('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric', timeZone: 'UTC' }).format(new Date(`${value}T00:00:00Z`))
}
