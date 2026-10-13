import { Route, Routes } from 'react-router-dom'
import { AuthProvider } from './features/auth/AuthProvider'
import { AuthPage } from './features/auth/AuthPage'
import { RequireAuth } from './features/auth/RequireAuth'
import { AccountPage } from './features/auth/AccountPage'
import { DashboardPage } from './features/dashboard/DashboardPage'
import { RoomsPage } from './features/rooms/RoomsPage'
import { BookingPage } from './features/booking/BookingPage'
import { AdminPage, StaffPage } from './features/staff/WorkspacePages'
import { AppLayout } from './shared/layout/AppLayout'
import { NotFoundPage, UiKitPage } from './shared/ui/ExamplePages'

export default function App() {
  return <AuthProvider><Routes><Route element={<AppLayout />}>
    <Route index element={<DashboardPage />} />
    <Route path="rooms" element={<RoomsPage />} />
    <Route path="booking" element={<BookingPage />} />
    <Route path="login" element={<AuthPage mode="login" />} />
    <Route path="register" element={<AuthPage mode="register" />} />
    <Route element={<RequireAuth />}><Route path="account" element={<AccountPage />} /></Route>
    <Route element={<RequireAuth roles={['STAFF', 'ADMIN']} />}><Route path="staff" element={<StaffPage />} /></Route>
    <Route element={<RequireAuth roles={['ADMIN']} />}><Route path="admin" element={<AdminPage />} /></Route>
    <Route path="ui-kit" element={<UiKitPage />} />
    <Route path="*" element={<NotFoundPage />} />
  </Route></Routes></AuthProvider>
}
