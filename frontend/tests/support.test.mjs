import assert from 'node:assert/strict'
import { after, test } from 'node:test'
import { mkdtemp, rm } from 'node:fs/promises'
import { resolve, sep } from 'node:path'
import { pathToFileURL, URL } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'
import { JSDOM } from 'jsdom'
import { build } from 'vite'

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost:5173/' })
const document = dom.window.document
const descriptors = Object.entries({
  window: dom.window, document, navigator: dom.window.navigator, sessionStorage: dom.window.sessionStorage,
  FormData: dom.window.FormData, IS_REACT_ACT_ENVIRONMENT: true,
}).map(([name, value]) => {
  const previous = Object.getOwnPropertyDescriptor(globalThis, name)
  Object.defineProperty(globalThis, name, { configurable: true, writable: true, value })
  return [name, previous]
})
const { act, createElement: h } = await import('react')
const { createRoot } = await import('react-dom/client')
const { MemoryRouter, Route, Routes } = await import('react-router-dom')
const modules = resolve('node_modules')
const output = await mkdtemp(resolve(modules, '.support-'))
await build({ configFile: false, logLevel: 'silent',
  plugins: [{ name: 'support-test-auth', enforce: 'pre', transform(_code, id) {
    if (id.replaceAll('\\', '/').endsWith('/src/features/auth/AuthProvider.tsx')) return 'let auth; export const useAuth = () => auth; export const setAuth = value => auth = value;'
  } }],
  build: { ssr: 'tests/support.entry.tsx', outDir: output, rollupOptions: {
    external: ['react', 'react-dom', 'react-router-dom', 'react/jsx-runtime'], output: { entryFileNames: 'entry.mjs' },
  } },
})
const { auth, supportApi, CustomerSupportWidget, StaffSupportPage, AdminFaqPage, AnswerDetails, mergeMessages } =
  await import(pathToFileURL(resolve(output, 'entry.mjs')).href)
const original = { ...supportApi }
after(async () => {
  if (!resolve(output).startsWith(modules + sep)) throw Error('Unexpected output path')
  await rm(output, { recursive: true, force: true })
  dom.window.close()
  for (const [name, descriptor] of descriptors) {
    if (descriptor) Object.defineProperty(globalThis, name, descriptor)
    else delete globalThis[name]
  }
})

const now = '2026-11-18T05:00:00Z'
const answer = (text, more = {}) => ({ text, source: 'FAQ', rooms: [], bookings: [], loginRequired: false, suppressed: false, ...more })
const waiting = { id: 2, status: 'WAITING_STAFF', customerName: 'Khách thử nghiệm', assignedToMe: false,
  staffOnline: false, unread: 1, preview: 'Xin chào', updatedAt: now }
const active = { ...waiting, status: 'STAFF_ACTIVE', assignedToMe: true }
const message = { id: 4, sender: 'CUSTOMER', content: 'Xin chào', createdAt: now }
const byButton = (view, label) => [...view.querySelectorAll('button')].find(item => item.textContent.trim() === label)
const click = button => act(async () => button.click())
function change(field, value) {
  return act(async () => {
    const prototype = field.tagName === 'TEXTAREA' ? dom.window.HTMLTextAreaElement.prototype : dom.window.HTMLInputElement.prototype
    Object.getOwnPropertyDescriptor(prototype, 'value').set.call(field, value)
    field.dispatchEvent(new dom.window.Event('input', { bubbles: true }))
  })
}
async function mount(t, page, roles = []) {
  auth.setAuth({ user: roles.length ? { email: 'support@test.invalid', roles } : undefined })
  supportApi.status = async () => ({ aiConfigured: false, staffOnline: 0 })
  const node = document.createElement('div'); document.body.append(node)
  const root = createRoot(node)
  await act(async () => root.render(h(MemoryRouter, null, h(Routes, null, h(Route, { path: '*', element: page })))))
  t.after(async () => {
    await act(async () => root.unmount()); node.remove(); Object.assign(supportApi, original); sessionStorage.clear()
  })
  return node
}

