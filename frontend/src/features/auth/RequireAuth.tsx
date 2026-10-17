import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { Button, Card, EmptyState, LoadingState } from '../../shared/ui'
import { Icon } from '../../shared/ui/Icon'
import { useAuth } from './AuthProvider'
import type { Role } from './types'

export function RequireAuth({ roles }: { roles?: Role[] }) {
  const auth = useAuth()
  const location = useLocation()
  if (auth.status === 'loading') return <Card><LoadingState message="Đang xác thực phiên đăng nhập…" /></Card>
  if (auth.status === 'unavailable') return <Card><EmptyState icon="refresh" title="Chưa thể xác thực tài khoản"
    description="Kết nối máy chủ đang gián đoạn. Bạn có thể thử lại mà không cần đăng nhập lại."
    action={<Button onClick={auth.retry}><Icon name="refresh" />Thử lại</Button>} /></Card>
  if (!auth.user) return <Navigate to="/login" replace state={{ from: location.pathname + location.search + location.hash }} />
  if (roles && !auth.user.roles.some((role) => roles.includes(role))) return <Navigate to="/403" replace />
  return <Outlet />
}
