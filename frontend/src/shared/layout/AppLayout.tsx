import { startTransition, useEffect, useRef, useState } from 'react'
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../../features/auth/AuthProvider'
import { roleLabels } from '../../features/auth/types'
import { Icon } from '../ui/Icon'
import type { IconName } from '../ui/Icon'

const primaryNav: { to: string; label: string; icon: IconName }[] = [
  { to: '/', label: 'Trang chủ', icon: 'home' },
  { to: '/rooms', label: 'Loại phòng', icon: 'bed' },
  { to: '/booking', label: 'Tìm phòng', icon: 'calendar' },
]
const titles: Record<string, string> = {
  '/': 'Trang chủ', '/rooms': 'Loại phòng', '/booking': 'Tìm phòng', '/login': 'Đăng nhập',
  '/register': 'Tạo tài khoản', '/account': 'Tài khoản', '/staff': 'Vận hành', '/admin': 'Quản trị', '/ui-kit': 'Mẫu giao diện',
  '/staff/calendar': 'Lịch phòng', '/staff/bookings': 'Danh sách đặt phòng', '/403': 'Không đủ quyền',
}

export function AppLayout() {
  const auth = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const [menuOpen, setMenuOpen] = useState(false)
  const closeButton = useRef<HTMLButtonElement>(null)
  const menuButton = useRef<HTMLButtonElement>(null)
  const isStaff = auth.user?.roles.some((role) => role === 'STAFF' || role === 'ADMIN')
  const isAdmin = auth.user?.roles.includes('ADMIN')

  function closeMenu() { setMenuOpen(false); menuButton.current?.focus() }

  useEffect(() => {
    const media = window.matchMedia('(max-width: 900px)')
    const onChange = () => { if (!media.matches) setMenuOpen(false) }
    media.addEventListener('change', onChange)
    return () => media.removeEventListener('change', onChange)
  }, [])

  useEffect(() => {
    setMenuOpen(false)
    document.title = `${titles[location.pathname] ?? 'Không tìm thấy trang'} · Hotel PMS Mini`
    window.scrollTo(0, 0)
  }, [location.pathname])
  useEffect(() => {
    if (!menuOpen) return
    closeButton.current?.focus()
    const previous = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    function onEscape(event: KeyboardEvent) {
      if (event.key === 'Escape') { setMenuOpen(false); menuButton.current?.focus() }
    }
    window.addEventListener('keydown', onEscape)
    return () => { document.body.style.overflow = previous; window.removeEventListener('keydown', onEscape) }
  }, [menuOpen])

  return <div className="app-shell">
    <a href="#main-content" className="skip-link">Đến nội dung chính</a>
    {menuOpen && <button className="sidebar-overlay" tabIndex={-1} onClick={closeMenu} aria-label="Đóng menu" />}
    <aside id="navigation-sidebar" className={`sidebar ${menuOpen ? 'sidebar--open' : ''}`} aria-label="Thanh điều hướng">
      <div className="sidebar-brand"><Link to="/" className="brand"><span className="brand-icon"><Icon name="building" size={23} /></span>
        <span>hotel<span className="brand-accent">pms</span><small>MINI HOTEL WORKSPACE</small></span></Link>
        <button ref={closeButton} className="icon-button mobile-only" onClick={closeMenu} aria-label="Đóng menu"><Icon name="close" /></button>
      </div>
      <div className="property-switch"><span className="property-icon"><Icon name="key" /></span><div><strong>Hotel PMS Mini</strong><small>Không gian khách sạn</small></div></div>
      <nav className="sidebar-nav" aria-label="Điều hướng chính"><p className="nav-caption">KHÁM PHÁ</p>
        {primaryNav.map((item) => <NavLink key={item.to} to={item.to} end={item.to === '/'}>
          <Icon name={item.icon} />{item.label}<Icon name="chevron" size={15} /></NavLink>)}
        {auth.user && <NavLink to="/account"><Icon name="user" />Tài khoản<Icon name="chevron" size={15} /></NavLink>}
        {!auth.user && auth.status !== 'loading' && <>
          <NavLink to="/login"><Icon name="key" />Đăng nhập</NavLink>
          <NavLink to="/register"><Icon name="user" />Đăng ký</NavLink>
        </>}
        {isStaff && <><p className="nav-caption">QUẢN LÝ</p><NavLink to="/staff"><Icon name="calendar" />Vận hành<Icon name="chevron" size={15} /></NavLink></>}
        {isAdmin && <NavLink to="/admin"><Icon name="shield" />Quản trị<Icon name="chevron" size={15} /></NavLink>}
      </nav>
      <div className="sidebar-bottom"><div className="sidebar-note"><span className="small-icon"><Icon name="building" /></span>
        <strong>Một kỳ nghỉ trọn vẹn</strong><p>Bắt đầu từ những trải nghiệm thật đơn giản.</p></div>
        <p className="sidebar-version">HOTEL PMS MINI <span>GROUP 03</span></p></div>
    </aside>
    <div className="workspace" inert={menuOpen}>
      <header className="topbar"><div className="breadcrumb"><button ref={menuButton} className="icon-button mobile-only"
        aria-label="Mở menu" aria-controls="navigation-sidebar" aria-expanded={menuOpen} onClick={() => setMenuOpen(true)}><Icon name="menu" /></button>
        <Link to="/" className="topbar-brand">Hotel PMS Mini</Link><span className="breadcrumb-slash">/</span><strong className="breadcrumb-page">{titles[location.pathname] ?? '404'}</strong></div>
        {auth.user ? <div className="topbar-account"><Link to="/account" className="account-link">
          <span className="avatar">{auth.user.fullName.slice(0, 1).toLocaleUpperCase('vi')}</span>
          <span className="account-label"><strong>{auth.user.fullName}</strong><small>{auth.user.roles.map((role) => roleLabels[role]).join(' · ')}</small></span></Link>
          <button className="button button--secondary button--small" onClick={() => startTransition(() => { auth.logout(); navigate('/', { replace: true }) })}><Icon name="logout" size={16} />Đăng xuất</button></div>
          : auth.status === 'loading' ? <span className="muted small">Đang xác thực…</span>
          : <nav className="topbar-auth" aria-label="Tài khoản">
            <Link className="button button--secondary button--small" to="/login">Đăng nhập</Link>
            <Link className="button button--primary button--small" to="/register">Đăng ký</Link>
          </nav>}
      </header>
      <main id="main-content" className="main-content" tabIndex={-1}><Outlet /></main>
      <footer className="workspace-footer"><span>Hotel PMS Mini · Nhóm 03</span><span>Đơn giản hơn mỗi ngày.</span></footer>
    </div>
  </div>
}
