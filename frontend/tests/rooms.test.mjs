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
const { AdminRoomsPage, DashboardPage, RoomDetailPage, RoomSearchPage, AuthProvider, roomApi, adminRoomApi, apiRequest, bookingApi } = await import(pathToFileURL(resolve(output, 'entry.mjs')).href)
const originalAdmin = { ...adminRoomApi }, originalRoom = { ...roomApi }
const originalBooking = { ...bookingApi }
bookingApi.quote = async () => ({ nights: 2, baseAmount: 1900000, discountRate: 0, discountAmount: 0, payableAmount: 1900000, nightlyRates: [{ date: '2035-04-01', nightlyRate: 950000 }] })
bookingApi.search = async () => [{ id: 5, availableRoomCount: 1 }]
after(async () => {
  Object.assign(bookingApi, originalBooking)
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
  const input = label(view, 'Thêm ảnh vào bộ sưu tập')
  Object.defineProperty(input, 'files', { configurable: true, value: next })
  await act(async () => input.dispatchEvent(new dom.window.Event('change', { bubbles: true })))
}
async function mount(t, component, path = '/admin/rooms') {
  const container = document.createElement('div'); document.body.append(container)
  const root = createRoot(container)
  const route = component === RoomDetailPage ? '/rooms/:roomTypeId' : component === RoomSearchPage ? '/rooms' : component === DashboardPage ? '/' : '/admin/rooms'
  const element = component === AdminRoomsPage ? h(component) : h(AuthProvider, null, h(component))
  await act(async () => root.render(h(MemoryRouter, { initialEntries: [path] }, h(Routes, null, h(Route, { path: route, element })))))
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
test('Admin creates a physical room under a type with no gallery using only room data', async (t) => {
  let sent, finish
  setupAdmin({ create: (...args) => { sent = args; return new Promise((resolve) => { finish = resolve }) } })
  const view = await mount(t, AdminRoomsPage)
  await change(label(view, 'Số phòng'), ' A105 '); await change(label(view, 'Tầng'), '3')
  assert.equal(view.querySelector('input[type=file]'), null)
  await act(async () => view.querySelector('form').dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true })))
  assert.deepEqual(sent, [{ roomTypeId: 5, roomNumber: 'A105', floorNumber: 3, active: true }])
  assert.equal(button(view, 'Tạo phòng').disabled, true)
  await act(async () => finish({ roomNumber: 'A105' }))
  assert.match(view.textContent, /Đã tạo phòng A105/)
  await act(async () => button(view, 'Thêm phòng mới').click())
  assert.equal(label(view, 'Số phòng').value, '')
})
test('Admin gallery rejects more than five images and unsupported/oversized files before API', async (t) => {
  let count = 0
  setupAdmin({ gallery: async () => { count++ } })
  const view = await mount(t, AdminRoomsPage)
  await act(async () => button(view, 'Ảnh hạng phòng').click())
  for (const [next, message] of [
    [Array.from({ length: 6 }, () => new File(['x'], 'x.png', { type: 'image/png' })), /tối đa 5 ảnh/],
    [[new File(['<svg/>'], 'x.svg', { type: 'image/svg+xml' })], /JPEG\/PNG/],
    [[new File([new Uint8Array(5242881)], 'big.png', { type: 'image/png' })], /JPEG\/PNG/],
  ]) {
    await files(view, next)
    assert.match(view.querySelector('[role=alert]').textContent, message)
    assert.equal(button(view, 'Lưu bộ ảnh').disabled, true)
  }
  assert.equal(count, 0)
})
test('Duplicate physical room keeps the draft and never edits the gallery', async (t) => {
  let count = 0
  setupAdmin({ create: async () => { count++; throw Error('Số phòng đã tồn tại') } }, type())
  const view = await mount(t, AdminRoomsPage)
  await change(label(view, 'Số phòng'), 'A105')
  await change(label(view, 'Tầng'), '3')
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
  assert.equal(images.length, 2); assert.match(images[0].src, /images\/12\?size=thumb$/)
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
  const link = [...view.querySelectorAll('a')].find((item) => item.textContent === 'Đặt ngay')
  assert.match(link.href, /roomTypeId=5/); assert.match(link.href, /arrival=2035-04-01/)
})
test('Adding a room can reuse existing gallery without uploading or replacing it', async (t) => {
  let sent
  setupAdmin({ create: async (...args) => { sent = args; return { roomNumber: 'REUSE-1' } } }, type())
  const view = await mount(t, AdminRoomsPage)
  assert.equal(view.querySelector('input[type=file]'), null)
  await change(label(view, 'Số phòng'), 'REUSE-1')
  await change(label(view, 'Tầng'), '1')
  await act(async () => view.querySelector('form').dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true })))
  assert.deepEqual(sent, [{ roomTypeId: 5, roomNumber: 'REUSE-1', floorNumber: 1, active: true }])
  assert.match(view.textContent, /Đã tạo phòng REUSE-1/)
})
test('Canceling an Admin draft clears fields without calling the API', async (t) => {
  let count = 0
  setupAdmin({ create: async () => { count++ } })
  const view = await mount(t, AdminRoomsPage)
  await change(label(view, 'Số phòng'), 'UNSAVED')
  await change(label(view, 'Tầng'), '1')
  await act(async () => button(view, 'Hủy tạo phòng').click())
  assert.equal(view.querySelector('form'), null)
  await act(async () => button(view, 'Thêm phòng mới').click())
  assert.equal(label(view, 'Số phòng').value, '')
  assert.equal(label(view, 'Tầng').value, ''); assert.equal(count, 0)
})
test('Admin can create the first room type and continue directly to its gallery', async (t) => {
  let request
  setupAdmin({ types: async () => request ? [type()] : [], createType: async body => { request = body; return type() } })
  const view = await mount(t, AdminRoomsPage)
  await act(async () => button(view, 'Loại phòng').click())
  for (const [name, value] of Object.entries({ code: 'DELUXE_DB', name: 'Deluxe từ DB', description: 'Mô tả', maxOccupancy: '2', price: '950000', amenities: 'Wi-Fi\nBan công' })) {
    view.querySelector(`[name="${name}"]`).value = value
  }
  await act(async () => button(view, 'Tạo hạng phòng').click())
  assert.deepEqual(request, { code: 'DELUXE_DB', name: 'Deluxe từ DB', description: 'Mô tả', maxOccupancy: 2, basePricePerNight: 950000, amenities: ['Wi-Fi', 'Ban công'] })
  assert.ok(label(view, 'Thêm ảnh vào bộ sưu tập'))
})
test('Admin can preview uploads and save an empty gallery by removing the final image', async (t) => {
  const current = { ...type(), images: [type().images[0]] }
  let sent
  setupAdmin({ gallery: async (...args) => { sent = args } }, current)
  const view = await mount(t, AdminRoomsPage)
  await act(async () => button(view, 'Ảnh hạng phòng').click())
  await files(view, [new File(['x'], 'new.png', { type: 'image/png' })])
  assert.ok(view.querySelector('img[alt="Xem trước new.png"]'))
  await act(async () => view.querySelector('button[aria-label="Xóa ảnh 1"]').click())
  await act(async () => view.querySelector('button[aria-label="Xóa ảnh 1"]').click())
  await act(async () => button(view, 'Lưu bộ ảnh').click())
  assert.deepEqual(sent, [current.id, { items: [], coverIndex: -1, expectedRevision: '12:1:true' }, []])
})
test('Customer detail uses the latest cover when the window regains focus', async t => {
  roomApi.get = async () => type()
  const view = await mount(t, RoomDetailPage, '/rooms/5')
  assert.match(view.querySelector('.gallery-main img').src, /images\/12$/)
  roomApi.get = async () => ({ ...type(), images: type().images.map(image => ({ ...image, cover: image.id === 11 })) })
  await act(async () => window.dispatchEvent(new window.Event('focus')))
  assert.match(view.querySelector('.gallery-main img').src, /images\/11$/)
})
test('Single-image gallery has zoom and close without redundant navigation', async (t) => {
  roomApi.get = async () => ({ ...type(), images: [type().images[0]] })
  const view = await mount(t, RoomDetailPage, '/rooms/5')
  assert.equal(view.querySelector('.image-gallery'), null)
  assert.equal(view.querySelector('.gallery-navigation'), null)
  await act(async () => view.querySelector('.gallery-main').click())
  assert.equal(view.querySelector('dialog').open, true)
  assert.equal(view.querySelector('dialog').querySelector('.dialog-actions'), null)
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

test('mobile swipe changes the main image and lightbox arrows use the saved gallery order', async t => {
  roomApi.get = async () => ({ ...type(), images: [...type().images].reverse() })
  const view = await mount(t, RoomDetailPage, '/rooms/5?arrival=2035-04-01&departure=2035-04-03')
  assert.match(view.querySelector('.gallery-main img').src, /images\/12$/)
  const stage = view.querySelector('.gallery-main')
  const start = new dom.window.Event('touchstart', { bubbles: true })
  const end = new dom.window.Event('touchend', { bubbles: true })
  Object.defineProperty(start, 'touches', { value: [{ clientX: 40, clientY: 100 }] })
  Object.defineProperty(end, 'changedTouches', { value: [{ clientX: 150, clientY: 105 }] })
  await act(async () => { stage.dispatchEvent(start); stage.dispatchEvent(end) })
  assert.match(stage.querySelector('img').src, /images\/11$/)
  await act(async () => stage.click())
  await act(async () => document.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'ArrowRight' })))
  assert.match(view.querySelector('.gallery-large').src, /images\/12$/)
})

