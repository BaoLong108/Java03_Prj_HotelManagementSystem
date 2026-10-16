import { startTransition, useEffect, useRef, useState } from 'react'
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../../features/auth/AuthProvider'
import { roleLabels } from '../../features/auth/types'
import { Button, ConfirmDialog, Dialog, useToast } from '../ui'
import { Icon } from '../ui/Icon'
import type { IconName } from '../ui/Icon'

const titles: Record<string, string> = {
  '/': 'Một khoảng nghỉ, thật riêng', '/rooms': 'Phòng & không gian', '/booking': 'Đặt phòng', '/login': 'Đăng nhập',
  '/register': 'Tạo tài khoản', '/account': 'Tài khoản', '/my-bookings': 'Kỳ nghỉ của tôi',
  '/staff': 'Tổng quan vận hành', '/staff/bookings': 'Quản lý đặt phòng', '/staff/calendar': 'Lịch phòng',
  '/staff/rooms': 'Tình trạng phòng', '/admin': 'Quản trị khách sạn', '/admin/rooms': 'Danh mục phòng',
  '/admin/members': 'Thành viên & chính sách', '/admin/settings': 'Cài đặt', '/ui-kit': 'Thư viện giao diện',
}

export function Brand({ workspace = false }: { workspace?: boolean }) {
  return <Link to={workspace ? '/staff' : '/'} className="brand" aria-label={workspace ? 'Hotel PMS — Vận hành' : 'Hotel PMS — Trang chủ'}>
    <span className="brand-mark"><Icon name="building" size={24} /></span><span>hotel<span className="brand-accent">pms</span><small>{workspace ? 'PROPERTY WORKSPACE' : 'A LITTLE SPACE. A BETTER STAY.'}</small></span></Link>
}

export function RouteEffects() {
  const location = useLocation()
  const previous = useRef(location.pathname)
  useEffect(() => {
    document.title = `${titles[location.pathname] ?? (location.pathname.startsWith('/rooms/') ? 'Chi tiết loại phòng' : 'Không tìm thấy trang')} · Hotel PMS`
    if (location.hash) document.getElementById(location.hash.slice(1))?.scrollIntoView()
    else window.scrollTo(0, 0)
    if (previous.current !== location.pathname) document.getElementById('main-content')?.focus({ preventScroll: true })
    previous.current = location.pathname
  }, [location.pathname, location.hash])
  return null
}

export function AccountMenu() {
  const auth = useAuth()
  const navigate = useNavigate()
  const notify = useToast()
  const location = useLocation()
  const details = useRef<HTMLDetailsElement>(null)
  const [confirm, setConfirm] = useState(false)
  useEffect(() => { details.current?.removeAttribute('open') }, [location.pathname])
  if (auth.status === 'loading') return <span className="small muted">Đang xác thực…</span>
  if (auth.status === 'unavailable') return <Button variant="secondary" onClick={auth.retry}>Thử kết nối lại</Button>
  if (!auth.user) return <Link className="button button--secondary button--small" to="/login"><Icon name="user" size={16} />Đăng nhập</Link>
  const user = auth.user
  return <><details ref={details} className="account-menu" onKeyDown={(event) => { if (event.key === 'Escape') { details.current?.removeAttribute('open'); details.current?.querySelector('summary')?.focus() } }}
    onBlur={(event) => { if (!event.currentTarget.contains(event.relatedTarget)) details.current?.removeAttribute('open') }}>
    <summary aria-label={`Tài khoản ${user.fullName}`}><span className="avatar">{user.fullName.slice(0, 1).toLocaleUpperCase('vi')}</span><span className="account-label">{user.fullName.split(' ').at(-1)}</span><Icon name="down" size={14} /></summary>
    <div className="account-dropdown"><strong>{user.fullName}</strong><p className="small muted">{user.roles.map((role) => roleLabels[role]).join(' · ')}</p>
      <Link to="/account"><Icon name="user" size={17} />Tài khoản của tôi</Link>
      {user.roles.includes('CUSTOMER') && <Link to="/my-bookings"><Icon name="calendar" size={17} />Kỳ nghỉ của tôi</Link>}
      {user.roles.some((role) => role === 'STAFF' || role === 'ADMIN') && <Link to="/staff"><Icon name="grid" size={17} />Không gian quản lý</Link>}
      <button onClick={() => { details.current?.removeAttribute('open'); setConfirm(true) }}><Icon name="logout" size={17} />Đăng xuất</button>
    </div>
  </details><ConfirmDialog open={confirm} title="Đăng xuất tài khoản?" description="Bạn có thể đăng nhập lại bất cứ lúc nào để tiếp tục quản lý kỳ nghỉ." confirmLabel="Đăng xuất" onClose={() => setConfirm(false)}
    onConfirm={() => { startTransition(() => { auth.logout(); navigate('/', { replace: true }) }); notify('Bạn đã đăng xuất an toàn.') }} /></>
}

