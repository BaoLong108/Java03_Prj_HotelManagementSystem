import assert from 'node:assert/strict'
import { after, test } from 'node:test'
import { mkdtemp, rm } from 'node:fs/promises'
import { resolve, sep } from 'node:path'
import { pathToFileURL, URL } from 'node:url'
import { JSDOM } from 'jsdom'
import { build } from 'vite'

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' })
const { document } = dom.window
for (const [name, value] of Object.entries({ window: dom.window, document, navigator: dom.window.navigator,
  FormData: dom.window.FormData, IS_REACT_ACT_ENVIRONMENT: true })) {
  Object.defineProperty(globalThis, name, { configurable: true, writable: true, value })
}
dom.window.HTMLDialogElement.prototype.showModal = function () { this.open = true }
dom.window.HTMLDialogElement.prototype.close = function () { this.open = false }
const { act, createElement: h } = await import('react')
const { createRoot } = await import('react-dom/client')
const { MemoryRouter, Route, Routes } = await import('react-router-dom')
const modules = resolve('node_modules')
const outDir = await mkdtemp(resolve(modules, '.crm-'))
await build({ configFile: false, logLevel: 'silent', build: { ssr: 'tests/crm.entry.tsx', outDir, rollupOptions: {
  external: ['react', 'react-dom', 'react-router-dom', 'react/jsx-runtime'], output: { entryFileNames: 'entry.mjs' },
} } })
const { AdminMembersPage, CustomerProfilePage } = await import(pathToFileURL(resolve(outDir, 'entry.mjs')).href)
const originalFetch = globalThis.fetch
after(async () => {
  if (!resolve(outDir).startsWith(modules + sep)) throw Error('Unexpected test directory')
  await rm(outDir, { recursive: true, force: true })
  dom.window.close()
})

const date = '2026-11-13T10:00:00Z'
const path = '/api/v1/admin/customers'
const customer = { id: 7, fullName: 'Demo Customer', email: 'demo@test.invalid', phone: null, active: true,
  registeredAt: date, tier: 'STANDARD', points: 30, eligibleSpending: 800000, bookingCount: 1 }
const profile = { customer, membership: { tierCode: 'STANDARD', eligibleSpendTotal: 800000, pointsBalance: 30,
  discountRate: 0, nextTier: 'VIP', nextTierSpend: 10000000, remainingSpend: 9200000 },
  completedBookings: 1, verifiedPayments: 1, pointsEarned: 80, pointsRedeemed: 50 }
const page = (items, index = 0, total = items.length, totalPages = items.length ? 1 : 0) => ({
  items, page: index, size: 10, totalElements: total, totalPages,
})
const booking = { bookingCode: 'BK-CRM-TEST', roomType: 'Deluxe', startAt: date, endAt: date, guests: 2, createdAt: date,
  status: 'CONFIRMED', paymentStatus: 'SUCCEEDED', baseAmount: 400000, membershipDiscount: 0, voucherDiscount: 400000, payableAmount: 0 }
const attempt = { id: 5, bookingCode: booking.bookingCode, reference: '#5', provider: 'ZERO_PAYABLE', method: 'VOUCHER',
  amount: 0, status: 'SUCCEEDED', initiatedAt: date, settledAt: date }

async function mount(t, route, reply) {
  const calls = []
  globalThis.fetch = async (url) => {
    const request = new URL(url, 'http://localhost')
    calls.push(request)
    const data = await reply(request)
    return data instanceof Response ? data : Response.json(data)
  }
  const view = document.createElement('div')
  document.body.append(view)
  const root = createRoot(view)
  t.after(async () => { await act(async () => root.unmount()); view.remove(); globalThis.fetch = originalFetch })
  await act(async () => root.render(h(MemoryRouter, { initialEntries: [route] }, h(Routes, null,
    h(Route, { path: '/admin/members', element: h(AdminMembersPage) }),
    h(Route, { path: '/admin/members/:customerId', element: h(CustomerProfilePage) })))))
  return { view, calls }
}
const button = (view, text) => [...view.querySelectorAll('button')].find(item => item.textContent.trim() === text)
async function click(element) {
  assert.ok(element)
  await act(async () => element.click())
}

