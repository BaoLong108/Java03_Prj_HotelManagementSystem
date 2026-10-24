import assert from 'node:assert/strict'
import { after, beforeEach, test } from 'node:test'
import { mkdtemp, rm } from 'node:fs/promises'
import { resolve, sep } from 'node:path'
import { pathToFileURL } from 'node:url'
import { build } from 'vite'

// Existing Vite + Node test runner; no extra dependency, live account or production fixture.
const modulesDirectory = resolve('node_modules')
const output = await mkdtemp(resolve(modulesDirectory, '.m5-02-tests-'))
const originalFetch = globalThis.fetch
const originalWindow = globalThis.window
const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage')
after(async () => {
  globalThis.fetch = originalFetch
  if (originalWindow === undefined) delete globalThis.window
  else globalThis.window = originalWindow
  if (originalStorage) Object.defineProperty(globalThis, 'sessionStorage', originalStorage)
  else delete globalThis.sessionStorage
  if (!resolve(output).startsWith(modulesDirectory + sep)) throw new Error('Unexpected test directory')
  await rm(output, { recursive: true, force: true })
})
await build({
  configFile: false, logLevel: 'silent',
  build: { ssr: 'tests/auth.entry.ts', outDir: output,
    rollupOptions: { output: { entryFileNames: 'entry.mjs' } },
  },
})
const { session, client, authApi, navigation, validateAuth, isAuthUser } = await import(pathToFileURL(resolve(output, 'entry.mjs')).href)
const key = 'hotel-pms.session.v1'
const user = { id: 7, fullName: 'Test Customer', email: 'test@example.test', roles: ['CUSTOMER'] }
const fields = { ...user, password: 'ValidPassword1!', confirmPassword: 'ValidPassword1!', phoneNumber: '' }
const token = (exp = Math.floor(Date.now() / 1000) + 3600, sub = '7') =>
  `${Buffer.from('{"alg":"HS256"}').toString('base64url')}.${Buffer.from(JSON.stringify({ exp, sub })).toString('base64url')}.synthetic`
function storeSession(accessToken = token()) {
  session.saveSession({ accessToken, expiresAt: Date.now() + 3600_000, user })
  return accessToken
}
let events
beforeEach(() => {
  const values = new Map()
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: {
    getItem: (name) => values.get(name) ?? null,
    setItem: (name, value) => values.set(name, value),
    removeItem: (name) => values.delete(name),
  } })
  globalThis.window = new EventTarget()
  events = []
  window.addEventListener(session.SESSION_ENDED_EVENT, (event) => events.push(event.detail))
  globalThis.fetch = async () => { throw new Error('Unexpected request') }
})

test('registration validates required fields, email and confirmation beside fields', () => {
  assert.deepEqual(Object.keys(validateAuth({ email: '', password: '', fullName: '', confirmPassword: '', phoneNumber: '' }, true)).sort(),
    ['confirmPassword', 'email', 'fullName', 'password'])
  assert.ok(validateAuth({ ...fields, email: 'not-an-email' }, true).email)
  assert.ok(validateAuth({ ...fields, confirmPassword: 'different' }, true).confirmPassword)
  assert.deepEqual(validateAuth(fields, true), {})
})
test('password follows backend UTF-8 byte limit, does not trim, and rejects blanks', () => {
  for (const password of ['x'.repeat(72), 'á'.repeat(36), '  valid password  ']) {
    assert.deepEqual(validateAuth({ ...fields, password, confirmPassword: password }, true), {})
  }
  for (const password of ['x'.repeat(73), 'á'.repeat(37), 'short', ' '.repeat(8)]) {
    assert.ok(validateAuth({ ...fields, password, confirmPassword: password }, true).password)
  }
})
test('full name/email/phone lengths and optional unrestricted phone match DTO', () => {
  assert.ok(validateAuth({ ...fields, fullName: 'x'.repeat(151) }, true).fullName)
  assert.ok(validateAuth({ ...fields, email: 'x'.repeat(251) + '@a.vn' }, true).email)
  assert.ok(validateAuth({ ...fields, phoneNumber: '1'.repeat(31) }, true).phoneNumber)
  assert.deepEqual(validateAuth({ ...fields, phoneNumber: '+84 (0) 90 123 4567' }, true), {})
  assert.deepEqual(validateAuth({ ...fields, password: 'wrong', confirmPassword: '' }, false), {})
})
test('role navigation covers CUSTOMER, STAFF, ADMIN and multi-role accounts', () => {
  assert.equal(navigation.loginDestination(['CUSTOMER']), '/')
  assert.equal(navigation.loginDestination(['CUSTOMER'], '/booking?start=tomorrow#quote'), '/booking?start=tomorrow#quote')
  for (const roles of [['STAFF'], ['ADMIN'], ['CUSTOMER', 'STAFF'], ['CUSTOMER', 'ADMIN']]) {
    assert.equal(navigation.loginDestination(roles), '/staff/calendar')
    assert.equal(navigation.loginDestination(roles, '/staff/bookings?q=123#results'), '/staff/bookings?q=123#results')
  }
  assert.equal(navigation.loginDestination(['CUSTOMER'], '/staff/calendar'), '/403')
  assert.equal(navigation.loginDestination(['STAFF'], '/admin'), '/403')
  assert.equal(navigation.loginDestination(['ADMIN'], '/admin'), '/admin')
})
test('return path rejects off-site, protocol-relative, backslash, auth and control paths', () => {
  for (const path of ['https://example.test', '//example.test', '/\\example.test', '/%2fexample.test', '/%5cexample.test',
    '/login', '/register?from=/', '/LOGIN#x', '/403', '/a/../login', '/%2e%2e/login', '/\nexample.test', null, {}]) {
    assert.equal(navigation.safeReturnPath(path), undefined)
  }
  assert.equal(navigation.safeReturnPath('/account?view=me#details'), '/account?view=me#details')
})

