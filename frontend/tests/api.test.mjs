import test from 'node:test'
import assert from 'node:assert/strict'
import { createApi, publicApi, pictureUrl, errorMessage, friendlyError, ApiError, query } from '../src/api/client.js'
import { createSseParser } from '../src/api/sse.js'

test('unexpected server content is never used as a user message', () => {
  for (const status of [400, 401, 403, 404, 409, 500]) {
    const message = errorMessage(status, { message: 'java.sql.Exception: secret database details', stack: 'internal' })
    assert.doesNotMatch(message, /java|secret|Exception|internal/)
  }
  assert.equal(errorMessage(400, { message: 'Insufficient funds' }), 'Insufficient funds')
  assert.notEqual(errorMessage(500, { message: 'Insufficient funds' }), 'Insufficient funds')
  assert.equal(friendlyError(new Error('sensitive')), 'Something went wrong. Please try again.')
})

test('queries encode filters safely and preserve pagination and sorting', () => {
  const parsed = new URLSearchParams(query({ symbol: 'A&B', page: 2, sort: 'asset.symbol,desc', type: '' }))
  assert.equal(parsed.get('symbol'), 'A&B'); assert.equal(parsed.get('page'), '2')
  assert.equal(parsed.get('sort'), 'asset.symbol,desc'); assert.equal(parsed.has('type'), false)
})

test('protected calls send bearer headers and no JWT in URLs', async t => {
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.doesNotMatch(url, /private-token/)
    assert.equal(options.headers.get('Authorization'), 'Bearer private-token')
    assert.equal(options.headers.get('Content-Type'), 'application/json')
    return new Response('{"ok":true}', { status: 200 })
  })
  assert.deepEqual(await createApi({ token: 'private-token' })('/portfolio/buy', { method: 'POST', body: { quantity: 0.01 } }), { ok: true })
})

test('401 and invalid-session 403 trigger session clearing even with empty bodies', async t => {
  for (const status of [401, 403]) {
    let invalid = 0
    t.mock.method(globalThis, 'fetch', async () => new Response('', { status }))
    await assert.rejects(createApi({ token: 'invalid', onInvalid: () => invalid++ })('/profile'), ApiError)
    assert.equal(invalid, 1)
    t.mock.restoreAll()
  }
})

test('role denial does not clear a valid session', async t => {
  let invalid = 0
  t.mock.method(globalThis, 'fetch', async url => url.endsWith('/auth/me')
    ? new Response('{"role":"USER"}', { status: 200 }) : new Response('', { status: 403 }))
  await assert.rejects(createApi({ token: 'valid', onInvalid: () => invalid++ })('/admin/users'), error => error.status === 403)
  assert.equal(invalid, 0)
})

test('network failures produce useful messages and preserve session', async t => {
  let invalid = 0
  t.mock.method(globalThis, 'fetch', async () => { throw new Error('internal address') })
  await assert.rejects(createApi({ token: 'valid', onInvalid: () => invalid++ })('/profile'), error => /Unable to connect/.test(error.message))
  assert.equal(invalid, 0)
})

test('cancelled response bodies cannot publish data after logout', async t => {
  const controller = new AbortController()
  t.mock.method(globalThis, 'fetch', async () => ({ ok: true, status: 200, json: async () => { controller.abort(); return { firstName: 'Previous user' } } }))
  await assert.rejects(createApi({ token: 'valid', signal: controller.signal })('/profile'), error => error.name === 'AbortError')
})

test('SSE parser handles fragmented CRLF frames, comments, names and multiline data', () => {
  const events = []
  const parse = createSseParser(event => events.push(event))
  parse(': connected\r\n\r\nid: trade-1\r\nevent: investment-notifi')
  parse('cation\r\ndata: Purchase completed: 0.01 shares of AAPL\r\n\r')
  parse('\nid: trade-2\nevent: other\ndata: first\ndata: second\n\n')
  assert.deepEqual(events, [
    { event: 'investment-notification', id: 'trade-1', data: 'Purchase completed: 0.01 shares of AAPL' },
    { event: 'other', id: 'trade-2', data: 'first\nsecond' },
  ])
})

