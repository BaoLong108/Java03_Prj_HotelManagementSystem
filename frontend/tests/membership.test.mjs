import assert from 'node:assert/strict'
import { after, test } from 'node:test'
import { mkdtemp, rm } from 'node:fs/promises'
import { resolve, sep } from 'node:path'
import { pathToFileURL } from 'node:url'
import { JSDOM } from 'jsdom'
import { build } from 'vite'

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' })
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
const output = await mkdtemp(resolve(modules, '.membership-'))
await build({
  configFile: false, logLevel: 'silent',
  plugins: [{ name: 'membership-test-auth', enforce: 'pre', transform(_code, id) {
    if (!id.replaceAll('\\', '/').endsWith('/src/features/auth/AuthProvider.tsx')) return
    return 'let state; export const setTestAuth = value => { state = value }; export const useAuth = () => state;'
  } }],
  build: { ssr: 'tests/membership.entry.tsx', outDir: output,
    rollupOptions: { external: ['react', 'react-dom', 'react-router-dom', 'react/jsx-runtime'], output: { entryFileNames: 'entry.mjs' } } },
})
const { AccountPage, membershipApi, voucherApi, auth } = await import(pathToFileURL(resolve(output, 'entry.mjs')).href)
const originalApi = { ...membershipApi }
after(async () => {
  if (!resolve(output).startsWith(modules + sep)) throw new Error('Unexpected test directory')
  await rm(output, { recursive: true, force: true })
  dom.window.close()
  for (const [name, descriptor] of globals) {
    if (descriptor) Object.defineProperty(globalThis, name, descriptor)
    else delete globalThis[name]
  }
})
const summary = { tierCode: 'VIP', eligibleSpendTotal: 10000000, pointsBalance: 1000,
  discountRate: 0.1, nextTier: 'DIAMOND', nextTierSpend: 30000000, remainingSpend: 20000000 }
const award = code => ({ bookingCode: code, eligibleAmount: 1000000, pointsAwarded: 100, awardedAt: '2032-01-10T05:00:00Z' })
const history = (content = [], page = 0, totalPages = 1) => ({ content, page, size: 10, totalElements: content.length, totalPages })
function deferred() {
  let resolve
  const promise = new Promise(yes => { resolve = yes })
  return { promise, resolve }
}
function user(id = 1, roles = ['CUSTOMER']) { return { id, fullName: 'Demo Customer', email: 'demo@test.invalid', roles } }
async function mount(t, overrides = {}, currentUser = user(), voucherOverrides = {}) {
  voucherApi.offers = async () => []
  voucherApi.wallet = async () => ({ content: [], page: 0, totalPages: 0, totalElements: 0 })
  Object.assign(voucherApi, voucherOverrides)
  Object.assign(membershipApi, { summary: async () => summary, history: async () => history(), ...overrides })
  auth.setTestAuth({ user: currentUser })
  const container = dom.window.document.createElement('div')
  dom.window.document.body.append(container)
  const root = createRoot(container)
  const render = () => root.render(createElement(MemoryRouter, null, createElement(AccountPage)))
  t.after(async () => {
    await act(async () => root.unmount())
    container.remove()
    Object.assign(membershipApi, originalApi)
  })
  await act(async () => render())
  return { container, render }
}
function button(view, label) { return [...view.querySelectorAll('button')].find(item => item.textContent.trim() === label) }

test('account shows server tier, spend, points and progress with the real award ledger', async t => {
  const { container } = await mount(t, { history: async () => history([award('BK-OWN')]) })
  assert.match(container.textContent, /VIP · giảm 10%/)
  assert.match(container.textContent, /1\.000/)
  assert.match(container.textContent, /BK-OWN/)
  assert.equal(container.querySelector('progress').value, 10000000)
  assert.equal(container.querySelector('progress').max, 30000000)
  assert.ok(container.querySelector('a[href="/my-bookings"]'))
})

test('pending API does not display an empty ledger or invent zero balances', async t => {
  const wait = deferred()
  const { container } = await mount(t, { summary: () => wait.promise, history: () => wait.promise })
  assert.match(container.textContent, /Đang tải thông tin thành viên/)
  assert.match(container.textContent, /Đang tải lịch sử tích điểm/)
  assert.doesNotMatch(container.textContent, /Chưa có giao dịch tích điểm/)
  assert.equal(container.querySelector('.loyalty-stats'), null)
})

test('empty ledger is shown only after successful API response', async t => {
  const { container } = await mount(t)
  assert.match(container.textContent, /Chưa có giao dịch tích điểm/)
})

test('summary and history errors have retry and do not masquerade as an empty history', async t => {
  let failing = true
  const { container } = await mount(t, {
    summary: async () => { if (failing) throw new Error('API đang bảo trì'); return summary },
    history: async () => { if (failing) throw new Error('Chưa tải được lịch sử'); return history([award('BK-RETRY')]) },
  })
  assert.equal(container.querySelectorAll('[role="alert"]').length, 2)
  assert.doesNotMatch(container.textContent, /Chưa có giao dịch tích điểm/)
  failing = false
  await act(async () => button(container, 'Tải lại').click())
  assert.match(container.textContent, /BK-RETRY/)
  assert.equal(container.querySelectorAll('[role="alert"]').length, 0)
})

test('history pagination uses server pages and clears rows while fetching', async t => {
  const pending = deferred()
  const { container } = await mount(t, { history: async page => page === 0 ? history([award('BK-FIRST')], 0, 2) : pending.promise })
  await act(async () => button(container, 'Trang sau').click())
  assert.doesNotMatch(container.textContent, /BK-FIRST/)
  assert.match(container.textContent, /Đang tải lịch sử/)
  await act(async () => pending.resolve(history([award('BK-SECOND')], 1, 2)))
  assert.match(container.textContent, /BK-SECOND/)
  assert.match(container.textContent, /Trang 2 \/ 2/)
})

