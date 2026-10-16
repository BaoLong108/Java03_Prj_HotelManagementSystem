import { Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider } from './features/auth/AuthProvider'
import { AuthPage } from './features/auth/AuthPage'
import { RequireAuth } from './features/auth/RequireAuth'
import { AccountPage } from './features/auth/AccountPage'
import { DashboardPage } from './features/dashboard/DashboardPage'
import { RoomsPage } from './features/rooms/RoomsPage'
import { BookingPage } from './features/booking/BookingPage'
import { AdminPage, StaffPage } from './features/staff/WorkspacePages'
import { StaffLayout } from './features/staff/StaffLayout'
import { AppLayout } from './shared/layout/AppLayout'
import { ForbiddenPage, NotFoundPage, UiKitPage } from './shared/ui/ExamplePages'

export default function App() {
  return <AuthProvider><Routes><Route element={<AppLayout />}>
    <Route index element={<DashboardPage />} />
    <Route path="rooms" element={<RoomsPage />} />
    <Route path="booking" element={<BookingPage />} />
    <Route path="login" element={<AuthPage mode="login" />} />
    <Route path="register" element={<AuthPage mode="register" />} />
    <Route element={<RequireAuth />}><Route path="account" element={<AccountPage />} /></Route>
    <Route element={<RequireAuth roles={['STAFF', 'ADMIN']} />}>
      <Route path="staff" element={<StaffLayout />}>
        <Route index element={<Navigate to="calendar" replace />} />
        <Route path="calendar" element={<StaffPage page="calendar" />} />
        <Route path="bookings" element={<StaffPage page="bookings" />} />
      </Route>
    </Route>
    <Route element={<RequireAuth roles={['ADMIN']} />}><Route path="admin" element={<AdminPage />} /></Route>
    <Route path="ui-kit" element={<UiKitPage />} />
    <Route path="403" element={<ForbiddenPage />} />
    <Route path="*" element={<NotFoundPage />} />
  </Route></Routes></AuthProvider>
}
