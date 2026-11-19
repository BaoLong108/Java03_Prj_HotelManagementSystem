import { useEffect, useState } from 'react'
import { bookingApi } from '../booking/api'
import type { PriceQuote } from '../booking/types'
import { roomApi } from './api'
import type { RoomTypeResponse } from './api'
import { errorMessage } from '../../shared/api/client'
import { stayParams, validateStay } from './search'
import type { StaySearch } from './search'

export type SearchRoom = RoomTypeResponse & {
  availableRoomCount?: number
  available?: boolean
  quote?: PriceQuote & { appliedTier: string }
  quoteError?: string
}
type Result = { key: string; rooms?: SearchRoom[]; error?: string }

// Availability and each price come from the existing backend, never from local inventory arithmetic.
export function useRoomSearch(stay: StaySearch, searched: boolean, identity = '') {
  const [retry, setRetry] = useState(0)
  const key = `${stayParams(stay)}|${searched}|${identity}|${retry}`
  useEffect(() => { const refresh = () => setRetry(value => value + 1); window.addEventListener('focus', refresh); return () => window.removeEventListener('focus', refresh) }, [])
  const [result, setResult] = useState<Result>({ key: '' })
  const validation = searched ? Object.values(validateStay(stay)).join(' ') : ''
  useEffect(() => {
    const controller = new AbortController()
    const body = { roomTypeId: 0, guestCount: stay.adults + stay.children,
      startAt: `${stay.arrival}T12:00:00+07:00`, endAt: `${stay.departure}T10:00:00+07:00` }
    const checkAvailability = searched && !validation
    Promise.all([roomApi.list(controller.signal), checkAvailability ? bookingApi.search(body, controller.signal) : []])
      .then(async ([catalog, available]) => {
        if (controller.signal.aborted) return
        const matches = new Map(available.map(room => [room.id, room]))
        const rooms = catalog.map<SearchRoom>(room => {
          const match = matches.get(room.id)
          const count = match?.availableRoomCount ?? 0
          return checkAvailability ? { ...room, available: count > 0, availableRoomCount: count } : room
        })
        const priced = await Promise.all(rooms.map(async room => {
          if (!room.available) return room
          try {
            return { ...room, quote: await bookingApi.quote(room, { ...body, roomTypeId: room.id }, controller.signal) }
          } catch (error) { return { ...room, quoteError: errorMessage(error) } }
        }))
        if (!controller.signal.aborted) setResult({ key, rooms: priced })
      }).catch(error => { if (!controller.signal.aborted) setResult({ key, error: errorMessage(error) }) })
    return () => controller.abort()
  }, [key, searched, validation, stay.arrival, stay.departure, stay.adults, stay.children])
  const current = result.key === key ? result : { key }
  return { rooms: current.rooms ?? [], loading: !current.rooms && !current.error,
    error: current.error, validation, retry: () => setRetry(value => value + 1) }
}
