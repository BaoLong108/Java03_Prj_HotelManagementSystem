import assert from 'node:assert/strict'
import { after, test } from 'node:test'
import { mkdtemp, rm } from 'node:fs/promises'
import { resolve, sep } from 'node:path'
import { pathToFileURL } from 'node:url'
import { JSDOM } from 'jsdom'
import { build } from 'vite'

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' })
const document = dom.window.document
for (const [name, value] of Object.entries({ window: dom.window, document, navigator: dom.window.navigator, IS_REACT_ACT_ENVIRONMENT: true })) {
  Object.defineProperty(globalThis, name, { configurable: true, writable: true, value })
}
dom.window.HTMLDialogElement.prototype.showModal = function () { this.open = true }
dom.window.HTMLDialogElement.prototype.close = function () { this.open = false }
dom.window.scrollTo = () => {}
const { act, createElement: h } = await import('react')
const { createRoot } = await import('react-dom/client')
const { MemoryRouter, Routes, Route } = await import('react-router-dom')
const modules = resolve('node_modules')
const output = await mkdtemp(resolve(modules, '.arrivals-'))
await build({ configFile: false, logLevel: 'silent', plugins: [{
  name: 'arrivals-test-auth', enforce: 'pre', transform(_source, id) {
    if (!id.replaceAll('\\', '/').endsWith('/src/features/auth/AuthProvider.tsx')) return
    return 'let state; export const setTestAuth = value => { state = value }; export const useAuth = () => state; export const AuthProvider = ({children}) => children;'
  },
}], build: { ssr: 'tests/arrivals.entry.tsx', outDir: output, rollupOptions: {
  external: ['react', 'react-dom', 'react-router-dom', 'react/jsx-runtime'], output: { entryFileNames: 'entry.mjs' },
} } })
const { App, AnalyticsPage, analyticsApi, BookingTimelinePage, StaffCheckInPage, checkInApi, ToastProvider, auth, RoomInventoryItem, adminRoomApi } =
  await import(pathToFileURL(resolve(output, 'entry.mjs')).href)
const apis = [analyticsApi, checkInApi, adminRoomApi]
const original = apis.map(api => ({ ...api }))
after(async () => {
  if (!resolve(output).startsWith(modules + sep)) throw new Error('Unexpected test directory')
  await rm(output, { recursive: true, force: true })
  dom.window.close()
})

const time = '2026-11-10T05:00:00Z'
const item = {
  id: 42, estimatedArrivalAt: time, earlyCheckInRequested: false, arrivalNote: 'Đến cùng gia đình',
  status: 'PENDING', reviewedAt: null, rejectionReason: null, createdAt: time, updatedAt: time,
}
const queueItem = { bookingCode: 'BK-QA', roomTypeName: 'Deluxe', bookingStatus: 'CONFIRMED', startAt: time, endAt: time, guestCount: 2, request: item }
const baseTotals = {
  reservations: 3, pendingPayments: 1, confirmed: 1, completed: 0, cancelled: 1, expired: 0, cancellations: 1,
  demoSettlements: 1, demoPaymentVolume: 1850000, confirmedBookingValue: 1850000, membershipDiscount: 250000,
  voucherDiscount: 400000, averageDemoBookingValue: 1850000, cancellationRate: 33.33,
}
const report = (filter, empty = false) => ({
  ...filter, totals: empty ? Object.fromEntries(Object.keys(baseTotals).map(key => [key, key === 'cancellationRate' ? null : 0])) : baseTotals,
  inventory: { bookedNights: 1, estimatedAvailableNights: 3, bookedUtilization: empty ? null : 33.33 }, comparison: null,
  days: [{ date: filter.from, reservations: empty ? 0 : 3, cancellations: empty ? 0 : 1, demoPaymentVolume: empty ? 0 : 1850000 }],
  roomTypes: [], membershipDistribution: { VIP: empty ? 0 : 1 },
})
const timeline = (requests = [], status = 'CONFIRMED') => ({
  booking: { bookingCode: 'BK-QA', status, startAt: time, endAt: '2026-11-12T03:00:00Z', guestCount: 2,
    baseAmount: 2500000, discountAmount: 250000, voucherDiscountAmount: 400000, payableAmount: 1850000, voucherCode: 'ABC123' },
  room: { name: 'Deluxe', coverImageUrl: '/api/v1/rooms/1/images/1' }, nights: 2,
  payment: status === 'CONFIRMED' ? { provider: 'DEMO', status: 'SUCCEEDED', amount: 1850000 } : null,
  confirmationPdfAvailable: status === 'CONFIRMED', requests,
  eligibility: { allowed: status === 'CONFIRMED' && !requests.length, opensAt: time, closesAt: time, reason: 'Yêu cầu không khả dụng.' },
  milestones: [{ stage: 'CREATED', at: time }, { stage: status === 'CONFIRMED' ? 'PAYMENT_SUCCEEDED' : status, at: time }],
})