test('guest opens chat, sees grounded FAQ loading and can minimize with Escape', async t => {
  let finish
  supportApi.ask = () => new Promise(resolve => { finish = resolve })
  const view = await mount(t, h(CustomerSupportWidget))
  assert.equal(view.querySelector('[role=log]'), null)
  await click(byButton(view, 'Hỗ trợ'))
  assert.match(view.textContent, /AI chưa được cấu hình/)
  await click(byButton(view, 'Giờ nhận / trả phòng'))
  assert.match(view.textContent, /Đang xử lý/)
  await act(async () => finish(answer('Nhận phòng lúc 12:00.')))
  assert.match(view.querySelector('[role=log]').textContent, /Chính sách khách sạn.*12:00/s)
  await act(async () => view.querySelector('textarea').dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true })))
  assert.equal(view.querySelector('[role=log]'), null)
  assert.equal(document.activeElement, byButton(view, 'Hỗ trợ'))
})

test('guest booking and Staff handoff ask for login without creating private records', async t => {
  let requested = 0
  supportApi.open = async () => { requested++; return waiting }
  const view = await mount(t, h(CustomerSupportWidget))
  await click(byButton(view, 'Hỗ trợ'))
  await click(byButton(view, 'Kiểm tra đơn đặt phòng'))
  assert.equal(view.querySelector('.support-panel a[href="/login"]').textContent, 'Đăng nhập để xem booking')
  await click(byButton(view, 'Gặp nhân viên'))
  assert.equal(view.querySelector('.support-panel a[href="/login"]').textContent, 'Đăng nhập để gặp nhân viên')
  await click(view.querySelector('.support-panel a[href="/login"]'))
  assert.equal(view.querySelector('.support-panel'), null)
  assert.equal(sessionStorage.getItem('hotel-pms.support-intent'), 'staff')
  assert.equal(requested, 0)
})

test('booking intent after login opens a new AI conversation if the previous one is closed', async t => {
  sessionStorage.setItem('hotel-pms.support-intent', 'bookings')
  const closed = { ...active, status: 'CLOSED' }
  const reopened = { ...active, id: 5, status: 'AI_ACTIVE' }
  let created = false
  let request
  supportApi.list = async () => created ? [reopened, closed] : [closed]
  supportApi.get = async (_role, id) => id === reopened.id ? reopened : closed
  supportApi.messages = async () => []
  supportApi.open = async () => { created = true; return reopened }
  supportApi.ask = async payload => { request = payload; return answer('Đơn đặt phòng của bạn') }
  supportApi.send = async () => { assert.fail('Booking lookup must not send a Staff message') }
  const view = await mount(t, h(CustomerSupportWidget), ['CUSTOMER'])
  assert.match(view.textContent, /Đã kết thúc/)
  await click(byButton(view, 'Xem đơn đặt phòng của tôi'))
  assert.equal(request?.conversationId, 5)
  assert.equal(request?.action, 'BOOKINGS')
  assert.equal(sessionStorage.getItem('hotel-pms.support-intent'), null)
})

test('room recommendation uses the server quote, real cover and app booking route', async t => {
  const details = answer('Còn phòng', { source: 'HOTEL_PMS', arrival: '2090-01-01', departure: '2090-01-03', guests: 2,
    rooms: [{ id: 8, name: 'Hạng phòng QA', coverImageUrl: '/api/v1/rooms/8/images/9', capacity: 2, amenities: ['Wi-Fi'], available: 1, payable: 1600000 }] })
  const view = await mount(t, h(AnswerDetails, { answer: details }))
  assert.match(view.querySelector('img').getAttribute('src'), /\/rooms\/8\/images\/9/)
  assert.match(view.textContent, /1\.600\.000/)
  assert.equal([...view.querySelectorAll('a')].find(link => link.textContent === 'Đặt ngay').getAttribute('href'),
    '/booking?arrival=2090-01-01&departure=2090-01-03&adults=2&children=0&roomTypeId=8')
})

test('authenticated customer hands off and queues text while Staff is offline', async t => {
  let state = { ...waiting, status: 'AI_ACTIVE' }
  let saved
  supportApi.list = async () => []
  supportApi.open = async () => state
  supportApi.get = async () => state
  supportApi.messages = async (_role, _id, cursor) => saved && !cursor ? [saved] : []
  supportApi.read = async () => {}
  supportApi.handoff = async () => { state = waiting; return state }
  supportApi.send = async (_role, _id, body) => { saved = { ...message, content: body.content }; return saved }
  const view = await mount(t, h(CustomerSupportWidget), ['CUSTOMER'])
  await click(byButton(view, 'Hỗ trợ'))
  await click(byButton(view, 'Gặp nhân viên'))
  assert.match(view.textContent, /Hiện chưa có nhân viên trực tuyến/)
  await change(view.querySelector('textarea'), 'Tin nhắn lưu trong hàng chờ')
  await click(byButton(view, 'Gửi'))
  assert.match(view.querySelector('[role=log]').textContent, /Tin nhắn lưu trong hàng chờ/)
})