export function AppLayout() {
  const [menu, setMenu] = useState(false)
  const location = useLocation()
  useEffect(() => { setMenu(false) }, [location.pathname, location.hash])
  const links = <><NavLink to="/" end>Trang chủ</NavLink><NavLink to="/rooms">Phòng & không gian</NavLink><Link to="/#membership">Đặc quyền thành viên</Link></>
  return <div className="guest-shell"><a href="#main-content" className="skip-link">Đến nội dung chính</a>
    <header className="guest-header"><div className="guest-header-inner"><Brand /><nav className="guest-nav" aria-label="Điều hướng chính">{links}</nav>
      <div className="header-actions"><AccountMenu /><Link className="button button--primary header-book" to="/rooms">Tìm phòng <Icon name="arrow" size={16} /></Link>
        <button className="icon-button mobile-only" aria-label="Mở menu" aria-haspopup="dialog" onClick={() => setMenu(true)}><Icon name="menu" /></button></div></div></header>
    <Dialog open={menu} onClose={() => setMenu(false)} title="Khám phá Hotel PMS"><nav className="mobile-nav" aria-label="Điều hướng trên điện thoại">{links}<Link to="/booking">Đặt phòng</Link></nav></Dialog>
    <main id="main-content" className={`guest-main ${location.pathname === '/' ? 'guest-main--home' : ''}`} tabIndex={-1}><Outlet /></main>
    <footer className="guest-footer"><div className="footer-main"><div><Brand /><p>Một khoảng nghỉ, thật riêng.<br />Một trải nghiệm, thật trọn vẹn.</p></div>
      <div><h2>KHÁM PHÁ</h2><Link to="/rooms">Phòng & không gian</Link><Link to="/booking">Lên kế hoạch kỳ nghỉ</Link><Link to="/#membership">Đặc quyền thành viên</Link></div>
      <div><h2>TÀI KHOẢN</h2><Link to="/account">Thông tin cá nhân</Link><Link to="/my-bookings">Kỳ nghỉ của tôi</Link><Link to="/register">Trở thành thành viên</Link></div>
      <div className="footer-note"><Icon name="leaf" size={30} /><p>Chậm lại một chút.<br />Tận hưởng nhiều hơn.</p></div></div>
      <div className="footer-bottom"><span>© {new Date().getFullYear()} Hotel PMS Mini · Nhóm 03</span><span>Đồ án khách sạn · Hình ảnh minh họa ý tưởng</span></div></footer>
  </div>
}

type NavItem = { to: string; label: string; icon: IconName; admin?: boolean }
const workspaceNav: NavItem[] = [
  { to: '/staff', label: 'Tổng quan', icon: 'grid' }, { to: '/staff/bookings', label: 'Đặt phòng', icon: 'calendar' },
  { to: '/staff/calendar', label: 'Lịch phòng', icon: 'clock' }, { to: '/staff/rooms', label: 'Tình trạng phòng', icon: 'bed' },
  { to: '/admin', label: 'Quản trị', icon: 'shield', admin: true }, { to: '/admin/rooms', label: 'Danh mục phòng', icon: 'building', admin: true },
  { to: '/admin/members', label: 'Thành viên', icon: 'users', admin: true }, { to: '/admin/settings', label: 'Cài đặt', icon: 'settings', admin: true },
]