async function mount(t, Component, path, role = 'ADMIN') {
  auth.setTestAuth({ status: 'authenticated', user: { id: 1, fullName: 'QA', email: 'qa@test.invalid', roles: [role] } })
  const container = document.createElement('div')
  document.body.append(container)
  const root = createRoot(container)
  t.after(async () => {
    await act(async () => root.unmount())
    container.remove()
    apis.forEach((api, index) => Object.assign(api, original[index]))
  })
  const page = Component === BookingTimelinePage
    ? h(Routes, null, h(Route, { path: '/my-bookings/:bookingCode', element: h(Component) })) : h(Component)
  await act(async () => root.render(h(MemoryRouter, { initialEntries: [path] }, h(ToastProvider, null, page))))
  return container
}

const button = (view, label) => [...view.querySelectorAll('button')].find(item => item.textContent.trim() === label)
const field = (view, label) => [...view.querySelectorAll('label')].find(item => item.textContent.startsWith(label)).control
const click = async element => { assert.ok(element); await act(async () => element.click()) }
async function change(element, value) {
  await act(async () => {
    Object.getOwnPropertyDescriptor(Object.getPrototypeOf(element), 'value').set.call(element, value)
    element.dispatchEvent(new dom.window.Event(element.tagName === 'SELECT' ? 'change' : 'input', { bubbles: true }))
  })
}

test('analytics formats authoritative values and reloads all charts on date and room filters', async t => {
  const requests = []
  analyticsApi.roomTypes = async () => [{ id: 2, name: 'Deluxe' }]
  analyticsApi.report = async filter => { requests.push(filter); return report(filter, !!filter.roomTypeId) }
  const view = await mount(t, AnalyticsPage, '/admin/analytics')
  assert.match(view.textContent, /1\.850\.000\s*₫/)
  assert.match(view.textContent, /33,33%/)
  assert.match(view.textContent, /ADR.*Không có dữ liệu/)
  assert.equal(view.querySelectorAll('.revenue-chart').length, 6)
  await change(field(view, 'Kỳ báo cáo'), 'today')
  assert.equal(requests.at(-1).from, requests.at(-1).to)
  await change(field(view, 'Kỳ báo cáo'), 'custom')
  await change(field(view, 'Từ ngày'), '2025-12-31')
  await change(field(view, 'Đến ngày'), '2026-01-02')
  await change(field(view, 'Hạng phòng'), '2')
  await click(button(view, 'Áp dụng'))
  assert.deepEqual(requests.at(-1), { from: '2025-12-31', to: '2026-01-02', roomTypeId: 2 })
  assert.match(view.textContent, /Chưa có dữ liệu trong kỳ/)
})

test('analytics shows loading and retries a failed report', async t => {
  let reject
  analyticsApi.report = filter => reject ? Promise.resolve(report(filter)) : new Promise((_resolve, fail) => { reject = fail })
  analyticsApi.roomTypes = async () => []
  const view = await mount(t, AnalyticsPage, '/admin/analytics')
  assert.ok(view.querySelector('.skeleton'))
  await act(async () => reject(new Error('Lỗi báo cáo QA')))
  assert.match(view.textContent, /Lỗi báo cáo QA/)
  await click(button(view, 'Tải lại báo cáo'))
  assert.ok(view.querySelector('.revenue-chart'))
})

test('customer timeline shows booking snapshot and submits hotel-local time, then reloads request status', async t => {
  let sent
  checkInApi.timeline = async () => timeline(sent ? [item] : [])
  checkInApi.submit = async (code, request) => { sent = { code, request }; return item }
  const view = await mount(t, BookingTimelinePage, '/my-bookings/BK-QA', 'CUSTOMER')
  assert.match(view.textContent, /Đã thanh toán demo/)
  assert.match(view.textContent, /1\.850\.000\s*₫/)
  assert.match(view.querySelector('.journey-cover').getAttribute('alt'), /Deluxe/)
  assert.match(view.querySelector('.journey-timeline').textContent, /Đã tạo đặt phòng.*Thanh toán thành công/)
  assert.doesNotMatch(view.querySelector('.journey-timeline').textContent, /Đang lưu trú|Đã nhận phòng/)
  await change(field(view, 'Giờ đến dự kiến'), '2026-11-10T14:00')
  await change(field(view, 'Ghi chú giờ đến'), ' Đến cùng gia đình ')
  await click(button(view, 'Gửi yêu cầu'))
  assert.deepEqual(sent, { code: 'BK-QA', request: { estimatedArrival: '2026-11-10T14:00', earlyCheckInRequested: false, arrivalNote: 'Đến cùng gia đình' } })
  assert.match(view.textContent, /Chờ xử lý/)
  assert.equal(button(view, 'Gửi yêu cầu'), undefined)
})

