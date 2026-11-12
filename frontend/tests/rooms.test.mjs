import assert from 'node:assert/strict'
import { after, test } from 'node:test'
import { mkdtemp, rm } from 'node:fs/promises'
import { resolve, sep } from 'node:path'
import { pathToFileURL, URL } from 'node:url'
import { JSDOM } from 'jsdom'
import { File, Blob } from 'node:buffer'
import { build } from 'vite'

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' })
const document = dom.window.document
const FormData = globalThis.FormData
const previous = new Map()
for (const [name, value] of Object.entries({ window: dom.window, document: dom.window.document, navigator: dom.window.navigator, IS_REACT_ACT_ENVIRONMENT: true })) {
  previous.set(name, Object.getOwnPropertyDescriptor(globalThis, name))
  Object.defineProperty(globalThis, name, { configurable: true, writable: true, value })
}
dom.window.HTMLDialogElement.prototype.showModal = function () { this.open = true }
dom.window.HTMLDialogElement.prototype.close = function () { this.open = false }
const createUrl = URL.createObjectURL, revokeUrl = URL.revokeObjectURL
URL.createObjectURL = () => 'blob:test-room-image'
URL.revokeObjectURL = () => {}
const { act, createElement: h } = await import('react')
const { createRoot } = await import('react-dom/client')
const { MemoryRouter, Routes, Route } = await import('react-router-dom')
const modules = resolve('node_modules')
const output = await mkdtemp(resolve(modules, '.rooms-'))
await build({ configFile: false, logLevel: 'silent', build: { ssr: 'tests/rooms.entry.tsx', outDir: output,
  rollupOptions: { external: ['react', 'react-dom', 'react-router-dom', 'react/jsx-runtime'], output: { entryFileNames: 'entry.mjs' } } } })
const { AdminRoomsPage, RoomDetailPage, roomApi, adminRoomApi, apiRequest } = await import(pathToFileURL(resolve(output, 'entry.mjs')).href)
const originalAdmin = { ...adminRoomApi }, originalRoom = { ...roomApi }
after(async () => {
  URL.createObjectURL = createUrl; URL.revokeObjectURL = revokeUrl
  if (!resolve(output).startsWith(modules + sep)) throw Error('Unexpected output path')
  await rm(output, { recursive: true, force: true }); dom.window.close()
  for (const [name, descriptor] of previous) {
    if (descriptor) Object.defineProperty(globalThis, name, descriptor)
    else delete globalThis[name]
  }
})
const type = () => ({ id: 5, code: 'DELUXE_DB', name: 'Deluxe từ DB', description: 'Phòng có ban công', maxOccupancy: 2, basePricePerNight: 950000, activeRoomCount: 1, active: true,
  coverImageUrl: '/api/v1/rooms/5/images/12', images: [ { id: 12, url: '/api/v1/rooms/5/images/12', cover: true, sortOrder: 1 }, { id: 11, url: '/api/v1/rooms/5/images/11', cover: false, sortOrder: 0 } ] })
