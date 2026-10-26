import test from 'node:test'
import assert from 'node:assert/strict'
import { createApi, errorMessage } from '../src/api/client.js'
import { EXECUTE_PENDING_PATH, limitOrderPayload, orderAssetLabel, placeLimitOrder, cancelLimitOrder, executePendingOrders } from '../src/api/orders.js'
import { sectionFor } from '../src/auth/roles.js'

const assets = [{ assetId: 42, symbol: 'TEST', name: 'Test asset' }]
const valid = { assetId: '42', orderType: 'BUY', quantity: '5', limitPrice: '12' }

test('limit order payload matches verified DTO and supports BUY and SELL', () => {
  assert.deepEqual(limitOrderPayload(valid, assets), { assetId: 42, orderType: 'BUY', quantity: 5, limitPrice: 12 })
  assert.deepEqual(limitOrderPayload({ ...valid, orderType: 'SELL', quantity: '0.01', limitPrice: '0.01' }, assets), { assetId: 42, orderType: 'SELL', quantity: 0.01, limitPrice: 0.01 })
})

test('order validation rejects unknown assets and order types', () => {
  for (const assetId of ['', '999', 'not-an-id']) assert.throws(() => limitOrderPayload({ ...valid, assetId }, assets), /Choose an available asset/)
  for (const orderType of ['', 'HOLD', 'buy']) assert.throws(() => limitOrderPayload({ ...valid, orderType }, assets), /BUY or SELL/)
})

test('quantity and limit price validation match backend minimum and reject nonfinite values', () => {
  for (const field of ['quantity', 'limitPrice']) {
    for (const value of ['', '0', '-1', '0.001', 'Infinity', 'NaN']) {
      assert.throws(() => limitOrderPayload({ ...valid, [field]: value }, assets), /at least/)
    }
  }
  assert.throws(() => limitOrderPayload({ ...valid, quantity: '1e308', limitPrice: '1e308' }, assets), /too large/)
})

test('asset lookup uses existing symbols or an explicit ID fallback', () => {
  assert.equal(orderAssetLabel({ assetId: 42 }, assets), 'TEST')
  assert.equal(orderAssetLabel({ assetId: 99 }, assets), 'Asset #99')
  assert.equal(orderAssetLabel({ assetId: 42 }), 'Asset #42')
})

test('GET orders preserves paginated metadata, server ordering and bearer authentication', async t => {
  const rows = [{ id: 8, assetId: 42, orderType: 'BUY', status: 'PENDING' }, { id: 3, assetId: 42, orderType: 'SELL', status: 'FILLED' }]
  const page = { content: rows, totalElements: 12, totalPages: 2, number: 1, size: 10, first: false, last: true }
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(new URL(url).search, '?page=1&size=10')
    assert.equal(options.headers.get('Authorization'), 'Bearer test-token')
    return new Response(JSON.stringify(page))
  })
  assert.deepEqual(await createApi({ token: 'test-token' })('/orders?page=1&size=10'), page)
})

test('create and cancel use bearer JWTs and accept verified plain-text success responses', async t => {
  const calls = []
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(options.headers.get('Authorization'), 'Bearer test-token')
    assert.equal(options.responseType, undefined)
    calls.push({ path: new URL(url).pathname, method: options.method, body: options.body })
    return new Response('Operation succeeded', { headers: { 'Content-Type': 'text/plain' } })
  })
  const api = createApi({ token: 'test-token' })
  await placeLimitOrder(api, limitOrderPayload(valid, assets))
  await cancelLimitOrder(api, 8)
  assert.deepEqual(calls.map(call => [call.path, call.method]), [['/orders', 'POST'], ['/orders/8/cancel', 'PATCH']])
  assert.deepEqual(JSON.parse(calls[0].body), { assetId: 42, orderType: 'BUY', quantity: 5, limitPrice: 12 })
  assert.equal(calls[1].body, undefined)
})

test('execution uses the actual existing admin controller mapping', async t => {
  assert.equal(EXECUTE_PENDING_PATH, '/admin/assets/execute-pending')
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(new URL(url).pathname, '/admin/assets/execute-pending')
    assert.equal(options.method, 'POST')
    assert.equal(options.body, undefined)
    return new Response('Pending orders processed successfully')
  })
  await executePendingOrders(createApi({ token: 'test-token' }))
  assert.equal(sectionFor('USER', 'orders'), 'orders')
  assert.equal(sectionFor('ADMIN', 'orders'), 'admin')
})

test('text-response mutations still sanitize errors and display known reservation rules', async t => {
  t.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({ message: 'Only pending orders can be cancelled' }), { status: 400 }))
  await assert.rejects(cancelLimitOrder(createApi({ token: 'test-token' }), 8), /Only pending orders can be cancelled/)
  for (const message of ['Insufficient available cash for this order', 'Insufficient available shares for this order', 'You do not own this asset', 'Asset is not available for trading']) {
    assert.equal(errorMessage(400, { message }), message)
  }
  assert.doesNotMatch(errorMessage(500, { message: 'java.sql.Exception: private details' }), /java|private/)
})

test('an aborted text response cannot publish into a subsequent session', async t => {
  const controller = new AbortController()
  t.mock.method(globalThis, 'fetch', async () => ({ ok: true, status: 200, text: async () => { controller.abort(); return 'Operation succeeded' } }))
  await assert.rejects(placeLimitOrder(createApi({ token: 'test-token', signal: controller.signal }), limitOrderPayload(valid, assets)), error => error.name === 'AbortError')
})
