import { Route, Routes } from 'react-router-dom'
import { AuthProvider } from './features/auth/AuthProvider'
import { AuthPage } from './features/auth/AuthPage'
import { RequireAuth } from './features/auth/RequireAuth'
import { AccountPage } from './features/auth/AccountPage'
import { DashboardPage } from './features/dashboard/DashboardPage'
import { RoomDetailPage, RoomsPage } from './features/rooms/RoomsPage'
import { BookingPage } from './features/booking/BookingPage'
import { MyBookingsPage } from './features/booking/MyBookingsPage'
import { AdminPage, StaffPage, AdminRoomsPage, AdminSettingsPage } from './features/staff/WorkspacePages'
import { AdminMembersPage } from './features/crm/AdminMembersPage'
import { CustomerProfilePage } from './features/crm/CustomerProfilePage'
import { AppLayout, RouteEffects, StaffLayout } from './shared/layout/AppLayout'
import { StaffLayout as StaffSectionLayout } from './features/staff/StaffLayout'
import { ForbiddenPage, NotFoundPage, UiKitPage } from './shared/ui/ExamplePages'
import { ToastProvider } from './shared/ui'
import { RoomCalendarPage } from './features/staff/calendar/RoomCalendarPage'
import { BookingSearchPage } from './features/staff/bookings/BookingSearchPage'
import { FloorMapPage } from './features/staff/floors/FloorMapPage'
import { RateCalendarPage } from './features/pricing/RateCalendarPage'
import { AnalyticsPage } from './features/analytics/AnalyticsPage'
import { BookingTimelinePage } from './features/checkin/BookingTimelinePage'
import { StaffCheckInPage } from './features/checkin/StaffCheckInPage'
import { PaymentReturnPage } from './features/booking/VnpayPayment'
import { TransactionHistoryPage } from './features/transactions/TransactionHistoryPage'
import { StaffSupportPage } from './features/support/StaffSupportPage'
import { AdminFaqPage } from './features/support/AdminFaqPage'

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
      <Route path="payment-return" element={<PaymentReturnPage />} />
      <Route path="account/transactions" element={<TransactionHistoryPage />} />
      <Route path="my-bookings" element={<MyBookingsPage />} />
      <Route path="my-bookings/:bookingCode" element={<BookingTimelinePage />} />
    </Route>
    <Route path="ui-kit" element={<UiKitPage />} />
    <Route path="403" element={<ForbiddenPage />} />
    <Route path="*" element={<NotFoundPage />} />
  </Route><Route element={<StaffLayout />}>
    <Route element={<RequireAuth roles={['STAFF', 'ADMIN']} />}>
      <Route path="staff" element={<StaffPage />} />
      <Route element={<StaffSectionLayout />}>
        <Route path="staff/bookings" element={<BookingSearchPage />} />
        <Route path="staff/calendar" element={<RoomCalendarPage />} />
      </Route>
      <Route path="staff/rooms" element={<FloorMapPage />} />
      <Route path="staff/check-in-requests" element={<StaffCheckInPage />} />
      <Route path="staff/support" element={<StaffSupportPage />} />
    </Route>
    <Route element={<RequireAuth roles={['ADMIN']} />}>
      <Route path="admin" element={<AdminPage />} />
      <Route path="admin/rooms" element={<AdminRoomsPage />} />
      <Route path="admin/rate-calendar" element={<RateCalendarPage />} />
      <Route path="admin/analytics" element={<AnalyticsPage />} />
      <Route path="admin/members" element={<AdminMembersPage />} />
      <Route path="admin/members/:customerId" element={<CustomerProfilePage />} />
      <Route path="admin/settings" element={<AdminSettingsPage />} />
      <Route path="admin/support/faq" element={<AdminFaqPage />} />
    </Route>
  </Route></Routes></AuthProvider></ToastProvider>
}
