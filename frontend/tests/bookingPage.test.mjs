import assert from 'node:assert/strict'
import { after, test } from 'node:test'
import { mkdtemp, rm } from 'node:fs/promises'
import { resolve, sep } from 'node:path'
import { pathToFileURL, URLSearchParams } from 'node:url'
import { JSDOM } from 'jsdom'
import { build } from 'vite'

// Exercise the real component/effects. Only HTTP results and authentication are controlled.
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/', pretendToBeVisual: true })
const globals = new Map()
for (const [name, value] of Object.entries({ window: dom.window, document: dom.window.document,
  navigator: dom.window.navigator, IS_REACT_ACT_ENVIRONMENT: true })) {
  globals.set(name, Object.getOwnPropertyDescriptor(globalThis, name))
  Object.defineProperty(globalThis, name, { configurable: true, writable: true, value })
}
const { act, createElement } = await import('react')
const { createRoot } = await import('react-dom/client')
const { MemoryRouter, Routes, Route, useNavigate } = await import('react-router-dom')
const modules = resolve('node_modules')
const output = await mkdtemp(resolve(modules, '.booking-page-'))
await build({
  configFile: false, logLevel: 'silent',
  plugins: [{ name: 'booking-test-auth', enforce: 'pre', transform(_code, id) {
    if (!id.replaceAll('\\', '/').endsWith('/src/features/auth/AuthProvider.tsx')) return
    return 'let state; export const setTestAuth = value => { state = value }; export const useAuth = () => state;'
  } }],
  build: { ssr: 'tests/bookingPage.entry.tsx', outDir: output,
    rollupOptions: { external: ['react', 'react-dom', 'react-router-dom', 'react/jsx-runtime'], output: { entryFileNames: 'entry.mjs' } } },
})
const { BookingPage, MyBookingsPage, bookingApi, roomApi, auth, paymentApi, paymentNavigation, PaymentReturnPage } = await import(pathToFileURL(resolve(output, 'entry.mjs')).href)
const originalApi = { ...bookingApi }
const originalRoomApi = { ...roomApi }
const originalPaymentApi = { ...paymentApi }
after(async () => {
  if (!resolve(output).startsWith(modules + sep)) throw new Error('Unexpected test directory')
  await rm(output, { recursive: true, force: true })
  dom.window.close()
  for (const [name, descriptor] of globals) {
    if (descriptor) Object.defineProperty(globalThis, name, descriptor)
    else delete globalThis[name]
  }
})

const NOW = Date.parse('2026-10-27T05:00:00Z')
const room = { id: 1, code: 'SUITE', name: 'Suite live', maxOccupancy: 4, basePricePerNight: 1000000 }
const hold = () => ({ id: 8, bookingCode: 'BK261027-TEST01', customerId: 10, roomTypeId: 1, guestCount: 2,
  startAt: '2026-11-10T05:00:00Z', endAt: '2026-11-12T03:00:00Z', createdAt: new Date(NOW).toISOString(),
  holdExpiresAt: new Date(NOW + 60000).toISOString(), status: 'PENDING_PAYMENT',
  baseAmount: 2000000, discountRate: 0, discountAmount: 0, payableAmount: 2000000 })
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function button(view, text) {
  return [...view.querySelectorAll('button')].find(item => item.textContent.includes(text))
}
function payable(view) {
  return [...view.querySelectorAll('dt')].find(item => item.textContent === 'Tổng tiền cần thanh toán').nextElementSibling.textContent
}
async function click(element) {
  assert.ok(element, 'Expected button to exist')
  await act(async () => element.click())
}
const defaultPath = '/booking?roomTypeId=1&arrival=2026-11-30&departure=2026-12-02&adults=2'
const futurePath = '/booking?roomTypeId=1&arrival=2090-11-30&departure=2090-12-02&adults=2'

function Harness() {
  const navigate = useNavigate()
  return createElement('div', null,
    createElement('button', { onClick: () => navigate('/booking?roomTypeId=2&arrival=2090-12-01&departure=2090-12-03&adults=3') }, 'Đổi URL QA'),
    createElement('button', { onClick: () => navigate(-1) }, 'Quay lại QA'),
    createElement(Routes, null,
      createElement(Route, { path: '/booking', element: createElement(BookingPage) }),
      createElement(Route, { path: '/my-bookings', element: createElement(MyBookingsPage) }),
      createElement(Route, { path: '/rooms', element: createElement('h1', null, 'Danh mục QA') })))
}

