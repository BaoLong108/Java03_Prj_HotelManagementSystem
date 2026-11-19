import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, errorMessage } from '../../shared/api/client'
import { supportApi } from './api'
import type { ChatMessage, Conversation, SupportStatus } from './api'

export function mergeMessages(current: ChatMessage[], next: ChatMessage[]) {
  return [...new Map([...current, ...next].map(item => [item.id, item])).values()].sort((a, b) => a.id - b.id)
}

export function useSupportThreads(staff: boolean, enabled: boolean, markRead = true) {
  const [threads, setThreads] = useState<Conversation[]>([])
  const [selected, setSelected] = useState<Conversation>()
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [status, setStatus] = useState<SupportStatus>()
  const [older, setOlder] = useState(false)
  const [moreThreads, setMoreThreads] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const active = useRef<number | undefined>(undefined)
  const requestNumber = useRef(0)
  const listNumber = useRef(0)
  const last = useRef(0)
  const acknowledged = useRef(0)

  const updateMessages = useCallback(async (id: number, after: number, replace: boolean) => {
    const request = ++requestNumber.current
    let cursor = after
    let next: ChatMessage[] = []
    let page: ChatMessage[]
    do {
      page = await supportApi.messages(staff, id, cursor ? `?after=${cursor}` : '')
      if (request !== requestNumber.current || active.current !== id) return
      next = [...next, ...page]
      cursor = page.at(-1)?.id ?? cursor
    } while (after > 0 && page.length === 40)
    const detail = await supportApi.get(staff, id)
    if (request !== requestNumber.current || active.current !== id) return
    setSelected(detail)
    if (!after) setOlder(next.length === 40)
    last.current = Math.max(last.current, cursor)
    setMessages(current => replace ? next : mergeMessages(current, next))
    if (markRead && last.current > acknowledged.current && (!staff || detail.assignedToMe)) {
      const through = last.current
      acknowledged.current = through
      void supportApi.read(staff, id, through).catch(() => {
        if (active.current === id && acknowledged.current === through) acknowledged.current = 0
      })
    }
  }, [staff, markRead])

  const select = useCallback(async (thread: Conversation) => {
    active.current = thread.id
    last.current = 0
    acknowledged.current = 0
    setSelected(thread)
    setMessages([])
    setOlder(false)
    setError('')
    try { await updateMessages(thread.id, 0, true) }
    catch (reason) { if (active.current === thread.id) setError(errorMessage(reason)) }
  }, [updateMessages])

  const update = useCallback(async () => {
    const revision = ++listNumber.current
    try {
      const info = await supportApi.status()
      if (revision !== listNumber.current) return
      setStatus(info)
      if (!enabled) return
      const list = await supportApi.list(staff)
      if (revision !== listNumber.current) return
      setThreads(list)
      setMoreThreads(list.length === 30)
      const id = active.current
      if (id) await updateMessages(id, last.current, !last.current)
      else if (!staff && list[0]) await select(list[0])
      if (revision === listNumber.current) setError('')
    } catch (reason) {
      if (revision === listNumber.current) {
        if (reason instanceof ApiError && reason.status === 404) {
          active.current = undefined
          setSelected(undefined)
          setMessages([])
        }
        setError(errorMessage(reason))
      }
    } finally { if (revision === listNumber.current) setLoading(false) }
  }, [enabled, select, staff, updateMessages])

  useEffect(() => {
    void update()
    return () => { listNumber.current++; requestNumber.current++ }
  }, [update])

  async function loadOlder() {
    const id = active.current
    if (!id || !messages.length) return
    try {
      const page = await supportApi.messages(staff, id, `?before=${messages[0].id}`)
      if (active.current !== id) return
      setMessages(current => mergeMessages(current, page))
      setOlder(page.length === 40)
    } catch (reason) { if (active.current === id) setError(errorMessage(reason)) }
  }

  async function loadThreads() {
    try {
      const page = await supportApi.list(staff, threads.at(-1)?.id)
      setThreads(current => [...new Map([...current, ...page].map(thread => [thread.id, thread])).values()])
      setMoreThreads(page.length === 30)
    } catch (reason) { setError(errorMessage(reason)) }
  }

  return { threads, selected, messages, status, loading, error, update, select, older, loadOlder, moreThreads, loadThreads }
}
