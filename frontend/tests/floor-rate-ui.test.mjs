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
const { MemoryRouter } = await import('react-router-dom')
const modules = resolve('node_modules')
const output = await mkdtemp(resolve(modules, '.operations-'))
await build({ configFile: false, logLevel: 'silent', plugins: [{
  name: 'operations-test-auth', enforce: 'pre', transform(_source, id) {
    if (!id.replaceAll('\\', '/').endsWith('/src/features/auth/AuthProvider.tsx')) return
    return 'let state; export const setTestAuth = value => { state = value }; export const useAuth = () => state; export const AuthProvider = ({children}) => children;'
  },
}], build: { ssr: 'tests/operations.entry.tsx', outDir: output, rollupOptions: {
  external: ['react', 'react-dom', 'react-router-dom', 'react/jsx-runtime'], output: { entryFileNames: 'entry.mjs' },
} } })
const { App, FloorMapPage, RateCalendarPage, adminRoomApi, floorApi, rateApi, auth } = await import(pathToFileURL(resolve(output, 'entry.mjs')).href)
const apis = [floorApi, rateApi, adminRoomApi]
const original = apis.map(api => ({ ...api }))
after(async () => {
  if (!resolve(output).startsWith(modules + sep)) throw new Error('Unexpected test directory')
  await rm(output, { recursive: true, force: true })
  dom.window.close()
})

const at = '2026-11-07T08:00:00Z'
const room = (roomId, roomNumber, operationalStatus, roomTypeId = 1) => ({ roomId, roomNumber, roomTypeId,
  roomTypeName: roomTypeId === 1 ? 'Deluxe' : 'Suite', operationalStatus, block: null, assignment: null, updatedAt: at })
const overview = { asOf: at, floors: [
  { floorNumber: 1, rooms: [room(1, '101', 'AVAILABLE'), { ...room(2, '102', 'BLOCKED'), block: { id: 1, reason: 'Sửa khóa', startAt: at, endAt: at } }] },
  { floorNumber: 2, rooms: [room(3, '201', 'OUT_OF_SERVICE', 2)] },
] }
const types = [{ id: 1, name: 'Deluxe', code: 'DELUXE', active: true }, { id: 2, name: 'Suite', code: 'SUITE', active: true }]
const calendar = (roomTypeId, month, rules = []) => ({ roomTypeId, month, baseRate: 800000, rules, nights: [1, 2].map(day => {
  const date = `${month}-0${day}`
  const rule = rules.find(item => item.startsOn <= date && item.endsOn >= date)
  return { date, nightlyRate: rule?.nightlyRate ?? 800000, weekend: false, kind: rule?.kind ?? null, ruleId: rule?.id ?? null }
}) })

async function mount(t, Component, path = '/staff/rooms', roles = ['ADMIN']) {
  auth.setTestAuth({ status: 'authenticated', user: { id: 1, fullName: 'QA', email: 'qa@test.invalid', roles } })
  const container = document.createElement('div')
  document.body.append(container)
  const root = createRoot(container)
  t.after(async () => {
    await act(async () => root.unmount())
    container.remove()
    apis.forEach((api, index) => Object.assign(api, original[index]))
  })
  await act(async () => root.render(h(MemoryRouter, { initialEntries: [path] }, h(Component))))
  return container
}

const button = (container, text) => [...container.querySelectorAll('button')].find(item => item.textContent.trim() === text)
const inputFor = (container, text) => [...container.querySelectorAll('label')].find(item => item.textContent.startsWith(text)).control
const click = async element => { assert.ok(element); await act(async () => element.click()) }
async function change(element, value) {
  await act(async () => {
    Object.getOwnPropertyDescriptor(Object.getPrototypeOf(element), 'value').set.call(element, value)
    element.dispatchEvent(new dom.window.Event(element.tagName === 'SELECT' ? 'change' : 'input', { bubbles: true }))
  })
}

