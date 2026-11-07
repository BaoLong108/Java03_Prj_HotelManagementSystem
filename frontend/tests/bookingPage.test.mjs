import assert from 'node:assert/strict'
import { after, test } from 'node:test'
import { mkdtemp, rm } from 'node:fs/promises'
import { resolve, sep } from 'node:path'
import { pathToFileURL } from 'node:url'
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
const { MemoryRouter } = await import('react-router-dom')
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
const { BookingPage, bookingApi, auth } = await import(pathToFileURL(resolve(output, 'entry.mjs')).href)
const originalApi = { ...bookingApi }
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
async function click(element) {
  assert.ok(element, 'Expected button to exist')
  await act(async () => element.click())
}
async function mount(t, overrides = {}) {
  Object.assign(bookingApi, originalApi, {
    search: async () => [room],
    quote: async selected => ({ roomType: selected, guestCount: 2, nights: 2, appliedTier: 'STANDARD',
      baseAmount: 2000000, discountRate: 0, discountAmount: 0, payableAmount: 2000000 }),
    create: async () => hold(), getByCode: async () => hold(), getMyBookings: async () => [],
    ...overrides,
  })
  auth.setTestAuth({ user: { id: 10, roles: ['CUSTOMER'] } })
  const container = dom.window.document.createElement('div')
  dom.window.document.body.append(container)
  const root = createRoot(container)
  t.after(async () => {
    await act(async () => root.unmount())
    container.remove()
    Object.assign(bookingApi, originalApi)
  })
  await act(async () => root.render(createElement(MemoryRouter, { initialEntries: ['/rooms?arrival=2026-11-10&departure=2026-11-12&adults=2'] }, createElement(BookingPage))))
  return container
}
async function changeGuestCount(view, value) {
  const input = view.querySelector('input[type="number"]')
  await act(async () => {
    Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype, 'value').set.call(input, String(value))
    input.dispatchEvent(new dom.window.Event('input', { bubbles: true }))
  })
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
  assert.match(view.textContent, /Đang tìm phòng trống/)
  assert.doesNotMatch(view.textContent, /Không có phòng phù hợp trong khoảng đã chọn/)
  assert.equal(button(view, 'Tiến hành giữ phòng').disabled, true)
  await act(async () => pending.resolve([]))
  assert.doesNotMatch(view.textContent, /Đang tìm phòng trống/)
  assert.match(view.textContent, /Không có phòng phù hợp trong khoảng đã chọn/)
})

test('availability errors are not empty results and retry starts a fresh loading state', async t => {
  const retry = deferred()
  let calls = 0
  const view = await mount(t, { search: () => ++calls === 1 ? Promise.reject(new Error('Availability unavailable')) : retry.promise })
  assert.match(view.textContent, /Availability unavailable/)
  assert.doesNotMatch(view.textContent, /Không có phòng phù hợp trong khoảng đã chọn/)
  await click(button(view, 'Thử lại tìm phòng'))
  assert.match(view.textContent, /Đang tìm phòng trống/)
  assert.doesNotMatch(view.textContent, /Availability unavailable/)
  await act(async () => retry.resolve([room]))
  assert.match(view.textContent, /Suite live/)
  assert.equal(button(view, 'Tiến hành giữ phòng').disabled, false)
})

test('an older availability response cannot replace a newer search, even if transport ignores abort', async t => {
  const first = deferred(), second = deferred()
  const signals = []
  const view = await mount(t, { search: (_body, signal) => { signals.push(signal); return signals.length === 1 ? first.promise : second.promise } })
  await changeGuestCount(view, 3)
  assert.equal(signals.length, 2)
  assert.equal(signals[0].aborted, true)
  await act(async () => second.resolve([{ ...room, name: 'Newest result' }]))
  await act(async () => first.resolve([{ ...room, name: 'Stale result' }]))
  assert.match(view.textContent, /Newest result/)
  assert.doesNotMatch(view.textContent, /Stale result/)
})

test('a new search hides stale rooms and disables holding while it is pending', async t => {
  const pending = deferred()
  let calls = 0
  const view = await mount(t, { search: () => ++calls === 1 ? Promise.resolve([room]) : pending.promise })
  assert.equal(button(view, 'Tiến hành giữ phòng').disabled, false)
  await changeGuestCount(view, 3)
  assert.match(view.textContent, /Đang tìm phòng trống/)
  assert.doesNotMatch(view.textContent, /Suite live/)
  assert.equal(button(view, 'Tiến hành giữ phòng').disabled, true)
  await act(async () => pending.resolve([]))
})

test('countdown uses the server deadline and zero never invents EXPIRED locally', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setInterval'], now: NOW })
  const view = await mount(t)
  await click(button(view, 'Tiến hành giữ phòng'))
  assert.match(view.querySelector('.hold-timer-digits').textContent, /01:00/)
  await act(async () => t.mock.timers.tick(61000))
  assert.match(view.querySelector('.hold-timer-digits').textContent, /00:00/)
  assert.match(view.textContent, /Chờ thanh toán/)
  assert.doesNotMatch(view.textContent, /Máy chủ xác nhận đơn đặt phòng đã hết hạn/)
  assert.equal(button(view, 'Thanh toán Demo ngay'), undefined)
  bookingApi.getByCode = async () => ({ ...hold(), status: 'EXPIRED' })
  await act(async () => t.mock.timers.tick(5000))
  assert.match(view.textContent, /Máy chủ xác nhận đơn đặt phòng đã hết hạn/)
})

