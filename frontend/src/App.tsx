import { Route, Routes } from 'react-router-dom'
import { AuthProvider } from './features/auth/AuthProvider'
import { AuthPage } from './features/auth/AuthPage'
import { RequireAuth } from './features/auth/RequireAuth'
import { AccountPage } from './features/auth/AccountPage'
import { DashboardPage } from './features/dashboard/DashboardPage'
import { RoomDetailPage, RoomsPage } from './features/rooms/RoomsPage'
import { BookingPage, MyBookingsPage } from './features/booking/BookingPage'
import { AdminPage, StaffPage, AdminRoomsPage, AdminMembersPage, AdminSettingsPage, RoomOperationsPage } from './features/staff/WorkspacePages'
import { AppLayout, RouteEffects, StaffLayout } from './shared/layout/AppLayout'
import { StaffLayout as StaffSectionLayout } from './features/staff/StaffLayout'
import { ForbiddenPage, NotFoundPage, UiKitPage } from './shared/ui/ExamplePages'
import { ToastProvider } from './shared/ui'
import { RoomCalendarPage } from './features/staff/calendar/RoomCalendarPage'
import { FloorMapPage } from './features/staff/floors/FloorMapPage'
import { RateCalendarPage } from './features/pricing/RateCalendarPage'
import { AnalyticsPage } from './features/analytics/AnalyticsPage'
import { BookingTimelinePage } from './features/checkin/BookingTimelinePage'
import { StaffCheckInPage } from './features/checkin/StaffCheckInPage'
import { StaffBookings } from './features/staff/bookings/DailyBookings'
import { BookingExportPage } from './features/staff/bookings/BookingExportPage'
import { PaymentReturnPage } from './features/booking/VnpayPayment'

export default function App() {
  return <ToastProvider><AuthProvider><RouteEffects /><Routes><Route element={<AppLayout />}>
    <Route index element={<DashboardPage />} />
    <Route path="rooms" element={<RoomsPage />} />
    <Route path="rooms/:roomTypeId" element={<RoomDetailPage />} />
    <Route path="booking" element={<BookingPage />} />
    <Route path="login" element={<AuthPage mode="login" />} />
    <Route path="register" element={<AuthPage mode="register" />} />
    <Route element={<RequireAuth />}><Route path="account" element={<AccountPage />} /></Route>
    <Route element={<RequireAuth roles={['CUSTOMER']} />}>
      <Route path="my-bookings" element={<MyBookingsPage />} />
      <Route path="my-bookings/:bookingCode" element={<BookingTimelinePage />} />
      <Route path="payment-return" element={<PaymentReturnPage />} />
    </Route>
    <Route path="ui-kit" element={<UiKitPage />} />
    <Route path="403" element={<ForbiddenPage />} />
    <Route path="*" element={<NotFoundPage />} />
  </Route><Route element={<StaffLayout />}>
    <Route element={<RequireAuth roles={['STAFF', 'ADMIN']} />}>
      <Route path="staff" element={<StaffPage />} />
      <Route element={<StaffSectionLayout />}>
        <Route path="staff/bookings" element={<StaffBookings />} />
        <Route path="staff/calendar" element={<RoomCalendarPage />} />
      </Route>
      <Route path="staff/rooms" element={<FloorMapPage />} />
      <Route path="staff/room-blocks" element={<RoomOperationsPage />} />
      <Route path="staff/check-in-requests" element={<StaffCheckInPage />} />
    </Route>
    <Route element={<RequireAuth roles={['ADMIN']} />}>
      <Route path="admin" element={<AdminPage />} />
      <Route path="admin/rooms" element={<AdminRoomsPage />} />
      <Route path="admin/rate-calendar" element={<RateCalendarPage />} />
      <Route path="admin/analytics" element={<AnalyticsPage />} />
      <Route path="admin/booking-export" element={<BookingExportPage />} />
      <Route path="admin/members" element={<AdminMembersPage />} />
      <Route path="admin/settings" element={<AdminSettingsPage />} />
    </Route>
  </Route></Routes></AuthProvider></ToastProvider>
}
