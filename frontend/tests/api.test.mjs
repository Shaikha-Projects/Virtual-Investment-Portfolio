import test from 'node:test'
import assert from 'node:assert/strict'
import { createApi, errorMessage, friendlyError, ApiError, query } from '../src/api/client.js'
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
