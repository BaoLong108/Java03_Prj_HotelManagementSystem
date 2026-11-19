import { useEffect, useState } from 'react'
import { errorMessage } from '../../shared/api/client'
import { useAuth } from '../auth/AuthProvider'
import { bookingApi } from '../booking/api'
import { toCheckInIso, toCheckOutIso } from '../booking/types'
import type { PriceQuote } from '../booking/types'
import { roomApi } from './api'
import type { RoomTypeResponse } from './api'
import { validateStay } from './search'
import type { StaySearch } from './search'

export function useSelectedRoom(id: number, stay: StaySearch | null) {
  const { user, status } = useAuth()
  const [revision, setRevision] = useState(0)
  const [details, setDetails] = useState<{ id: number; room?: RoomTypeResponse; error?: string }>()
  const [result, setResult] = useState<{ key: string; available?: boolean; quote?: PriceQuote; error?: string }>()
  const { arrival = '', departure = '', adults = 0, children = 0 } = stay ?? {}
  const validation = stay ? Object.values(validateStay(stay)).join(' ') : ''
  const check = stay !== null && !validation && status !== 'loading'
  const room = details?.id === id ? details.room : undefined
  const key = [id, arrival, departure, adults, children, user?.id, status, revision].join('|')

  useEffect(() => {
    let controller: AbortController | undefined
    function load() {
      controller?.abort()
      const request = new AbortController()
      controller = request
      roomApi.get(id, request.signal).then(room => {
        if (room.id !== id) throw new Error('Thông tin không khớp hạng phòng đã chọn.')
        if (!request.signal.aborted) setDetails({ id, room })
      }).catch(error => {
        if (!request.signal.aborted) setDetails(current => current?.room ? current : { id, error: errorMessage(error) })
      })
    }
    load()
    window.addEventListener('focus', load)
    return () => { window.removeEventListener('focus', load); controller?.abort() }
  }, [id, revision])

  useEffect(() => {
    if (!room || !check) return
    const controller = new AbortController()
    const request = { roomTypeId: id, guestCount: adults + children, startAt: toCheckInIso(arrival), endAt: toCheckOutIso(departure) }
    async function load(selected: RoomTypeResponse) {
      try {
        const available = await bookingApi.search(request, controller.signal)
        if (controller.signal.aborted) return
        const match = available.find(item => item.id === id)
        if (!match || match.availableRoomCount === 0) {
          setResult({ key, available: false })
          return
        }
        const quote = await bookingApi.quote(selected, request, controller.signal)
        if (!controller.signal.aborted) setResult({ key, available: true, quote })
      } catch (error) {
        if (!controller.signal.aborted) setResult({ key, error: errorMessage(error) })
      }
    }
    void load(room)
    return () => controller.abort()
  }, [room, check, key, id, arrival, departure, adults, children])

  return { room, roomError: details?.id === id ? details.error : undefined,
    current: check && result?.key === key ? result : undefined, validation,
    retry: () => { setDetails(undefined); setRevision(value => value + 1) } }
}