test('returning to a tab reads server CONFIRMED without posting another payment', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setInterval'], now: NOW })
  let payments = 0
  const view = await mount(t, { confirm: async () => { payments++; return { ...hold(), status: 'CONFIRMED' } } })
  await click(button(view, 'Tiến hành giữ phòng'))
  bookingApi.getByCode = async () => ({ ...hold(), status: 'CONFIRMED' })
  await act(async () => dom.window.dispatchEvent(new dom.window.Event('focus')))
  assert.match(view.textContent, /Đặt phòng đã được xác nhận/)
  assert.equal(payments, 0)
})

test('status read failure preserves server state, disables payment and allows explicit retry', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setInterval'], now: NOW })
  const view = await mount(t, { getByCode: async () => { throw new Error('Status unavailable') } })
  await click(button(view, 'Tiến hành giữ phòng'))
  assert.match(view.textContent, /Status unavailable/)
  assert.match(view.textContent, /Chờ thanh toán/)
  assert.equal(button(view, 'Thanh toán Demo ngay').disabled, true)
  bookingApi.getByCode = async () => hold()
  await click(button(view, 'Kiểm tra lại trạng thái'))
  assert.doesNotMatch(view.textContent, /Status unavailable/)
  assert.equal(button(view, 'Thanh toán Demo ngay').disabled, false)
})

test('leaving a hold aborts its status read and ignores late confirmation', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setInterval'], now: NOW })
  const pending = deferred()
  let signal
  const view = await mount(t, { getByCode: (_code, current) => { signal = current; return pending.promise } })
  await click(button(view, 'Tiến hành giữ phòng'))
  await click(button(view, 'Chọn lại loại phòng'))
  assert.equal(signal.aborted, true)
  await act(async () => pending.resolve({ ...hold(), status: 'CONFIRMED' }))
  assert.doesNotMatch(view.textContent, /Đặt phòng đã được xác nhận/)
  assert.match(view.textContent, /Chọn loại phòng/)
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
  await click(button(view, 'Tiến hành giữ phòng'))
  assert.equal(button(view, 'Áp dụng mã').disabled, true)
  await enterVoucher(view, 'a1b2c3')
  await click(button(view, 'Áp dụng mã'))
  assert.deepEqual(submitted, { code: hold().bookingCode, voucher: 'A1B2C3' })
  assert.equal(button(view, 'Thanh toán Demo ngay').disabled, true)
  current = { ...hold(), voucherCode: 'A1B2C3', voucherDiscountAmount: 2000000, afterMembershipAmount: 2000000, payableAmount: 0 }
  await act(async () => pending.resolve(current))
  assert.match(button(view, 'Thanh toán Demo ngay').textContent, /0/)
  assert.equal(button(view, 'Thanh toán Demo ngay').disabled, false)
  assert.match(view.querySelector('.checkout-voucher').textContent, /A1B2C3/)
  await click(button(view, 'Gỡ mã'))
  assert.match(button(view, 'Thanh toán Demo ngay').textContent, /2\.000\.000/)
  assert.ok(view.querySelector('.checkout-voucher input'))
})

test('invalid voucher errors preserve the server price and allow retry', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setInterval'], now: NOW })
  const view = await mount(t, { applyVoucher: async () => { throw new Error('Mã đã sử dụng') } })
  await click(button(view, 'Tiến hành giữ phòng'))
  await enterVoucher(view, 'A1B2C3')
  await click(button(view, 'Áp dụng mã'))
  assert.match(view.querySelector('.checkout-voucher').textContent, /Mã đã sử dụng/)
  assert.match(button(view, 'Thanh toán Demo ngay').textContent, /2\.000\.000/)
  assert.equal(button(view, 'Áp dụng mã').disabled, false)
})

test('voucher form is disabled at the server hold deadline', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setInterval'], now: NOW })
  const view = await mount(t)
  await click(button(view, 'Tiến hành giữ phòng'))
  await enterVoucher(view, 'A1B2C3')
  assert.equal(button(view, 'Áp dụng mã').disabled, false)
  await act(async () => t.mock.timers.tick(61000))
  assert.equal(button(view, 'Áp dụng mã').disabled, true)
})

test('booking history distinguishes tier and voucher reductions alongside the final amount', async t => {
  const view = await mount(t, { getMyBookings: async () => [{ ...hold(), status: 'CONFIRMED',
    discountRate: 0.1, discountAmount: 200000, voucherCode: 'A1B2C3', voucherDiscountAmount: 400000,
    afterMembershipAmount: 1800000, payableAmount: 1400000 }] })
  await click(button(view, 'Đơn đặt phòng của tôi'))
  const row = view.querySelector('tbody tr').textContent
  assert.match(row, /1\.400\.000/)
  assert.match(row, /Ưu đãi hạng: −200\.000/)
  assert.match(row, /Mã A1B2C3: −400\.000/)
})
