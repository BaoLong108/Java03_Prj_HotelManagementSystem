import assert from 'node:assert/strict'
import { after, test } from 'node:test'
import { mkdtemp, rm } from 'node:fs/promises'
import { resolve, sep } from 'node:path'
import { pathToFileURL, URL } from 'node:url'
import { JSDOM } from 'jsdom'
import { build } from 'vite'

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' })
const { document } = dom.window
for (const [name, value] of Object.entries({ window: dom.window, document, navigator: dom.window.navigator, IS_REACT_ACT_ENVIRONMENT: true })) {
  Object.defineProperty(globalThis, name, { configurable: true, writable: true, value })
}
dom.window.HTMLDialogElement.prototype.showModal = function () { this.open = true }
dom.window.HTMLDialogElement.prototype.close = function () { this.open = false }
const { act, createElement } = await import('react')
const { createRoot } = await import('react-dom/client')
const { MemoryRouter } = await import('react-router-dom')
const modules = resolve('node_modules')
const outDir = await mkdtemp(resolve(modules, '.transactions-'))
await build({ configFile: false, logLevel: 'silent', build: {
  ssr: 'tests/transactions.entry.tsx', outDir, rollupOptions: {
    external: ['react', 'react-dom', 'react-router-dom', 'react/jsx-runtime'], output: { entryFileNames: 'entry.mjs' },
  },
} })
const { TransactionHistoryPage } = await import(pathToFileURL(resolve(outDir, 'entry.mjs')).href)
const originalFetch = globalThis.fetch
after(async () => {
  if (!resolve(outDir).startsWith(modules + sep)) throw new Error('Unexpected test directory')
  await rm(outDir, { recursive: true, force: true })
  dom.window.close()
})

const path = '/api/v1/customers/me/transactions'
const pending = {
  id: 1, bookingCode: 'BK-ONE', roomTypeName: 'Gallery Suite', coverImageUrl: '/api/v1/rooms/6/images/3',
  reference: 'PMS-ONE', provider: 'VNPAY', method: 'VNPAY', amount: 450000, currency: 'VND',
  initiatedAt: '2026-11-12T05:30:00Z', status: 'PROCESSING', bookingStatus: 'PENDING_PAYMENT', receiptAvailable: false,
}
const zero = {
  ...pending, id: 2, bookingCode: 'BK-ZERO', reference: '#2', provider: 'ZERO_PAYABLE', method: 'VOUCHER',
  amount: 0, status: 'SUCCEEDED', bookingStatus: 'CONFIRMED', receiptAvailable: true,
}
const detail = transaction => ({
  transaction, providerTransactionId: null, baseAmount: 500000, membershipDiscount: 50000,
  voucherDiscount: transaction.id === 2 ? 450000 : 0, payableAmount: transaction.amount,
  gatewayVerifiedAt: null, settledAt: transaction.id === 2 ? '2026-11-12T05:31:00Z' : null,
})
const list = (items, page = 0, totalElements = items.length, totalPages = items.length ? 1 : 0) =>
  Response.json({ items, page, size: 10, totalElements, totalPages })

async function mount(t, handle) {
  globalThis.fetch = (url, options) => handle(new URL(url, 'http://localhost'), options)
  const view = document.createElement('div')
  document.body.append(view)
  const root = createRoot(view)
  t.after(async () => {
    await act(async () => root.unmount())
    view.remove()
    globalThis.fetch = originalFetch
  })
  await act(async () => root.render(createElement(MemoryRouter, null, createElement(TransactionHistoryPage))))
  return view
}

const button = (view, text) => [...view.querySelectorAll('button')].find(element => element.textContent.trim() === text)
const inputFor = (view, text) => [...view.querySelectorAll('label')].find(element => element.textContent === text).control
async function click(element) {
  assert.ok(element)
  await act(async () => element.click())
}
async function change(element, value) {
  await act(async () => {
    Object.getOwnPropertyDescriptor(Object.getPrototypeOf(element), 'value').set.call(element, value)
    element.dispatchEvent(new dom.window.Event(element.tagName === 'SELECT' ? 'change' : 'input', { bubbles: true }))
  })
}

