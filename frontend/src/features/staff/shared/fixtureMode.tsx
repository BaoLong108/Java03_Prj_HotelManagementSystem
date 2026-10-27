import { Alert } from '../../../shared/ui'

export const USE_FIXTURES = import.meta.env.VITE_USE_FIXTURES === 'true'

export function FixtureNotice() {
  return USE_FIXTURES ? <Alert tone="warning">Đang dùng dữ liệu mẫu – chưa nối API thật</Alert> : null
}