test('account switch aborts prior requests and late results cannot overwrite the new account', async t => {
  const pendingSummary = deferred(), pendingHistory = deferred()
  let oldSignal
  const { container, render } = await mount(t, {
    summary: signal => { oldSignal = signal; return pendingSummary.promise }, history: () => pendingHistory.promise,
  })
  Object.assign(membershipApi, { summary: async () => ({ ...summary, tierCode: 'STANDARD', pointsBalance: 0 }), history: async () => history() })
  auth.setTestAuth({ user: user(2) })
  await act(async () => render())
  assert.equal(oldSignal.aborted, true)
  await act(async () => {
    pendingSummary.resolve(summary)
    pendingHistory.resolve(history([award('BK-PRIVATE-OLD')]))
  })
  assert.match(container.textContent, /Standard/)
  assert.doesNotMatch(container.textContent, /BK-PRIVATE-OLD|VIP · giảm 10%/)
})

test('operational accounts do not call customer membership endpoints', async t => {
  let calls = 0
  const { container } = await mount(t, { summary: () => { calls++; return summary }, history: () => { calls++; return history() } }, user(3, ['STAFF']))
  assert.equal(calls, 0)
  assert.match(container.textContent, /Không gian làm việc của bạn/)
})

const offers = [{ pointsCost: 50, discountAmount: 400000 }, { pointsCost: 100, discountAmount: 1000000 }, { pointsCost: 200, discountAmount: 2200000 }]
const voucher = (code = 'A1B2C3') => ({ code, pointsCost: 50, discountAmount: 400000, status: 'AVAILABLE', bookingId: null, createdAt: '2032-01-10T05:00:00Z', usedAt: null })
const wallet = (content = [], page = 0, totalPages = 1) => ({ content, page, totalPages, totalElements: content.length })

test('voucher offers use server values and insufficient balances disable redemption', async t => {
  const { container } = await mount(t, { summary: async () => ({ ...summary, pointsBalance: 50 }) }, user(), {
    offers: async () => offers, wallet: async () => wallet([voucher()]),
  })
  assert.equal(button(container, 'Đổi 50 điểm').disabled, false)
  assert.equal(button(container, 'Đổi 100 điểm').disabled, true)
  assert.equal(button(container, 'Đổi 200 điểm').disabled, true)
  assert.match(container.textContent, /A1B2C3/)
  assert.match(container.textContent, /400\.000/)
  assert.match(container.textContent, /2\.200\.000/)
})

test('redemption retries reuse the same key and success refreshes the point balance and wallet', async t => {
  const requests = []
  let balance = 150, redeemed = false
  const { container } = await mount(t, { summary: async () => ({ ...summary, pointsBalance: balance }) }, user(), {
    offers: async () => offers, wallet: async () => wallet(redeemed ? [voucher()] : []),
    redeem: async (points, key) => {
      requests.push({ points, key })
      if (requests.length === 1) throw new Error('Mất kết nối')
      redeemed = true; balance -= points
      return voucher()
    },
  })
  await act(async () => button(container, 'Đổi 50 điểm').click())
  assert.match(container.textContent, /Mất kết nối/)
  await act(async () => button(container, 'Đổi 50 điểm').click())
  assert.equal(requests[0].key, requests[1].key)
  assert.equal(requests[1].points, 50)
  assert.match(container.textContent, /Đổi điểm thành công/)
  assert.match(container.textContent, /A1B2C3/)
  assert.match(container.querySelector('.loyalty-stats').textContent, /100/)
  await act(async () => button(container, 'Đổi 50 điểm').click())
  assert.notEqual(requests[2].key, requests[1].key)
})

test('rapid double click starts one redemption request', async t => {
  const pending = deferred()
  let calls = 0
  const { container } = await mount(t, {}, user(), { offers: async () => offers, wallet: async () => wallet(),
    redeem: () => { calls++; return pending.promise },
  })
  await act(async () => { button(container, 'Đổi 50 điểm').click(); button(container, 'Đổi 50 điểm').click() })
  assert.equal(calls, 1)
  assert.equal(button(container, 'Đổi 100 điểm').disabled, true)
  await act(async () => pending.resolve(voucher()))
})

test('wallet errors have retry and never show a fake empty wallet', async t => {
  let failed = true
  const { container } = await mount(t, {}, user(), { offers: async () => offers,
    wallet: async () => { if (failed) throw new Error('Không tải được ví mã'); return wallet([voucher()]) },
  })
  assert.match(container.textContent, /Không tải được ví mã/)
  assert.doesNotMatch(container.textContent, /Bạn chưa đổi mã nào/)
  failed = false
  await act(async () => button(container, 'Tải lại mã').click())
  assert.match(container.textContent, /A1B2C3/)
})

test('late private wallet result is ignored after switching accounts', async t => {
  const pending = deferred()
  let signal
  const { container, render } = await mount(t, {}, user(), { offers: async () => offers,
    wallet: (_page, current) => { signal = current; return pending.promise },
  })
  voucherApi.wallet = async () => wallet()
  auth.setTestAuth({ user: user(2) })
  await act(async () => render())
  assert.equal(signal.aborted, true)
  await act(async () => pending.resolve(wallet([voucher('Z9X8C7')])))
  assert.doesNotMatch(container.textContent, /Z9X8C7/)
})