test('floor map groups rooms, combines filters and opens details with role-scoped links', async t => {
  floorApi.overview = async () => overview
  const view = await mount(t, FloorMapPage, '/staff/rooms', ['STAFF'])
  assert.equal(view.querySelectorAll('.floor-section').length, 2)
  await change(inputFor(view, 'Loại phòng'), '1')
  await change(inputFor(view, 'Trạng thái'), 'BLOCKED')
  assert.equal(view.querySelectorAll('.floor-room').length, 1)
  await click(view.querySelector('.floor-room'))
  assert.match(view.querySelector('dialog[open]').textContent, /Phòng 102.*Sửa khóa/s)
  assert.equal(view.querySelector('dialog a[href^="/admin/rooms"]'), null)
  await click(view.querySelector('dialog button[aria-label="Đóng hộp thoại"]'))
  await change(inputFor(view, 'Tìm số phòng'), '201')
  assert.match(view.textContent, /Không có phòng phù hợp/)
})

test('floor map distinguishes pending, error and a successful empty refresh', async t => {
  let fail
  floorApi.overview = () => new Promise((_resolve, reject) => { fail = reject })
  const view = await mount(t, FloorMapPage)
  assert.ok(view.querySelector('.skeleton'))
  assert.doesNotMatch(view.textContent, /Chưa có phòng vật lý/)
  await act(async () => fail(new Error('Mất kết nối')))
  assert.match(view.querySelector('[role="alert"]').textContent, /Mất kết nối/)
  floorApi.overview = async () => ({ ...overview, floors: [] })
  await click(button(view, 'Làm mới'))
  assert.match(view.textContent, /Chưa có phòng vật lý/)
})

test('workspace navigation exposes Floor Map to staff and Rate Calendar to Admin', async t => {
  floorApi.overview = async () => overview
  const staff = await mount(t, App, '/staff/rooms', ['STAFF'])
  assert.ok(staff.querySelector('a[href="/staff/rooms"]'))
  assert.equal(staff.querySelector('a[href="/admin/rate-calendar"]'), null)
  const admin = await mount(t, App, '/staff/rooms', ['ADMIN'])
  assert.ok(admin.querySelector('a[href="/admin/rate-calendar"]'))
})

test('rate calendar reloads the selected room type and month, with errors visible', async t => {
  const requests = []
  adminRoomApi.types = async () => types
  rateApi.month = async (id, month) => { requests.push([id, month]); return calendar(id, month) }
  const view = await mount(t, RateCalendarPage, '/admin/rate-calendar')
  const initial = inputFor(view, 'Tháng xem giá').value
  await change(inputFor(view, 'Loại phòng'), '2')
  assert.deepEqual(requests.at(-1), [2, initial])
  await click(view.querySelector('button[aria-label="Tháng sau"]'))
  assert.notEqual(requests.at(-1)[1], initial)
  rateApi.month = async () => { throw new Error('Không thể tải giá') }
  await click(view.querySelector('button[aria-label="Tháng sau"]'))
  assert.match(view.querySelector('[role="alert"]').textContent, /Không thể tải giá/)
  assert.equal(view.querySelector('.rate-grid'), null)
})

test('range edit previews backend values, requires confirmation, refreshes and deactivates', async t => {
  let rules = []
  let saves = 0
  adminRoomApi.types = async () => types
  rateApi.month = async (id, month) => calendar(id, month, rules)
  rateApi.preview = async draft => [{ before: { nightlyRate: 800000 }, after: { date: draft.startsOn, nightlyRate: Number(draft.nightlyRate) } }]
  rateApi.save = async draft => { saves += 1; const rule = { ...draft, id: 8, nightlyRate: Number(draft.nightlyRate) }; rules = [rule]; return rule }
  rateApi.deactivate = async () => { rules = [] }
  const view = await mount(t, RateCalendarPage, '/admin/rate-calendar')
  await click(button(view, 'Chọn khoảng đêm'))
  await click(view.querySelectorAll('.rate-cell')[1])
  await change(inputFor(view, 'Giá mỗi đêm'), '1250000')
  await click(button(view, 'Xem trước'))
  assert.match(view.querySelector('.rate-preview').textContent, /1\.250\.000/)
  await click(button(view, 'Lưu quy tắc'))
  assert.equal(saves, 0)
  await click(button(view.querySelector('dialog[open]'), 'Xác nhận'))
  assert.equal(saves, 1)
  assert.match(view.querySelector('.rate-cell').textContent, /1\.250\.000/)
  await click(view.querySelector('[aria-label="Sửa quy tắc Giá chỉ định"]'))
  await click(button(view, 'Ngừng áp dụng'))
  await click(button(view.querySelector('dialog[open]'), 'Xác nhận'))
  assert.match(view.querySelector('.rate-cell').textContent, /800\.000/)
})