test('return paths reject every ASCII control and space while keeping normal query/hash navigation', () => {
  for (let code = 0; code <= 0x20; code++) {
    assert.equal(navigation.safeReturnPath(`/account${String.fromCharCode(code)}?view=profile`), undefined)
  }
  assert.equal(navigation.safeReturnPath('/account?view=profile#details'), '/account?view=profile#details')
})
test('storage persists only token, expiry and minimal display user, survives another read', () => {
  const accessToken = token()
  session.saveSession({ accessToken, expiresAt: Date.now() + 3600_000, password: 'must-not-persist',
    user: { ...user, password: 'must-not-persist', phoneNumber: 'must-not-persist' } })
  const saved = JSON.parse(sessionStorage.getItem(key))
  assert.deepEqual(Object.keys(saved).sort(), ['accessToken', 'expiresAt', 'user'])
  assert.deepEqual(Object.keys(saved.user).sort(), ['email', 'fullName', 'roles'])
  assert.equal(session.readSession().accessToken, accessToken)
  session.clearSession()
  assert.equal(sessionStorage.getItem(key), null)
  assert.equal(session.readSession(), null)
})
test('old token-only sessions are still readable for server revalidation', () => {
  sessionStorage.setItem(key, JSON.stringify({ accessToken: token(), expiresAt: Date.now() + 60_000 }))
  assert.ok(session.readSession())
})
test('malformed, missing-exp and expired tokens clear storage and notify once', () => {
  for (const accessToken of ['not-a-token', token(1), 'e30.e30.signature']) {
    storeSession(accessToken)
    assert.equal(session.readSession(), null)
    assert.equal(sessionStorage.getItem(key), null)
  }
  sessionStorage.setItem(key, 'not-json')
  assert.equal(session.readSession(), null)
  assert.equal(events.length, 4)
  session.readSession()
  assert.equal(events.length, 4)
})
test('cached expiry cannot extend JWT expiry; invalid cached role is not trusted', () => {
  const expiry = Math.floor(Date.now() / 1000) + 60
  sessionStorage.setItem(key, JSON.stringify({ accessToken: token(expiry), expiresAt: Date.now() + 86400_000,
    user: { ...user, roles: ['SUPERADMIN'] } }))
  assert.equal(session.readSession().expiresAt, expiry * 1000)
  assert.equal(session.readSession().user, undefined)
  assert.equal(isAuthUser({ ...user, roles: ['SUPERADMIN'] }), false)
})
test('login/register use exact endpoints and body; passwords are not trimmed', async () => {
  storeSession()
  const calls = []
  const response = { accessToken: token(), tokenType: 'Bearer', expiresIn: 3600, user }
  globalThis.fetch = async (url, options) => { calls.push({ url, options }); return Response.json(response) }
  await authApi.login({ email: user.email, password: ' pass word ' })
  await authApi.register({ email: user.email, password: ' pass word ', fullName: user.fullName, phoneNumber: '0901234567' })
  assert.equal(calls[0].url, '/api/v1/auth/login')
  assert.equal(calls[1].url, '/api/v1/auth/register')
  assert.equal(JSON.parse(calls[0].options.body).password, ' pass word ')
  assert.deepEqual(Object.keys(JSON.parse(calls[1].options.body)).sort(), ['email', 'fullName', 'password', 'phoneNumber'])
  for (const call of calls) {
    assert.equal(call.options.method, 'POST')
    assert.equal(call.options.headers.Authorization, undefined)
    assert.equal(call.options.credentials, 'omit')
  }
})
test('me sends current Bearer token and returns unwrapped user DTO', async () => {
  const accessToken = storeSession()
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/api/v1/auth/me')
    assert.equal(options.headers.Authorization, `Bearer ${accessToken}`)
    return Response.json(user)
  }
  assert.deepEqual(await authApi.me(), user)
})
test('400 preserves server message and field errors, ignores malformed detail values', async () => {
  globalThis.fetch = async () => Response.json({ code: 'VALIDATION_ERROR', message: 'Dữ liệu không hợp lệ',
    details: { fullName: 'Tên không hợp lệ', password: 'Mật khẩu quá dài', invalid: { nested: true } } }, { status: 400 })
  await assert.rejects(authApi.register(fields), (error) => error.status === 400 && error.message === 'Dữ liệu không hợp lệ'
    && error.details.fullName === 'Tên không hợp lệ' && error.details.password === 'Mật khẩu quá dài' && error.details.invalid === undefined)
})
test('duplicate email code is preserved; 403 does not sign out', async () => {
  storeSession()
  globalThis.fetch = async () => Response.json({ code: 'EMAIL_ALREADY_EXISTS', message: 'Email đã được đăng ký', details: {} }, { status: 409 })
  await assert.rejects(authApi.register(fields), (error) => error.status === 409 && error.code === 'EMAIL_ALREADY_EXISTS')
  globalThis.fetch = async () => Response.json({ code: 'FORBIDDEN', message: 'Không đủ quyền' }, { status: 403 })
  await assert.rejects(client.apiRequest('/protected'), (error) => error.status === 403)
  assert.ok(session.readSession())
  assert.equal(events.length, 0)
})
test('401 from current authenticated request clears session and passes server message', async () => {
  storeSession()
  globalThis.fetch = async () => Response.json({ code: 'UNAUTHORIZED', message: 'Tài khoản không còn khả dụng' }, { status: 401 })
  await assert.rejects(authApi.me(), (error) => error.status === 401)
  assert.equal(session.readSession(), null)
  assert.deepEqual(events, ['Tài khoản không còn khả dụng'])
})
test('401 without token notifies login; login 401 stays a form error without redirect', async () => {
  globalThis.fetch = async () => Response.json({ code: 'INVALID_CREDENTIALS', message: 'Email hoặc mật khẩu không đúng' }, { status: 401 })
  await assert.rejects(authApi.login(fields), /Email hoặc mật khẩu không đúng/)
  assert.equal(events.length, 0)
  await assert.rejects(client.apiRequest('/protected'), (error) => error.status === 401)
  assert.equal(events.length, 1)
})
test('a stale 401 cannot log out a newer session', async () => {
  storeSession()
  let finish
  globalThis.fetch = () => new Promise((resolveResponse) => { finish = resolveResponse })
  const pending = authApi.me()
  const newToken = storeSession(token(Math.floor(Date.now() / 1000) + 3600, '8'))
  finish(Response.json({ message: 'Old token invalid' }, { status: 401 }))
  await assert.rejects(pending, (error) => error.status === 401)
  assert.equal(session.readSession().accessToken, newToken)
  assert.equal(events.length, 0)
})
test('network/server failures are honest errors; abort is preserved', async () => {
  globalThis.fetch = async () => { throw new TypeError('Connection refused') }
  await assert.rejects(authApi.login(fields), (error) => error.status === 0 && error.code === 'NETWORK_ERROR')
  globalThis.fetch = async () => new Response('Bad gateway', { status: 502 })
  await assert.rejects(authApi.login(fields), (error) => error.status === 502 && error.message === 'API trả về HTTP 502')
  const controller = new AbortController()
  controller.abort()
  globalThis.fetch = async () => { throw controller.signal.reason }
  await assert.rejects(authApi.login(fields, controller.signal), (error) => error.name === 'AbortError')
})
test('register supports user-only and 204 successful responses without inventing a token', async () => {
  globalThis.fetch = async () => Response.json(user, { status: 201 })
  assert.deepEqual(await authApi.register(fields), user)
  globalThis.fetch = async () => new Response(null, { status: 204 })
  assert.equal(await authApi.register(fields), undefined)
  assert.equal(session.readSession(), null)
})
