import assert from 'node:assert/strict'
import { after, test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { mkdtemp, rm } from 'node:fs/promises'
import { resolve, sep } from 'node:path'
import { pathToFileURL } from 'node:url'
import { build } from 'vite'

const modulesDirectory = resolve('node_modules')
const output = await mkdtemp(resolve(modulesDirectory, '.m5-01-routes-'))
after(async () => {
  if (!resolve(output).startsWith(modulesDirectory + sep)) throw new Error('Unexpected test directory')
  await rm(output, { recursive: true, force: true })
})
// Inject context only into this test bundle. Production provider and HTTP are unchanged.
await build({
  configFile: false, logLevel: 'silent',
  plugins: [{ name: 'test-auth-context', enforce: 'pre', transform(_code, id) {
    if (!id.replaceAll('\\', '/').endsWith('/src/features/auth/AuthProvider.tsx')) return
    return `let state; export const setTestAuth = value => { state = value };
      export const useAuth = () => state; export const AuthProvider = ({ children }) => children;`
  } }],
  build: { ssr: 'tests/routes.entry.tsx', outDir: output,
    rollupOptions: { external: ['react', 'react-dom', 'react-router-dom', 'react/jsx-runtime'], output: { entryFileNames: 'entry.mjs' } },
  },
})
const { App, auth } = await import(pathToFileURL(resolve(output, 'entry.mjs')).href)
function render(path, roles = null, status = roles ? 'authenticated' : 'anonymous') {
  auth.setTestAuth({ user: roles ? { id: 1, fullName: 'Test User', email: 'test@example.test', roles } : null,
    status, sessionExpired: false, sessionMessage: '', logout() {}, retry() {} })
  return renderToStaticMarkup(createElement(MemoryRouter, { initialEntries: [path] }, createElement(App)))
}
test('guest has exact menu labels, login/register links and public home/search', () => {
  const home = render('/')
  for (const label of ['Hotel PMS Mini', 'Trang chủ', 'Tìm phòng', 'Đăng nhập', 'Đăng ký']) assert.ok(home.includes(label))
  const booking = render('/booking')
  assert.ok(booking.includes('Bạn chưa chọn hạng phòng.') && booking.includes('href="/rooms"'))
  assert.ok(!booking.includes('aria-label="Tiến trình đặt phòng"'))
  const selected = render('/booking?roomTypeId=2&arrival=2090-04-01&departure=2090-04-03&adults=2')
  assert.ok(selected.includes('Xem lại đặt phòng') && selected.includes('aria-label="Tiến trình đặt phòng"'))
  const rooms = render('/rooms')
  assert.ok(rooms.includes('Kết quả tìm phòng') && rooms.includes('Ngày đến') && rooms.includes('Ngày đi'))
  assert.ok(!home.includes('href="/staff"'))
})
test('authenticated header shows name/logout and hides login/register menu', () => {
  const html = render('/account', ['CUSTOMER'])
  assert.ok(html.includes('Test User') && html.includes('Đăng xuất'))
  assert.ok(!html.includes('href="/login"') && !html.includes('href="/register"'))
})
test('staff and multi-role users see authorized calendar loading and lookup controls', () => {
  for (const roles of [['STAFF'], ['ADMIN'], ['CUSTOMER', 'STAFF'], ['CUSTOMER', 'ADMIN']]) {
    const pages = [
      ['/staff/calendar', 'Lịch phòng', 'Đang tải lịch phòng'],
      ['/staff/bookings', 'Tra cứu booking', 'Nhập mã booking hoặc số điện thoại để bắt đầu.'],
    ]
    for (const [path, heading, unavailable] of pages) {
      const html = render(path, roles)
      assert.ok(html.includes('Điều hướng nhân viên'))
      assert.ok(html.includes('href="/staff/calendar"') && html.includes('href="/staff/bookings"'))
      assert.ok(html.includes(heading) && html.includes(unavailable))
    }
  }
})
test('anonymous/CUSTOMER cannot render staff content; cached roles do not bypass loading', () => {
  for (const roles of [null, ['CUSTOMER']]) {
    assert.ok(!render('/staff/calendar', roles).includes('Điều hướng nhân viên'))
    assert.ok(!render('/staff/bookings', roles).includes('Nhập mã booking hoặc số điện thoại để bắt đầu.'))
    assert.ok(!render('/staff/calendar', roles).includes('Lịch phòng chưa có dữ liệu xác thực'))
  }
  assert.ok(!render('/staff/calendar', ['ADMIN'], 'loading').includes('Điều hướng nhân viên'))
  assert.ok(render('/staff/calendar', ['ADMIN'], 'loading').includes('Đang xác thực'))
  assert.ok(render('/staff/calendar', null, 'unavailable').includes('Chưa thể xác thực tài khoản'))
})
test('dedicated 403 and unknown route 404 have a way back home', () => {
  const forbidden = render('/403', ['CUSTOMER'])
  assert.ok(forbidden.includes('403') && forbidden.includes('Không đủ quyền') && forbidden.includes('Về trang chủ'))
  const missing = render('/does-not-exist')
  assert.ok(missing.includes('404') && missing.includes('Không tìm thấy trang') && missing.includes('Về trang chủ'))
})
test('support launcher is public, inbox is Staff/Admin and FAQ editor is Admin only', () => {
  assert.ok(render('/').includes('aria-controls="support-panel"'))
  assert.ok(!render('/', ['CUSTOMER'], 'loading').includes('aria-controls="support-panel"'))
  assert.ok(!render('/staff/support', ['CUSTOMER']).includes('Nhận yêu cầu, trả lời khách'))
  assert.ok(render('/staff/support', ['STAFF']).includes('Nhận yêu cầu, trả lời khách'))
  assert.ok(!render('/admin/support/faq', ['STAFF']).includes('Quản lý câu trả lời công khai'))
  assert.ok(render('/admin/support/faq', ['ADMIN']).includes('Quản lý câu trả lời công khai'))
})
test('registration includes confirmation, no role selector and accessible mobile toggle', () => {
  const html = render('/register')
  assert.ok(html.includes('Nhập lại mật khẩu') && html.includes('name="confirmPassword"'))
  assert.ok(!html.includes('<select') && !html.includes('name="roles"'))
  assert.ok(html.includes('aria-controls="navigation-sidebar"') && html.includes('aria-expanded="false"'))
})
