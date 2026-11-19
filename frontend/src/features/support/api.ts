import { apiAssetUrl, apiRequest } from '../../shared/api/client'

export type Conversation = {
  id: number; status: 'AI_ACTIVE' | 'WAITING_STAFF' | 'STAFF_ACTIVE' | 'CLOSED'
  customerName?: string; assignedToMe: boolean; staffOnline: boolean; unread: number; preview: string; updatedAt: string
}
export type ConciergeAnswer = {
  text: string; source: 'LIVE_AI' | 'FAQ' | 'HOTEL_PMS' | 'UNAVAILABLE'
  faqId?: number; arrival?: string; departure?: string; guests?: number; loginRequired: boolean; suppressed: boolean
  rooms: { id: number; name: string; coverImageUrl?: string; description?: string; capacity: number; amenities: string[]
    available?: number; payable?: number }[]
  bookings: { code: string; roomType: string; startAt: string; endAt: string; status: string; paymentStatus: string }[]
}
export type ChatMessage = { id: number; sender: 'CUSTOMER' | 'AI' | 'STAFF'; content: string; createdAt: string; details?: ConciergeAnswer }
export type MessageDraft = { clientId: string; content: string }
export type FaqDraft = { category: string; question: string; answer: string; active: boolean }
export type Faq = FaqDraft & { id: number; updatedAt: string }
export type SupportStatus = { aiConfigured: boolean; staffOnline: number }

function path(staff: boolean, id?: number, suffix = '') {
  return `/api/v1/${staff ? 'staff/' : ''}support/conversations${id === undefined ? '' : `/${id}`}${suffix}`
}

export const supportApi = {
  status: () => apiRequest<SupportStatus>('/api/v1/support/status', { auth: false }),
  ask: (body: { conversationId?: number; clientId: string; text: string; action: 'CHAT' | 'ROOMS' | 'POLICY' | 'BOOKINGS'
    arrival?: string; departure?: string; guests?: number }, anonymous: boolean) =>
    apiRequest<ConciergeAnswer>('/api/v1/support/ai/message', { method: 'POST', body, auth: !anonymous }),
  list: (staff: boolean, before?: number) => apiRequest<Conversation[]>(path(staff) + (before ? `?before=${before}` : '')),
  open: () => apiRequest<Conversation>(path(false), { method: 'POST' }),
  get: (staff: boolean, id: number) => apiRequest<Conversation>(path(staff, id)),
  messages: (staff: boolean, id: number, cursor = '') => apiRequest<ChatMessage[]>(path(staff, id, '/messages') + cursor),
  send: (staff: boolean, id: number, body: MessageDraft) =>
    apiRequest<ChatMessage>(path(staff, id, '/messages'), { method: 'POST', body }),
  read: (staff: boolean, id: number, through: number) =>
    apiRequest<void>(path(staff, id, '/read'), { method: 'POST', body: { through } }),
  handoff: (id: number) => apiRequest<Conversation>(path(false, id, '/request-staff'), { method: 'POST' }),
  claim: (id: number) => apiRequest<Conversation>(path(true, id, '/claim'), { method: 'POST' }),
  close: (id: number) => apiRequest<Conversation>(path(true, id, '/close'), { method: 'POST' }),
  faqList: (query: string, before?: number) => apiRequest<Faq[]>(
    `/api/v1/admin/support/faqs?q=${encodeURIComponent(query)}${before ? `&before=${before}` : ''}`),
  saveFaq: (body: FaqDraft, id?: number) => apiRequest<Faq>(`/api/v1/admin/support/faqs${id ? `/${id}` : ''}`,
    { method: id ? 'PUT' : 'POST', body }),
}

export function supportSocketUrl() {
  const url = new URL(apiAssetUrl('/api/v1/support/socket'), window.location.origin)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  return url.toString()
}
