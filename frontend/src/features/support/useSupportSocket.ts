import { useCallback, useEffect, useRef, useState } from 'react'
import { readSession } from '../../shared/auth/session'
import { supportSocketUrl } from './api'
import type { ChatMessage, MessageDraft } from './api'

class Interrupted extends Error {}
type Pending = { resolve: (message: ChatMessage) => void; reject: (error: Error) => void; timeout: number }
type Frame = { type: string; clientId?: string; message?: ChatMessage | string }

export function useSupportSocket(identity: string | undefined, onUpdate: () => void) {
  const [connected, setConnected] = useState(false)
  const onUpdateRef = useRef(onUpdate)
  const current = useRef<WebSocket | undefined>(undefined)
  const authenticated = useRef(false)
  const pending = useRef(new Map<string, Pending>())
  useEffect(() => { onUpdateRef.current = onUpdate }, [onUpdate])
  useEffect(() => {
    setConnected(false)
    if (!identity) return
    let stopped = false
    let retries = 0
    let retryTimer = 0
    let updateTimer = 0
    let heartbeat = 0
    let lastPong = 0
    const requests = pending.current

    function rejectPending() {
      for (const request of requests.values()) {
        window.clearTimeout(request.timeout)
        request.reject(new Interrupted())
      }
      requests.clear()
    }

    function connect() {
      const token = readSession()?.accessToken
      if (stopped || !token) return
      const connection = new WebSocket(supportSocketUrl())
      current.current = connection
      connection.onopen = () => connection.send(JSON.stringify({ type: 'auth', token }))
      connection.onmessage = (event) => {
        if (current.current !== connection || stopped) return
        let frame: Frame
        try { frame = JSON.parse(event.data) as Frame } catch { return }
        if (frame.type === 'ready') {
          retries = 0
          lastPong = Date.now()
          authenticated.current = true
          setConnected(true)
          onUpdateRef.current()
          heartbeat = window.setInterval(() => {
            if (Date.now() - lastPong > 50_000) { connection.close(); return }
            connection.send(JSON.stringify({ type: 'ping' }))
          }, 20_000)
        } else if (frame.type === 'pong') {
          lastPong = Date.now()
        } else if (frame.type === 'changed' || frame.type === 'presence') {
          window.clearTimeout(updateTimer)
          updateTimer = window.setTimeout(() => onUpdateRef.current(), 80)
        } else if (frame.clientId && (frame.type === 'ack' || frame.type === 'error')) {
          const request = requests.get(frame.clientId)
          if (!request) return
          requests.delete(frame.clientId)
          window.clearTimeout(request.timeout)
          if (typeof frame.message === 'object' && frame.type === 'ack') request.resolve(frame.message)
          else request.reject(new Error(typeof frame.message === 'string' ? frame.message : 'Chưa thể gửi tin nhắn.'))
        }
      }
      connection.onclose = (event) => {
        if (current.current !== connection || stopped) return
        window.clearInterval(heartbeat)
        authenticated.current = false
        setConnected(false)
        rejectPending()
        const delay = event.code === 1008 ? 30_000 : Math.min(1000 * 2 ** retries++, 15_000)
        retryTimer = window.setTimeout(connect, delay)
      }
      connection.onerror = () => connection.close()
    }

    connect()
    return () => {
      stopped = true
      window.clearTimeout(retryTimer)
      window.clearTimeout(updateTimer)
      window.clearInterval(heartbeat)
      authenticated.current = false
      current.current?.close()
      current.current = undefined
      rejectPending()
    }
  }, [identity])

  const send = useCallback(async (id: number, staff: boolean, draft: MessageDraft, fallback: () => Promise<ChatMessage>) => {
    const socket = current.current
    if (!authenticated.current || !socket || socket.readyState !== WebSocket.OPEN) return fallback()
    try {
      return await new Promise<ChatMessage>((resolve, reject) => {
        const timeout = window.setTimeout(() => {
          pending.current.delete(draft.clientId)
          reject(new Interrupted())
        }, 3500)
        pending.current.set(draft.clientId, { resolve, reject, timeout })
        try { socket.send(JSON.stringify({ type: 'send', conversationId: id, staff, ...draft })) }
        catch {
          window.clearTimeout(timeout)
          pending.current.delete(draft.clientId)
          reject(new Interrupted())
        }
      })
    } catch (error) {
      if (error instanceof Interrupted) return fallback()
      throw error
    }
  }, [])
  return { connected, send }
}