async function mount(t, overrides = {}, paymentOverrides = {}, path = Date.now() === NOW ? defaultPath : futurePath) {
  roomApi.get = async id => id === 1 ? room : { ...room, id, name: 'Deluxe live' }
  roomApi.list = async () => { throw new Error('Booking review must not load catalog') }
  Object.assign(paymentApi, originalPaymentApi, { availability: async () => ({ available: true }), ...paymentOverrides })
  Object.assign(bookingApi, originalApi, {
    search: async ({ roomTypeId }) => [{ ...room, id: roomTypeId }],
    getRoomTypes: async () => [room],
    quote: async selected => ({ roomType: selected, guestCount: 2, nights: 2, appliedTier: 'STANDARD',
      baseAmount: 2000000, discountRate: 0, discountAmount: 0, payableAmount: 2000000 }),
    create: async () => hold(), getByCode: async () => hold(), getMyBookings: async () => [],
    ...overrides,
  })
  auth.setTestAuth({ status: 'authenticated', user: { id: 10, roles: ['CUSTOMER'] } })
  const container = dom.window.document.createElement('div')
  dom.window.document.body.append(container)
  const root = createRoot(container)
  t.after(async () => {
    await act(async () => root.unmount())
    container.remove()
    Object.assign(bookingApi, originalApi)
    Object.assign(roomApi, originalRoomApi)
    Object.assign(paymentApi, originalPaymentApi)
  })
  await act(async () => root.render(createElement(MemoryRouter, { initialEntries: ['/rooms', path] }, createElement(Harness))))
  return container
}
async function enterVoucher(view, value) {
  const input = view.querySelector('.checkout-voucher input')
  await act(async () => {
    Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype, 'value').set.call(input, value)
    input.dispatchEvent(new dom.window.Event('input', { bubbles: true }))
  })
}

test('availability stays loading until an actual empty response arrives', async t => {
  const pending = deferred()
  const view = await mount(t, { search: () => pending.promise })
  assert.match(view.textContent, /Đang kiểm tra phòng trống/)
  assert.doesNotMatch(view.textContent, /Hạng phòng này đã hết phòng/)
  assert.equal(button(view, 'Tiếp tục giữ phòng').disabled, true)
  await act(async () => pending.resolve([]))
  assert.doesNotMatch(view.textContent, /Đang kiểm tra phòng trống/)
  assert.match(view.textContent, /Hạng phòng này đã hết phòng/)
})

test('missing VNPay configuration leaves a positive booking pending without a demo fallback', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setInterval'], now: NOW })
  const view = await mount(t, {}, { availability: async () => ({ available: false }) })
  await click(button(view, 'Tiếp tục giữ phòng'))
  assert.match(view.textContent, /VNPay hiện chưa sẵn sàng/)
  assert.match(view.textContent, /Chờ thanh toán/)
  assert.equal(button(view, 'Thanh toán qua VNPay Sandbox'), undefined)
  assert.equal(button(view, 'Thanh toán Demo'), undefined)
  assert.equal(button(view, 'Xác nhận đặt phòng 0₫'), undefined)
})

test('VNPay checkout uses the server URL and leaves confirmation to the backend', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setInterval'], now: NOW })
  let request, redirected
  const url = 'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?fixture=only'
  const originalRedirect = paymentNavigation.redirect
  paymentNavigation.redirect = destination => { redirected = destination }
  t.after(() => { paymentNavigation.redirect = originalRedirect })
  const view = await mount(t, {}, { checkout: async (...args) => { request = args; return { paymentUrl: url } } })
  await click(button(view, 'Tiếp tục giữ phòng'))
  await click(button(view, 'Thanh toán qua VNPay Sandbox'))
  assert.equal(request[0], hold().bookingCode)
  assert.match(request[1], /^web-vnpay-/)
  assert.equal(redirected, url)
  assert.doesNotMatch(view.textContent, /Đặt phòng đã được xác nhận/)
})

test('a zero balance uses only backend settlement and renders the resulting confirmed booking', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setInterval'], now: NOW })
  const zero = { ...hold(), payableAmount: 0 }
  let settled
  const view = await mount(t, {
    create: async () => zero, getByCode: async () => zero,
    settleZero: async code => { settled = code; return { ...zero, status: 'CONFIRMED' } },
  })
  await click(button(view, 'Tiếp tục giữ phòng'))
  await click(button(view, 'Xác nhận đặt phòng 0₫'))
  assert.equal(settled, zero.bookingCode)
  assert.match(view.textContent, /Đặt phòng đã được xác nhận/)
})