test('Staff inbox claims a pending thread and enables the reply composer', async t => {
  let thread = waiting
  supportApi.list = async () => [thread]
  supportApi.get = async () => thread
  supportApi.messages = async (_role, _id, cursor) => !cursor && thread.assignedToMe ? [message] : []
  supportApi.read = async () => {}
  supportApi.claim = async () => { thread = active; return thread }
  const view = await mount(t, h(StaffSupportPage), ['STAFF'])
  assert.match(view.textContent, /1 tin nhắn chưa đọc/)
  await click(view.querySelector('.support-thread-list button'))
  assert.equal(view.querySelector('textarea'), null)
  await click(byButton(view, 'Nhận cuộc trò chuyện'))
  assert.ok(view.querySelector('textarea'))
  assert.match(view.querySelector('[role=log]').textContent, /Xin chào/)
  assert.equal(mergeMessages([message], [{ ...message }, { ...message, id: 9 }]).length, 2)
})

test('socket reconnect uses the last displayed ID and deduplicates recovered messages', async t => {
  const sockets = []
  const before = globalThis.WebSocket
  class FakeSocket {
    static OPEN = 1
    readyState = 1
    sent = []
    constructor(url) { this.url = url; sockets.push(this) }
    send(frame) { this.sent.push(JSON.parse(frame)) }
    frame(type) { this.onmessage({ data: JSON.stringify({ type }) }) }
    close() { this.readyState = 3; this.onclose?.({ code: 1006 }) }
  }
  globalThis.WebSocket = FakeSocket
  t.after(() => { globalThis.WebSocket = before })
  const expiresAt = Date.now() + 60_000
  const claims = Buffer.from(JSON.stringify({ exp: Math.floor(expiresAt / 1000) })).toString('base64url')
  sessionStorage.setItem('hotel-pms.session.v1', JSON.stringify({ accessToken: `e30.${claims}.signature`, expiresAt }))
  const requests = []
  let recovered = false
  supportApi.list = async () => [active]
  supportApi.get = async () => active
  supportApi.read = async () => {}
  supportApi.messages = async (_staff, _id, cursor) => {
    requests.push(cursor)
    if (!cursor) return [message]
    if (recovered && cursor === '?after=4') return [message, { ...message, id: 5, content: 'Đến trong lúc offline' }]
    return []
  }
  const view = await mount(t, h(StaffSupportPage), ['STAFF'])
  await act(async () => { sockets[0].onopen(); sockets[0].frame('ready') })
  assert.equal(sockets[0].sent[0].type, 'auth')
  assert.equal(new URL(sockets[0].url).search, '')
  await click(byButton(view, 'Đang hỗ trợ (1)'))
  await click(view.querySelector('.support-thread-list button'))
  await act(async () => {
    sockets[0].close()
    await delay(1050)
  })
  recovered = true
  await act(async () => { sockets[1].onopen(); sockets[1].frame('ready') })
  assert.ok(requests.includes('?after=4'))
  assert.equal(view.querySelectorAll('[role=log] article').length, 2)
  assert.match(view.querySelector('[role=log]').textContent, /Đến trong lúc offline/)
})

test('Admin edits and deactivates FAQ while preview renders as text', async t => {
  let entry = { id: 7, category: 'Tiện ích', question: 'Có gì?', answer: '<b>Nội dung</b>', active: true, updatedAt: now }
  supportApi.faqList = async () => [entry]
  supportApi.saveFaq = async (draft, id) => { assert.equal(id, 7); entry = { ...entry, ...draft }; return entry }
  const view = await mount(t, h(AdminFaqPage), ['ADMIN'])
  await click(byButton(view, 'Sửa'))
  assert.equal(view.querySelector('.support-preview b'), null)
  await click(view.querySelector('input[type=checkbox]'))
  await click(byButton(view, 'Lưu FAQ'))
  assert.equal(entry.active, false)
  assert.match(view.textContent, /Đã cập nhật FAQ/)
})
