import { apiRequest } from '../../shared/api/client'

export type RateKind = 'OVERRIDE' | 'HOLIDAY' | 'SEASONAL' | 'WEEKEND'
export type RateRule = { id: number; kind: RateKind; startsOn: string; endsOn: string; nightlyRate: number }
export type NightlyRate = { date: string; nightlyRate: number; weekend: boolean; kind: RateKind | null; ruleId: number | null }
export type RateCalendar = { roomTypeId: number; month: string; baseRate: number; nights: NightlyRate[]; rules: RateRule[] }
export type RateDraft = { roomTypeId: number; kind: RateKind; startsOn: string; endsOn: string; nightlyRate: string }
export type RatePreview = { before: NightlyRate; after: NightlyRate }

export const rateLabels: Record<RateKind, string> = {
  OVERRIDE: 'Giá chỉ định', HOLIDAY: 'Ngày lễ', SEASONAL: 'Theo mùa', WEEKEND: 'Cuối tuần',
}

export const rateApi = {
  month: (roomTypeId: number, month: string, signal?: AbortSignal) =>
    apiRequest<RateCalendar>(`/api/v1/admin/rate-calendar?roomTypeId=${roomTypeId}&month=${encodeURIComponent(month)}`, { signal }),
  preview: (body: RateDraft, id?: number) => apiRequest<RatePreview[]>(`/api/v1/admin/rate-calendar/preview${id ? `?ruleId=${id}` : ''}`, { method: 'POST', body }),
  save: (body: RateDraft, id?: number) => apiRequest<RateRule>(`/api/v1/admin/rate-rules${id ? `/${id}` : ''}`, { method: id ? 'PUT' : 'POST', body }),
  deactivate: (roomTypeId: number, id: number) => apiRequest<void>(`/api/v1/admin/rate-rules/${id}/deactivate?roomTypeId=${roomTypeId}`, { method: 'PATCH' }),
  base: (roomTypeId: number, nightlyRate: string) => apiRequest<void>(`/api/v1/admin/rate-calendar/${roomTypeId}/base-rate`, { method: 'PATCH', body: { nightlyRate } }),
}