test('browser return ignores unsigned success and waits for the authoritative attempt', async t => {
  const view = dom.window.document.createElement('div')
  dom.window.document.body.append(view)
  const root = createRoot(view)
  const state = { bookingCode: hold().bookingCode, bookingStatus: 'PENDING_PAYMENT', paymentStatus: 'PENDING',
    outcome: 'PENDING', provider: 'VNPAY', payableAmount: 2000000, holdExpiresAt: new Date(Date.now() + 60000).toISOString() }
  let query, current = state
  paymentApi.status = async value => { query = value; return current }
  t.after(async () => {
    await act(async () => root.unmount()); view.remove(); Object.assign(paymentApi, originalPaymentApi)
  })
  await act(async () => root.render(createElement(MemoryRouter, {
    initialEntries: [`/payment-return?bookingCode=${state.bookingCode}&vnp_TxnRef=PMS1&vnp_ResponseCode=00`],
  }, createElement(PaymentReturnPage))))
  assert.equal(new URLSearchParams(query).get('reference'), 'PMS1')
  assert.match(view.textContent, /Đang chờ xác nhận thanh toán/)
  assert.doesNotMatch(view.textContent, /Đặt phòng đã được xác nhận/)
  current = { ...state, bookingStatus: 'CONFIRMED', paymentStatus: 'SUCCEEDED', outcome: 'SUCCEEDED' }
  await click(button(view, 'Cập nhật trạng thái'))
  assert.match(view.textContent, /Đặt phòng đã được xác nhận/)
})

test('availability errors are not empty results and retry starts a fresh loading state', async t => {
  const retry = deferred()
  let calls = 0
  const view = await mount(t, { search: () => ++calls === 1 ? Promise.reject(new Error('Availability unavailable')) : retry.promise })
  assert.match(view.textContent, /Availability unavailable/)
  assert.doesNotMatch(view.textContent, /Hạng phòng này đã hết phòng/)
  await click(button(view, 'Thử lại báo giá'))
  assert.match(view.textContent, /Đang kiểm tra phòng trống/)
  assert.doesNotMatch(view.textContent, /Availability unavailable/)
  await act(async () => retry.resolve([room]))
  assert.match(view.textContent, /Suite live/)
  assert.equal(button(view, 'Tiếp tục giữ phòng').disabled, false)
})

test('an older availability response cannot replace a newer search, even if transport ignores abort', async t => {
  const first = deferred(), second = deferred()
  const signals = []
  const view = await mount(t, { search: (_body, signal) => { signals.push(signal); return signals.length === 1 ? first.promise : second.promise } })
  await click(button(view, 'Đổi URL QA'))
  assert.equal(signals.length, 2)
  assert.equal(signals[0].aborted, true)
  await act(async () => second.resolve([{ ...room, id: 2 }]))
  await act(async () => first.resolve([room]))
  assert.match(view.textContent, /Deluxe live/)
  assert.doesNotMatch(view.textContent, /Suite live/)
})

test('a new search hides stale rooms and disables holding while it is pending', async t => {
  const pending = deferred()
  let calls = 0
  const view = await mount(t, { search: () => ++calls === 1 ? Promise.resolve([room]) : pending.promise })
  assert.equal(button(view, 'Tiếp tục giữ phòng').disabled, false)
  await click(button(view, 'Đổi URL QA'))
  assert.match(view.textContent, /Đang kiểm tra phòng trống/)
  assert.doesNotMatch(view.textContent, /Suite live/)
  assert.equal(button(view, 'Tiếp tục giữ phòng').disabled, true)
  await act(async () => pending.resolve([]))
})

test('countdown uses the server deadline and zero never invents EXPIRED locally', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setInterval'], now: NOW })
  const view = await mount(t)
  await click(button(view, 'Tiếp tục giữ phòng'))
  assert.match(view.querySelector('[role=timer]').textContent, /01:00/)
  await act(async () => t.mock.timers.tick(61000))
  assert.match(view.textContent, /Thời gian giữ phòng đã kết thúc/)
  assert.match(view.textContent, /Chờ thanh toán/)
  assert.doesNotMatch(view.textContent, /Máy chủ xác nhận đơn đặt phòng đã hết hạn/)
  assert.equal(button(view, 'Thanh toán qua VNPay Sandbox'), undefined)
  bookingApi.getByCode = async () => ({ ...hold(), status: 'EXPIRED' })
  await act(async () => t.mock.timers.tick(5000))
  assert.match(view.textContent, /Máy chủ xác nhận đơn đặt phòng đã hết hạn/)
})