test('timeline displays expired state and omits check-in form and PDF for unpaid booking', async t => {
  checkInApi.timeline = async () => timeline([], 'EXPIRED')
  const view = await mount(t, BookingTimelinePage, '/my-bookings/BK-QA', 'CUSTOMER')
  assert.match(view.textContent, /Đã hết hạn giữ phòng/)
  assert.equal(button(view, 'Tải PDF xác nhận'), undefined)
  assert.equal(button(view, 'Gửi yêu cầu'), undefined)
})

test('staff searches, opens a drawer and confirms rejection with an audit reason', async t => {
  const requests = []
  let rejected
  checkInApi.list = async (status, code, page) => { requests.push({ status, code, page }); return { items: rejected ? [] : [queueItem], hasNext: false } }
  checkInApi.detail = async () => queueItem
  checkInApi.review = async (id, decision, rejectionReason) => {
    rejected = { id, decision, rejectionReason }
    return { ...queueItem, request: { ...item, status: 'REJECTED', rejectionReason, reviewedAt: time, reviewedBy: 1 } }
  }
  const view = await mount(t, StaffCheckInPage, '/staff/check-in-requests', 'STAFF')
  await change(field(view, 'Tìm theo mã đặt phòng'), 'BK-QA')
  await click(button(view, 'Tìm yêu cầu'))
  assert.deepEqual(requests.at(-1), { status: 'PENDING', code: 'BK-QA', page: 0 })
  await click(button(view, 'Xem yêu cầu'))
  const drawer = view.querySelector('dialog[open]')
  await click(button(drawer, 'Từ chối'))
  await change(field(drawer, 'Lý do từ chối'), 'Giờ đến chưa phù hợp')
  await click(button(drawer, 'Xác nhận từ chối'))
  assert.deepEqual(rejected, { id: 42, decision: 'REJECTED', rejectionReason: 'Giờ đến chưa phù hợp' })
  assert.match(drawer.textContent, /Đã từ chối.*Giờ đến chưa phù hợp/)
  assert.match(view.textContent, /Không có yêu cầu phù hợp/)
})

test('staff navigation shows check-in but denies admin-only analytics', async t => {
  const view = await mount(t, App, '/admin/analytics', 'STAFF')
  assert.doesNotMatch(view.textContent, /Xuất CSV/)
  assert.match(view.textContent, /Không đủ quyền|không có quyền/i)
})

test('admin edits a room and confirms hiding through the existing API', async t => {
  const calls = []
  let changed = 0
  const room = { id: 31, roomNumber: '301', roomTypeId: 2, roomTypeName: 'Deluxe', active: true, floorNumber: 3 }
  adminRoomApi.update = async (...args) => { calls.push(['update', ...args]); return room }
  adminRoomApi.hide = async id => { calls.push(['hide', id]) }
  const view = await mount(t, () => h(RoomInventoryItem, { room, onChange: () => changed++ }), '/admin/rooms')
  await click(button(view, 'Sửa phòng 301'))
  await change(field(view.querySelector('dialog[open]'), 'Số phòng'), '301-A')
  await click(button(view, 'Lưu thông tin phòng'))
  assert.deepEqual(calls[0], ['update', 31, '301-A', 3])
  await click(button(view, 'Ẩn phòng 301'))
  assert.equal(calls.length, 1)
  await click(button(view, 'Xác nhận ẩn phòng'))
  assert.deepEqual(calls[1], ['hide', 31])
  assert.equal(changed, 2)
})

test('a server conflict when hiding a booked room remains visible for the admin', async t => {
  const room = { id: 31, roomNumber: '301', roomTypeId: 2, roomTypeName: 'Deluxe', active: true, floorNumber: 3 }
  adminRoomApi.hide = async () => { throw new Error('Phòng đang có booking') }
  const view = await mount(t, () => h(RoomInventoryItem, { room, onChange: () => assert.fail('Should not report success') }), '/admin/rooms')
  await click(button(view, 'Ẩn phòng 301'))
  await click(button(view, 'Xác nhận ẩn phòng'))
  assert.match(view.querySelector('dialog[open]').textContent, /Phòng đang có booking/)
})