export function StaffLayout() {
  const { user } = useAuth()
  const [collapsed, setCollapsed] = useState(false)
  const [mobile, setMobile] = useState(false)
  const [searchOpen, setSearchOpen] = useState(false)
  const [query, setQuery] = useState('')
  const location = useLocation()
  const canManage = user?.roles.some((role) => role === 'STAFF' || role === 'ADMIN')
  const items = workspaceNav.filter((item) => canManage && (!item.admin || user?.roles.includes('ADMIN')))
  useEffect(() => { setMobile(false); setSearchOpen(false); setQuery('') }, [location.pathname])
  const nav = <nav className="sidebar-nav" aria-label="Quản lý khách sạn">{items.map((item) => <NavLink to={item.to} end key={item.to} title={collapsed ? item.label : undefined}><Icon name={item.icon} /><span>{item.label}</span></NavLink>)}</nav>
  return <div className={`workspace-shell ${collapsed ? 'workspace-shell--collapsed' : ''}`}><a href="#main-content" className="skip-link">Đến nội dung chính</a>
    <aside className="sidebar"><Brand workspace /><div className="property-label"><span className="property-tile"><Icon name="building" /></span><div><strong>Hotel PMS Mini</strong><small>Quản lý khách sạn</small></div></div><p className="nav-caption">KHÔNG GIAN LÀM VIỆC</p>{nav}
      <div className="sidebar-bottom"><Link to="/" className="sidebar-return"><Icon name="arrow" size={18} /><span>Về website khách sạn</span></Link><button className="collapse-button" onClick={() => setCollapsed(!collapsed)} aria-label={collapsed ? 'Mở rộng thanh điều hướng' : 'Thu gọn thanh điều hướng'} aria-expanded={!collapsed}><Icon name="panel" /><span>Thu gọn</span></button></div></aside>
    <div className="workspace"><header className="workspace-header"><div className="breadcrumb"><button className="icon-button mobile-only" aria-label="Mở menu quản lý" onClick={() => setMobile(true)}><Icon name="menu" /></button><Link to="/staff" className="breadcrumb-root">Không gian quản lý</Link><span className="breadcrumb-slash">/</span><span>{titles[location.pathname] ?? 'Trang'}</span></div>
      <div className="header-actions"><button className="workspace-search" onClick={() => setSearchOpen(true)} aria-label="Tìm công cụ"><Icon name="search" size={17} /><span>Tìm công cụ…</span></button><AccountMenu /></div></header>
      <main id="main-content" className="main-content" tabIndex={-1}><Outlet /></main><footer className="workspace-footer"><span>HOTEL PMS MINI</span><span>Property workspace · Nhóm 03</span></footer></div>
    <Dialog open={mobile} title="Không gian quản lý" onClose={() => setMobile(false)}><div className="mobile-workspace-nav">{nav}<Link className="text-link" to="/">Về website khách sạn <Icon name="arrow" size={16} /></Link></div></Dialog>
    <Dialog open={searchOpen} title="Tìm công cụ" onClose={() => setSearchOpen(false)}><div className="field"><label htmlFor="tool-search">Tên trang hoặc công cụ</label><input id="tool-search" type="search" value={query} onChange={(event) => setQuery(event.target.value)} autoFocus placeholder="Ví dụ: đặt phòng, lịch phòng…" /></div>
      <nav className="search-results" aria-label="Kết quả tìm công cụ">{items.filter((item) => item.label.toLocaleLowerCase('vi').includes(query.toLocaleLowerCase('vi'))).map((item) => <Link key={item.to} to={item.to} onClick={() => setSearchOpen(false)}><Icon name={item.icon} />{item.label}<Icon name="arrow" size={16} /></Link>)}</nav>
      {!items.some((item) => item.label.toLocaleLowerCase('vi').includes(query.toLocaleLowerCase('vi'))) && <p className="muted">Không tìm thấy công cụ phù hợp.</p>}</Dialog>
  </div>
}