test('returning to a tab reads server CONFIRMED without posting another payment', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setInterval'], now: NOW })
  let payments = 0
  const view = await mount(t, { settleZero: async () => { payments++; return { ...hold(), status: 'CONFIRMED' } } },
    { checkout: async () => { payments++; throw new Error('Unexpected checkout') } })
  await click(button(view, 'Tiếp tục giữ phòng'))
  bookingApi.getByCode = async () => ({ ...hold(), status: 'CONFIRMED' })
  await act(async () => dom.window.dispatchEvent(new dom.window.Event('focus')))
  assert.match(view.textContent, /Đặt phòng đã được xác nhận/)
  assert.equal(payments, 0)
})

test('status read failure preserves server state, disables payment and allows explicit retry', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setInterval'], now: NOW })
  const view = await mount(t)
  await click(button(view, 'Tiếp tục giữ phòng'))
  bookingApi.getByCode = async () => { throw new Error('Status unavailable') }
  await act(async () => dom.window.dispatchEvent(new dom.window.Event('focus')))
  assert.match(view.textContent, /Status unavailable/)
  assert.match(view.textContent, /Chờ thanh toán/)
  assert.equal(button(view, 'Thanh toán qua VNPay Sandbox').disabled, true)
  bookingApi.getByCode = async () => hold()
  await click(button(view, 'Kiểm tra lại trạng thái'))
  assert.doesNotMatch(view.textContent, /Status unavailable/)
  assert.equal(button(view, 'Thanh toán qua VNPay Sandbox').disabled, false)
})

test('leaving a hold aborts its status read and ignores late confirmation', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setInterval'], now: NOW })
  const pending = deferred()
  let signal
  const view = await mount(t, { getByCode: (_code, current) => { signal = current; return pending.promise } })
  await click(button(view, 'Tiếp tục giữ phòng'))
  await click(button(view, 'Quay lại QA'))
  assert.equal(signal.aborted, true)
  await act(async () => pending.resolve({ ...hold(), status: 'CONFIRMED' }))
  assert.doesNotMatch(view.textContent, /Đặt phòng đã được xác nhận/)
  assert.match(view.textContent, /Danh mục QA/)
})

test('checkout applies the server voucher total, disables payment during mutation, and supports removal', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setInterval'], now: NOW })
  const pending = deferred()
  let current = hold(), submitted
  const view = await mount(t, {
    getByCode: async () => current,
    applyVoucher: async (code, voucher) => { submitted = { code, voucher }; return pending.promise },
    removeVoucher: async () => { current = hold(); return current },
  })
  await click(button(view, 'Tiếp tục giữ phòng'))
  assert.equal(button(view, 'Áp dụng mã').disabled, true)
  await enterVoucher(view, 'a1b2c3')
  await click(button(view, 'Áp dụng mã'))
  assert.deepEqual(submitted, { code: hold().bookingCode, voucher: 'A1B2C3' })
  assert.equal(button(view, 'Thanh toán qua VNPay Sandbox').disabled, true)
  current = { ...hold(), voucherCode: 'A1B2C3', voucherDiscountAmount: 2000000, afterMembershipAmount: 2000000, payableAmount: 0 }
  await act(async () => pending.resolve(current))
  assert.equal(button(view, 'Xác nhận đặt phòng 0₫').disabled, false)
  assert.equal(button(view, 'Thanh toán qua VNPay Sandbox'), undefined)
  assert.match(view.querySelector('.checkout-voucher').textContent, /A1B2C3/)
  await click(button(view, 'Gỡ mã'))
  assert.equal(button(view, 'Thanh toán qua VNPay Sandbox').disabled, false)
  assert.match(payable(view), /2\.000\.000/)
  assert.ok(view.querySelector('.checkout-voucher input'))
})

