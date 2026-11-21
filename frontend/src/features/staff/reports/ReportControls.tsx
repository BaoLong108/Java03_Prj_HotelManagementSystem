import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError, errorMessage } from '../../../shared/api/client'
import { Alert, Button } from '../../../shared/ui'
import { useAuth } from '../../auth/AuthProvider'
import { getCsv } from './reportApi'
import type { Period } from './reportApi'

export function PeriodFields({ period, onChange }: { period: Period; onChange: (p: Period) => void }) {
  return <><label className="staff-control">Từ ngày<input type="date" value={period.from} onChange={e => onChange({ ...period, from: e.target.value })} /></label>
    <label className="staff-control">Đến ngày (không gồm ngày này)<input type="date" value={period.to} onChange={e => onChange({ ...period, to: e.target.value })} /></label></>
}
export function CsvDownload({ period }: { period: Period }) {
  const { user } = useAuth(), navigate = useNavigate()
  const lock = useRef(false), [busy, setBusy] = useState(false), [error, setError] = useState('')
  if (!user?.roles.includes('ADMIN')) return null
  return <div>{error && <Alert>{error}</Alert>}<Button busy={busy} variant="secondary" onClick={async () => {
    if (lock.current) return
    lock.current = true; setBusy(true); setError('')
    try {
      const blob = await getCsv(period), url = URL.createObjectURL(blob)
      const a = document.createElement('a'); a.href = url; a.download = `bookings-${period.from}-${period.to}.csv`
      document.body.append(a); a.click(); a.remove(); window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch (e) { if (e instanceof ApiError && e.status === 403) navigate('/403', { replace: true }); else setError(errorMessage(e)) }
    finally { lock.current = false; setBusy(false) }
  }}>Xuất CSV</Button></div>
}
