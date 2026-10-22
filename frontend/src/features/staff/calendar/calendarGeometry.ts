import type { CalendarEntry } from './roomCalendarTypes'

/** Geometry only: half-open intervals. Does not infer availability or expire server holds. */
export function positionCalendarBar(startAt: string, endAt: string, from: string, to: string) {
  const [start, end, viewStart, viewEnd] = [startAt, endAt, from, to].map(Date.parse)
  if (![start, end, viewStart, viewEnd].every(Number.isFinite)
      || start >= end || viewStart >= viewEnd || end <= viewStart || start >= viewEnd) return null
  const left = Math.max(start, viewStart)
  const right = Math.min(end, viewEnd)
  return {
    leftPercent: (left - viewStart) / (viewEnd - viewStart) * 100,
    widthPercent: (right - left) / (viewEnd - viewStart) * 100,
    clippedStart: start < viewStart, clippedEnd: end > viewEnd,
  }
}

export function visibleCalendarEntries(entries: readonly CalendarEntry[], from: string, to: string) {
  const laneEnds: number[] = []
  return entries
    .filter(entry => entry.kind === 'BLOCK' || ['PENDING_PAYMENT', 'CONFIRMED', 'COMPLETED'].includes(entry.status))
    .slice().sort((a, b) => Date.parse(a.startAt) - Date.parse(b.startAt) || a.id - b.id)
    .flatMap(entry => {
      const position = positionCalendarBar(entry.startAt, entry.endAt, from, to)
      if (!position) return []
      const start = Math.max(Date.parse(entry.startAt), Date.parse(from))
      let lane = laneEnds.findIndex(end => end <= start)
      if (lane < 0) lane = laneEnds.length
      laneEnds[lane] = Math.min(Date.parse(entry.endAt), Date.parse(to))
      return [{ entry, position, lane }]
    })
}