test('directory searches and filters on the server, pages and opens a profile with preserved back link', async t => {
  const { view, calls } = await mount(t, '/admin/members', url => {
    if (url.pathname.endsWith('/7')) return profile
    return { statistics: { total: 12, standard: 10, vip: 1, diamond: 1 },
      customers: page([customer], Number(url.searchParams.get('page') ?? 0), 12, 2) }
  })
  assert.match(view.textContent, /Tổng khách hàng12/)
  assert.match(view.querySelector('tbody').textContent, /Demo Customer.*demo@test.invalid.*800.000/s)
  await click(button(view, 'Chính sách thành viên'))
  assert.match(view.textContent, /Diamond/)
  assert.equal(view.querySelector('table'), null)
  await click(button(view, 'Danh sách khách hàng'))
  view.querySelector('input[name=search]').value = ' Demo '
  view.querySelector('select[name=tier]').value = 'STANDARD'
  await act(async () => view.querySelector('form').dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true })))
  assert.equal(calls.at(-1).searchParams.get('search'), 'Demo')
  assert.equal(calls.at(-1).searchParams.get('tier'), 'STANDARD')
  await click(button(view, 'Trang sau'))
  assert.equal(calls.at(-1).searchParams.get('page'), '1')
  await click(view.querySelector('a[href="/admin/members/7"]'))
  assert.match(view.textContent, /Khách hàng #7.*9.200.000/s)
  assert.equal(view.querySelector('progress').value, 800000)
  assert.match(view.querySelector('a').getAttribute('href'), /search=Demo.*tier=STANDARD.*page=1/)
})

test('profile loads histories by tab and displays read-only booking and transaction details', async t => {
  const { view, calls } = await mount(t, '/admin/members/7', url => {
    const suffix = url.pathname.slice((path + '/7').length)
    return ({
      '': profile, '/bookings': page([booking]), '/transactions': page([attempt]),
      '/transactions/5': { transaction: attempt, baseAmount: 400000, membershipDiscount: 0, voucherDiscount: 400000, settledAt: date },
      '/loyalty': page([{ id: 2, type: 'EARNED', points: 80, at: date, bookingCode: booking.bookingCode }]),
      '/vouchers': page([{ id: 2, code: '••••AB', points: 50, discount: 400000, createdAt: date, status: 'USED' }]),
      '/check-in-requests': page([{ id: 2, bookingCode: booking.bookingCode, createdAt: date, estimatedArrival: date, early: true, status: 'REJECTED',
        reviewer: 'Demo Staff', reviewedAt: date, rejectionReason: 'Chưa thể nhận phòng sớm' }]),
    })[suffix]
  })
  assert.deepEqual(calls.map(url => url.pathname), [path + '/7'])
  await click(button(view, 'Đặt phòng'))
  assert.match(view.querySelector('tbody').textContent, /Deluxe/)
  view.querySelector('select[name=status]').value = 'CONFIRMED'
  await act(async () => view.querySelector('form').dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true })))
  assert.equal(calls.at(-1).searchParams.get('status'), 'CONFIRMED')
  await click(button(view, booking.bookingCode))
  assert.match(view.querySelector('dialog[open]').textContent, /Tổng phải trả/)
  await click(view.querySelector('dialog[open] button[aria-label="Đóng hộp thoại"]'))
  await click(button(view, 'Giao dịch'))
  assert.match(view.querySelector('tbody').textContent, /Voucher.*Thanh toán thành công/s)
  await click(button(view, '#5'))
  assert.match(view.querySelector('dialog[open]').textContent, /Số tiền giao dịch0/)
  await click(view.querySelector('dialog[open] button[aria-label="Đóng hộp thoại"]'))
  await click(button(view, 'Hạng & điểm'))
  assert.match(view.querySelector('tbody').textContent, /Tích điểm\+80/)
  await click(button(view, 'Voucher'))
  assert.match(view.querySelector('tbody').textContent, /••••AB.*Đã dùng/s)
  await click(button(view, 'Yêu cầu nhận phòng'))
  assert.match(view.querySelector('tbody').textContent, /Từ chối.*Demo Staff.*Chưa thể nhận phòng sớm/s)
})

test('directory distinguishes loading, errors and an empty result after retry', async t => {
  let complete, attempts = 0
  const { view } = await mount(t, '/admin/members', () => {
    if (++attempts === 1) return new Promise(resolve => { complete = resolve })
    return { statistics: { total: 0, standard: 0, vip: 0, diamond: 0 }, customers: page([]) }
  })
  assert.ok(view.querySelector('.skeleton'))
  await act(async () => complete(Response.json({ message: 'Không thể tải' }, { status: 500 })))
  assert.match(view.querySelector('[role=alert]').textContent, /Không thể tải/)
  await click(button(view, 'Thử lại'))
  assert.match(view.textContent, /Không tìm thấy khách hàng phù hợp/)
})