test('guest search filters real amenities and sorts by the server total instead of the base rate', async t => {
  const deluxe = { ...type(), amenities: ['Ban công'] }
  const standard = { ...type(), id: 6, name: 'Standard từ DB', maxOccupancy: 3, basePricePerNight: 2000000, amenities: ['Wi-Fi'] }
  roomApi.list = async () => [deluxe, standard]
  const quote = bookingApi.quote, search = bookingApi.search
  bookingApi.search = async () => [deluxe, standard].map(room => ({ ...room, availableRoomCount: 2 }))
  bookingApi.quote = async room => ({ nights: 2, payableAmount: room.id === 5 ? 3800000 : 1400000, discountAmount: 0 })
  t.after(() => { bookingApi.quote = quote; bookingApi.search = search })
  const view = await mount(t, RoomSearchPage, '/rooms?arrival=2035-04-01&departure=2035-04-03&adults=2')
  const names = () => [...view.querySelectorAll('.portal-room-card h3')].map(item => item.textContent)
  assert.deepEqual(names(), ['Deluxe từ DB', 'Standard từ DB'])
  const sort = view.querySelector('.portal-sort select')
  await act(async () => { sort.value = 'price-asc'; sort.dispatchEvent(new dom.window.Event('change', { bubbles: true })) })
  assert.deepEqual(names(), ['Standard từ DB', 'Deluxe từ DB'])
  const balcony = [...view.querySelectorAll('.amenity-filter label')].find(item => item.textContent === 'Ban công')
  await act(async () => balcony.querySelector('input').click())
  assert.deepEqual(names(), ['Deluxe từ DB'])
  assert.match(view.querySelector('.portal-room-price').textContent, /3\.800\.000/)
})
test('landing has a search form and does not request or show the room catalog', async t => {
  let requests = 0
  roomApi.list = async () => { requests++; return [type()] }
  const view = await mount(t, DashboardPage, '/')
  assert.ok(view.querySelector('form.stay-search'))
  assert.ok(button(view, 'Tìm phòng'))
  assert.match(view.textContent, /Khám phá phòng nghỉ/)
  assert.equal(view.querySelector('.featured-rooms'), null)
  assert.equal(requests, 0)
})