test('invalid voucher errors preserve the server price and allow retry', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setInterval'], now: NOW })
  const view = await mount(t, { applyVoucher: async () => { throw new Error('Mã đã sử dụng') } })
  await click(button(view, 'Tiếp tục giữ phòng'))
  await enterVoucher(view, 'A1B2C3')
  await click(button(view, 'Áp dụng mã'))
  assert.match(view.querySelector('.checkout-voucher').textContent, /Mã đã sử dụng/)
  assert.match(payable(view), /2\.000\.000/)
  assert.equal(button(view, 'Áp dụng mã').disabled, false)
})

test('voucher form is disabled at the server hold deadline', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setInterval'], now: NOW })
  const view = await mount(t)
  await click(button(view, 'Tiếp tục giữ phòng'))
  await enterVoucher(view, 'A1B2C3')
  assert.equal(button(view, 'Áp dụng mã').disabled, false)
  await act(async () => t.mock.timers.tick(61000))
  assert.equal(button(view, 'Áp dụng mã'), undefined)
})

test('booking history distinguishes tier and voucher reductions alongside the final amount', async t => {
  const view = await mount(t, { getMyBookings: async () => [{ ...hold(), status: 'CONFIRMED',
    discountRate: 0.1, discountAmount: 200000, voucherCode: 'A1B2C3', voucherDiscountAmount: 400000,
    afterMembershipAmount: 1800000, payableAmount: 1400000 }] }, {}, '/my-bookings')
  const row = view.querySelector('tbody tr').textContent
  assert.match(row, /1\.400\.000/)
  assert.match(row, /Ưu đãi hạng: −200\.000/)
  assert.match(row, /Mã A1B2C3: −400\.000/)
})

test('missing and malformed RoomType links return to the catalog without room selection', async t => {
  const view = await mount(t, {}, {}, '/booking')
  assert.match(view.textContent, /Bạn chưa chọn hạng phòng/)
  assert.equal(view.querySelector('a').getAttribute('href'), '/rooms')
  assert.equal(view.querySelector('.booking-selected-room'), null)
})

test('valid selection retains dates and does not fall back to another available room', async t => {
  const view = await mount(t, { search: async () => [{ ...room, id: 2 }] })
  assert.equal(view.querySelectorAll('.booking-selected-room').length, 1)
  assert.match(view.textContent, /Suite live/)
  assert.match(view.textContent, /Hạng phòng này đã hết phòng/)
  assert.ok(button(view, 'Tiếp tục giữ phòng').disabled)
  const changeRoom = [...view.querySelectorAll('a')].find(link => link.textContent.includes('Đổi hạng phòng'))
  assert.match(changeRoom.getAttribute('href'), /\/rooms\?arrival=2090-11-30&departure=2090-12-02&adults=2/)
})

test('rapid reservation attempts share one request and use the selected RoomType and stay', async t => {
  const pending = deferred()
  const requests = []
  const view = await mount(t, { create: body => { requests.push(body); return pending.promise } })
  const reserve = button(view, 'Tiếp tục giữ phòng')
  await act(async () => { reserve.click(); reserve.click() })
  assert.deepEqual(requests, [{ roomTypeId: 1, guestCount: 2, startAt: '2090-11-30T12:00:00+07:00', endAt: '2090-12-02T10:00:00+07:00' }])
  await act(async () => pending.resolve(hold()))
  assert.match(view.textContent, /BK261027-TEST01/)
})

test('invalid RoomType ID cannot fetch a replacement', async t => {
  const view = await mount(t, { search: async () => { throw Error('unexpected search') } }, {}, '/booking?roomTypeId=-2')
  assert.match(view.textContent, /Hạng phòng không hợp lệ/)
  assert.equal(view.querySelector('.booking-selected-room'), null)
})

test('a late quote for a different RoomType cannot replace the current price', async t => {
  const first = deferred()
  let oldSignal
  const view = await mount(t, { quote: (selected, body, signal) => {
    assert.equal(selected.id, body.roomTypeId)
    if (selected.id === 1) {
      oldSignal = signal
      return first.promise
    }
    return Promise.resolve({ roomType: selected, nights: 2, guestCount: 3, baseAmount: 2400000,
      discountRate: 0, discountAmount: 0, payableAmount: 2400000 })
  } })
  await click(button(view, 'Đổi URL QA'))
  assert.equal(oldSignal.aborted, true)
  await act(async () => first.resolve({ roomType: room, baseAmount: 1000000, payableAmount: 1000000 }))
  assert.match(view.textContent, /Deluxe live/)
  assert.match(payable(view), /2\.400\.000/)
  assert.equal(view.querySelectorAll('.booking-selected-room').length, 1)
})