test('renders payment and booking states, loads own attempt details and withholds a pending PDF', async t => {
  const view = await mount(t, async url => {
    if (url.pathname === path) return list([pending])
    assert.equal(url.pathname, path + '/1')
    return Response.json(detail(pending))
  })
  assert.match(view.textContent, /Gallery Suite.*BK-ONE.*PMS-ONE/s)
  assert.match(view.textContent, /450\.000\s*₫/)
  assert.match(view.textContent, /Đang chờ xác minh thanh toán/)
  assert.doesNotMatch(view.querySelector('.transaction-list').textContent, /Thanh toán thành công/)
  assert.equal(view.querySelector('img').getAttribute('src'), '/api/v1/rooms/6/images/3?size=thumb')
  await click(view.querySelector('.transaction-card button'))
  const dialog = view.querySelector('dialog[open]')
  assert.ok(dialog)
  assert.match(dialog.textContent, /PMS-ONE.*VNPay Sandbox/s)
  assert.equal(dialog.querySelector('a').getAttribute('href'), '/my-bookings/BK-ONE')
  assert.equal(button(dialog, 'Tải PDF xác nhận'), undefined)
  await click(dialog.querySelector('[aria-label="Đóng hộp thoại"]'))
  assert.equal(view.querySelector('dialog[open]'), null)
})

test('submits search, provider, status, dates and sort and requests the next server page', async t => {
  const queries = []
  const view = await mount(t, async url => {
    queries.push(Object.fromEntries(url.searchParams))
    return list([pending], Number(url.searchParams.get('page')), 21, 3)
  })
  await change(inputFor(view, 'Tìm giao dịch'), '  PMS-ONE  ')
  await change(inputFor(view, 'Trạng thái giao dịch'), 'PROCESSING')
  await change(inputFor(view, 'Nhà cung cấp thanh toán'), 'VNPAY')
  await change(inputFor(view, 'Từ ngày'), '2026-11-01')
  await change(inputFor(view, 'Đến ngày'), '2026-11-30')
  await change(inputFor(view, 'Sắp xếp'), 'OLDEST')
  await click(button(view, 'Áp dụng'))
  const filters = { page: '0', size: '10', sort: 'OLDEST', search: 'PMS-ONE', status: 'PROCESSING', provider: 'VNPAY',
    from: '2026-11-01', to: '2026-11-30' }
  assert.deepEqual(queries.at(-1), filters)
  await click(button(view, 'Trang sau'))
  assert.deepEqual(queries.at(-1), { ...filters, page: '1' })
  assert.match(view.querySelector('nav').textContent, /Trang 2 \/ 3/)
  await click(button(view, 'Đặt lại'))
  assert.deepEqual(queries.at(-1), { page: '0', size: '10', sort: 'NEWEST' })
})

test('keeps loading, error and successful empty states distinct and retries', async t => {
  let resolveRequest
  let calls = 0
  const view = await mount(t, () => {
    if (++calls === 1) return new Promise(resolve => { resolveRequest = resolve })
    return Promise.resolve(list([]))
  })
  assert.ok(view.querySelector('.skeleton'))
  assert.doesNotMatch(view.textContent, /Bạn chưa có giao dịch nào/)
  await act(async () => resolveRequest(Response.json({ code: 'SERVER_ERROR' }, { status: 500 })))
  assert.match(view.querySelector('[role="alert"]').textContent, /Không thể tải lịch sử giao dịch/)
  await click(button(view, 'Thử lại'))
  assert.match(view.textContent, /Bạn chưa có giao dịch nào/)
  assert.equal(view.querySelector('[role="alert"]'), null)
})

test('renders the voucher breakdown and downloads only the eligible attempt through the booking PDF endpoint', async t => {
  let downloaded
  let receiptRequest
  const clickAnchor = dom.window.HTMLAnchorElement.prototype.click
  dom.window.HTMLAnchorElement.prototype.click = function () { downloaded = this.download }
  t.after(() => { dom.window.HTMLAnchorElement.prototype.click = clickAnchor })
  const view = await mount(t, async url => {
    if (url.pathname === path) return list([zero])
    if (url.pathname === path + '/2') return Response.json(detail(zero))
    receiptRequest = url.pathname + url.search
    return new Response('%PDF fixture', { headers: { 'Content-Type': 'application/pdf' } })
  })
  assert.equal(view.querySelectorAll('.transaction-card').length, 1)
  assert.match(view.querySelector('.transaction-card').textContent, /0\s*₫.*Voucher/s)
  await click(view.querySelector('.transaction-card button'))
  const dialog = view.querySelector('dialog[open]')
  assert.match(dialog.textContent, /500\.000\s*₫.*−50\.000\s*₫.*−450\.000\s*₫.*0\s*₫/s)
  await click(button(dialog, 'Tải PDF xác nhận'))
  assert.equal(receiptRequest, '/api/v1/bookings/BK-ZERO/confirmation.pdf?transactionId=2')
  assert.equal(downloaded, 'booking-BK-ZERO.pdf')
})