test('catalog without dates shows base rates without inventing availability', async t => {
  roomApi.list = async () => [type()]
  const search = bookingApi.search
  let requests = 0
  bookingApi.search = async () => { requests++; return [] }
  t.after(() => { bookingApi.search = search })
  const view = await mount(t, RoomSearchPage, '/rooms')
  assert.match(view.textContent, /Deluxe từ DB.*giá cơ bản/s)
  assert.doesNotMatch(view.textContent, /Còn 1 phòng|Hết phòng theo lịch chọn/)
  assert.equal(requests, 0)
})

test('catalog keeps sold-out types visible and only passes the available room to booking', async t => {
  roomApi.list = async () => [type(), { ...type(), id: 6, name: 'Đã hết' }]
  const view = await mount(t, RoomSearchPage, '/rooms?arrival=2035-04-01&departure=2035-04-03&adults=2')
  const cards = view.querySelectorAll('.portal-room-card')
  assert.equal(cards.length, 2)
  assert.match(cards[1].textContent, /Còn 0 phòng.*Hạng phòng này đã hết phòng trong khoảng ngày bạn chọn\./s)
  assert.equal(cards[1].querySelector('a[href^="/booking"]'), null)
  assert.equal(cards[0].querySelector('a[href^="/booking"]').getAttribute('href'),
    '/booking?arrival=2035-04-01&departure=2035-04-03&adults=2&children=0&roomTypeId=5')
})

test('HTTP client passes multipart through unchanged without a JSON content-type', async (t) => {
  const previousFetch = globalThis.fetch
  t.after(() => { globalThis.fetch = previousFetch })
  let sent
  globalThis.fetch = async (_url, options) => { sent = options; return new Response('{"id":1}', { status: 201, headers: { 'Content-Type': 'application/json' } }) }
  const form = new FormData(); form.append('images', new Blob(['x'], { type: 'image/png' }), 'x.png')
  await apiRequest('/api/v1/admin/rooms/types/5/images', { method: 'PUT', body: form, token: 'test-token' })
  assert.equal(sent.body, form); assert.equal(sent.headers['Content-Type'], undefined); assert.equal(sent.headers.Authorization, 'Bearer test-token')
  await apiRequest('/api/v1/auth/login', { method: 'POST', body: { email: 'x' }, auth: false })
  assert.equal(sent.headers['Content-Type'], 'application/json'); assert.equal(sent.body, '{"email":"x"}')
})
