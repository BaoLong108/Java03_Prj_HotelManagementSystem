import { NavLink, Outlet } from 'react-router-dom'

export function StaffLayout() {
  return <div className="staff-layout">
    <nav className="staff-nav" aria-label="Điều hướng nhân viên">
      <NavLink to="/staff/calendar">Lịch phòng</NavLink>
      <NavLink to="/staff/bookings">Tra cứu booking</NavLink>
    </nav>
    <Outlet />
  </div>
}