test('API paths route to their microservices without a monolith fallback', async t => {
  const routes = [
    ['/auth/login', 8084], ['/auth/me', 8084], ['/auth/register', 8084],
    ['/auth/forgot-password', 8084], ['/auth/reset-password', 8084], ['/auth/change-password', 8084],
    ['/profile', 8084], ['/profile/picture', 8084], ['/admin/users?size=1', 8084],
    ['/admin/users/42/status?status=INACTIVE', 8084], ['/uploads/profile-pictures/avatar.png', 8084],
    ['/assets?search=A%26B', 8085], ['/assets/1/price-history', 8085],
    ['/portfolio/buy', 8085], ['/portfolio/sell', 8085], ['/portfolio/holdings', 8085],
    ['/portfolio/transactions?page=2&sort=createdAt,desc', 8085], ['/portfolio/performance', 8085], ['/portfolio/history', 8085],
    ['/orders', 8085], ['/orders/7/cancel', 8085], ['/watchlist', 8085], ['/watchlist/1', 8085],
    ['/price-alerts', 8085], ['/price-alerts/1/cancel', 8085],
    ['/admin/assets', 8085], ['/admin/assets/1/status', 8085],
    ['/admin/assets/execute-pending', 8085], ['/admin/audit-logs?page=1', 8085],
    ['/notifications/subscribe', 8083],
  ]
  const requests = []
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    requests.push(url)
    assert.equal(options.headers.get('Authorization'), 'Bearer synthetic-token')
    assert.doesNotMatch(url, /synthetic-token|:8081/)
    return new Response('{}', { status: 200 })
  })
  const api = createApi({ token: 'synthetic-token' })
  for (const [path, port] of routes) {
    await api(path)
    assert.equal(requests.at(-1), `http://localhost:${port}${path}`)
  }
})

test('public authentication calls retain their interface and omit bearer credentials', async t => {
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(url, 'http://localhost:8084/auth/login')
    assert.equal(options.headers.has('Authorization'), false)
    assert.equal(options.body, JSON.stringify({ emailAddress: 'user@example.test', password: 'synthetic' }))
    return new Response('{"token":"synthetic"}', { status: 200 })
  })
  assert.deepEqual(await publicApi('/auth/login', { method: 'POST', body: { emailAddress: 'user@example.test', password: 'synthetic' } }), { token: 'synthetic' })
})

test('Investment role denial checks identity with Authentication and preserves a valid session', async t => {
  const requests = []
  let invalid = 0
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    requests.push(url)
    assert.equal(options.headers.get('Authorization'), 'Bearer synthetic-token')
    return url === 'http://localhost:8084/auth/me'
      ? new Response('{"role":"USER"}', { status: 200 }) : new Response('', { status: 403 })
  })
  await assert.rejects(createApi({ token: 'synthetic-token', onInvalid: () => invalid++ })('/admin/assets'), error => error.status === 403)
  assert.deepEqual(requests, ['http://localhost:8085/admin/assets', 'http://localhost:8084/auth/me'])
  assert.equal(invalid, 0)
})

test('profile images always use Authentication with existing path normalization', () => {
  assert.equal(pictureUrl('uploads/profile-pictures/avatar.png'), 'http://localhost:8084/uploads/profile-pictures/avatar.png')
  assert.equal(pictureUrl('uploads\\profile-pictures\\avatar.png'), 'http://localhost:8084/uploads/profile-pictures/avatar.png')
  assert.equal(pictureUrl('/uploads/profile-pictures/avatar.png'), 'http://localhost:8084/uploads/profile-pictures/avatar.png')
  assert.equal(pictureUrl(null), '')
})

test('unknown paths and lookalike prefixes cannot send credentials to a fallback service', async t => {
  let requests = 0
  t.mock.method(globalThis, 'fetch', async () => { requests++; return new Response('{}') })
  for (const path of ['/admin/users-extra', '/admin/assets-extra', '/authentication', '/unknown', 'http://localhost:8081/auth/me']) {
    await assert.rejects(createApi({ token: 'synthetic-token' })(path), ApiError)
  }
  assert.equal(requests, 0)
})

test('multipart uploads and empty and plain-text responses retain their behavior across services', async t => {
  const body = new FormData()
  body.append('picture', new Blob(['synthetic-image'], { type: 'image/png' }), 'avatar.png')
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    if (url === 'http://localhost:8084/profile/picture') {
      assert.equal(options.body, body)
      assert.equal(options.headers.has('Content-Type'), false)
      return new Response('{}', { status: 200 })
    }
    if (url === 'http://localhost:8085/price-alerts/1/cancel') return new Response(null, { status: 204 })
    assert.equal(url, 'http://localhost:8085/orders')
    return new Response('Limit order placed successfully', { status: 200 })
  })
  const api = createApi({ token: 'synthetic-token' })
  assert.deepEqual(await api('/profile/picture', { method: 'PUT', body }), {})
  assert.equal(await api('/price-alerts/1/cancel', { method: 'PATCH' }), null)
  assert.equal(await api('/orders', { method: 'POST', responseType: 'text' }), 'Limit order placed successfully')
})