function button(view, text) { return [...view.querySelectorAll('button')].find((item) => item.textContent.trim() === text) }
function label(view, text) {
  const target = [...view.querySelectorAll('label')].find((item) => item.textContent.startsWith(text))
  return view.querySelector(`[id="${target.htmlFor}"]`)
}
async function change(input, value) {
  await act(async () => {
    Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype, 'value').set.call(input, value)
    input.dispatchEvent(new dom.window.Event('input', { bubbles: true }))
  })
}
async function files(view, next) {
  const input = label(view, 'Ảnh phòng')
  Object.defineProperty(input, 'files', { configurable: true, value: next })
  await act(async () => input.dispatchEvent(new dom.window.Event('change', { bubbles: true })))
}
async function mount(t, component, path = '/admin/rooms') {
  const container = document.createElement('div'); document.body.append(container)
  const root = createRoot(container)
  await act(async () => root.render(h(MemoryRouter, { initialEntries: [path] }, h(Routes, null, h(Route, { path: component === RoomDetailPage ? '/rooms/:roomTypeId' : '/admin/rooms', element: h(component) })))))
  if (component === AdminRoomsPage && button(container, 'Thêm phòng mới')) await act(async () => button(container, 'Thêm phòng mới').click())
  t.after(async () => { await act(async () => root.unmount()); container.remove(); Object.assign(adminRoomApi, originalAdmin); Object.assign(roomApi, originalRoom) })
  return container
}
function setupAdmin(overrides = {}, current = { ...type(), images: [], coverImageUrl: null }) {
  Object.assign(adminRoomApi, { types: async () => [current], physical: async () => [], create: async () => ({ roomNumber: 'A105' }), ...overrides })
}
test('Admin uses database room types and prices, not hardcoded options', async (t) => {
  setupAdmin()
  const view = await mount(t, AdminRoomsPage)
  assert.match(view.querySelector('select').textContent, /Deluxe từ DB \(DELUXE_DB\)/)
  assert.match(view.textContent, /950\.000/)
  assert.equal(view.querySelector('option[value="1"]'), null)
})
test('Admin create sends selected files, cover index and room data; prevents double submit while pending', async (t) => {
  let sent, finish
  setupAdmin({ create: (...args) => { sent = args; return new Promise((resolve) => { finish = resolve }) } })
  const view = await mount(t, AdminRoomsPage)
  await change(label(view, 'Số phòng'), ' A105 '); await change(label(view, 'Tầng'), '3')
  const chosen = [new File(['one'], 'one.png', { type: 'image/png' }), new File(['two'], 'two.jpg', { type: 'image/jpeg' })]
  await files(view, chosen)
  await act(async () => view.querySelectorAll('input[type=radio]')[1].click())
  await act(async () => view.querySelector('form').dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true })))
  assert.deepEqual(sent[0], { roomTypeId: 5, roomNumber: 'A105', floorNumber: 3 })
  assert.deepEqual(sent[1], chosen); assert.equal(sent[2], 1)
  assert.equal(button(view, 'Tạo phòng & lưu ảnh').disabled, true)
  await act(async () => finish({ roomNumber: 'A105' }))
  assert.match(view.textContent, /Đã tạo phòng A105/)
  await act(async () => button(view, 'Thêm phòng mới').click())
  assert.equal(label(view, 'Số phòng').value, '')
})
test('Admin rejects more than five images and unsupported/oversized files before API', async (t) => {
  let count = 0
  setupAdmin({ create: async () => { count++ } })
  const view = await mount(t, AdminRoomsPage)
  for (const next of [Array.from({ length: 6 }, () => new File(['x'], 'x.png', { type: 'image/png' })), [new File(['<svg/>'], 'x.svg', { type: 'image/svg+xml' })], [new File([new Uint8Array(5242881)], 'big.png', { type: 'image/png' })]]) {
    await files(view, next)
    assert.match(view.querySelector('[role=alert]').textContent, /1–5 ảnh JPEG\/PNG/)
    assert.equal(button(view, 'Tạo phòng & lưu ảnh').disabled, true)
  }
  assert.equal(count, 0)
})
test('Existing gallery requires explicit replacement consent and conflict keeps the form', async (t) => {
  let count = 0
  setupAdmin({ create: async () => { count++; throw Error('Số phòng đã tồn tại') } }, type())
  const view = await mount(t, AdminRoomsPage)
  await change(label(view, 'Số phòng'), 'A105')
  await files(view, [new File(['x'], 'x.png', { type: 'image/png' })])
  await act(async () => view.querySelector('form').dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true })))
  assert.equal(count, 0)
  assert.match(view.textContent, /Cần xác nhận/)
  await act(async () => view.querySelector('input[type=checkbox]').click())
  await act(async () => view.querySelector('form').dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true })))
  assert.equal(count, 1); assert.match(view.textContent, /Số phòng đã tồn tại/); assert.equal(label(view, 'Số phòng').value, 'A105')
})
test('Admin price update keeps the RoomType contract and uses edited price', async (t) => {
  let saved
  setupAdmin({ price: async (...args) => { saved = args } })
  const view = await mount(t, AdminRoomsPage)
  await act(async () => button(view, 'Giá cơ bản').click())
  await change(label(view, 'Giá mỗi đêm'), '1230000')
  await act(async () => view.querySelector('form').dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true })))
  assert.equal(saved[0].id, 5); assert.equal(saved[1], 1230000)
})
test('Customer gallery shows cover first and opens next/previous/close lightbox', async (t) => {
  roomApi.get = async () => type()
  const view = await mount(t, RoomDetailPage, '/rooms/5?arrival=2035-04-01&departure=2035-04-03')
  const images = view.querySelectorAll('.image-gallery img')
  assert.equal(images.length, 2); assert.match(images[0].src, /images\/12$/)
  assert.match(view.querySelector('.gallery-main img').src, /images\/12$/)
  await act(async () => view.querySelectorAll('.image-gallery button')[1].click())
  assert.match(view.querySelector('.gallery-main img').src, /images\/11$/)
  await act(async () => view.querySelector('.image-gallery button').click())
  await act(async () => view.querySelector('.gallery-main').click())
  assert.equal(view.querySelector('dialog').open, true)
  const dialog = view.querySelector('dialog')
  assert.equal(button(dialog, 'Ảnh trước').disabled, true)
  await act(async () => button(dialog, 'Ảnh sau').click())
  assert.match(view.querySelector('.gallery-large').src, /images\/11$/)
  assert.equal(button(dialog, 'Ảnh sau').disabled, true)
  await act(async () => view.querySelector('button[aria-label="Đóng hộp thoại"]').click())
  assert.equal(view.querySelector('dialog').open, false)
  const link = [...view.querySelectorAll('a')].find((item) => item.textContent === 'Chọn ngày & đặt phòng')
  assert.match(link.href, /roomTypeId=5/); assert.match(link.href, /arrival=2035-04-01/)
})
test('Adding a room can reuse existing gallery without uploading or replacing it', async (t) => {
  let sent
  setupAdmin({ create: async (...args) => { sent = args; return { roomNumber: 'REUSE-1' } } }, type())
  const view = await mount(t, AdminRoomsPage)
  assert.equal(label(view, 'Ảnh phòng').required, false)
  await change(label(view, 'Số phòng'), 'REUSE-1')
  await act(async () => view.querySelector('form').dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true })))
  assert.equal(sent[1].length, 0); assert.equal(sent[3], false)
  assert.match(view.textContent, /Đã tạo phòng REUSE-1/)
})
test('Canceling an Admin draft clears files and fields without calling the API', async (t) => {
  let count = 0
  setupAdmin({ create: async () => { count++ } })
  const view = await mount(t, AdminRoomsPage)
  await change(label(view, 'Số phòng'), 'UNSAVED')
  await files(view, [new File(['x'], 'x.png', { type: 'image/png' })])
  await act(async () => button(view, 'Hủy tạo phòng').click())
  assert.equal(view.querySelector('form'), null)
  await act(async () => button(view, 'Thêm phòng mới').click())
  assert.equal(label(view, 'Số phòng').value, '')
  assert.equal(view.querySelector('.room-upload-preview'), null); assert.equal(count, 0)
})
test('Single-image gallery has zoom and close without redundant navigation', async (t) => {
  roomApi.get = async () => ({ ...type(), images: [type().images[0]] })
  const view = await mount(t, RoomDetailPage, '/rooms/5')
  assert.equal(view.querySelector('.image-gallery'), null)
  assert.equal(view.querySelector('.gallery-navigation'), null)
  await act(async () => view.querySelector('.gallery-main').click())
  assert.equal(view.querySelector('dialog').open, true)
  assert.equal(view.querySelector('.dialog-actions'), null)
})
test('Customer sees loading then a real error; retry loads the room', async (t) => {
  let fail
  roomApi.get = () => new Promise((_resolve, reject) => { fail = reject })
  const view = await mount(t, RoomDetailPage, '/rooms/5')
  assert.match(view.textContent, /Đang tải thông tin/); assert.equal(view.querySelector('.image-gallery'), null)
  await act(async () => fail(Error('Không tìm thấy hạng phòng')))
  assert.match(view.querySelector('[role=alert]').textContent, /Không tìm thấy/)
  roomApi.get = async () => type()
  await act(async () => button(view, 'Thử lại').click())
  assert.equal(view.querySelectorAll('.image-gallery img').length, 2)
})
test('Customer legacy room without images has a clear fallback', async (t) => {
  roomApi.get = async () => ({ ...type(), images: [], coverImageUrl: null })
  const view = await mount(t, RoomDetailPage, '/rooms/5')
  assert.match(view.textContent, /Chưa có ảnh phòng/)
  assert.match(view.textContent, /950\.000/)
})
test('HTTP client passes multipart through unchanged without a JSON content-type', async (t) => {
  const previousFetch = globalThis.fetch
  t.after(() => { globalThis.fetch = previousFetch })
  let sent
  globalThis.fetch = async (_url, options) => { sent = options; return new Response('{"id":1}', { status: 201, headers: { 'Content-Type': 'application/json' } }) }
  const form = new FormData(); form.append('images', new Blob(['x'], { type: 'image/png' }), 'x.png')
  await apiRequest('/api/v1/admin/rooms/physical', { method: 'POST', body: form, token: 'test-token' })
  assert.equal(sent.body, form); assert.equal(sent.headers['Content-Type'], undefined); assert.equal(sent.headers.Authorization, 'Bearer test-token')
  await apiRequest('/api/v1/auth/login', { method: 'POST', body: { email: 'x' }, auth: false })
  assert.equal(sent.headers['Content-Type'], 'application/json'); assert.equal(sent.body, '{"email":"x"}')
})
