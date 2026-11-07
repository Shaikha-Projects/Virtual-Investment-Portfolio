import test from 'node:test'
import assert from 'node:assert/strict'
import { alertCondition, priceAlertPayload, createPriceAlert, cancelPriceAlert, isPriceAlertNotification } from '../src/api/priceAlerts.js'
import { createApi } from '../src/api/client.js'

const assets = [{ assetId: 7, symbol: 'TEST', name: 'Test Asset', currentPrice: '100.00' }]
const values = { assetId: '7', targetPrice: '101.25', condition: 'ABOVE' }

test('price alert validation requires an asset, decimal target and correct condition direction', () => {
  assert.deepEqual(priceAlertPayload(values, assets), { assetId: 7, targetPrice: 101.25, condition: 'ABOVE' })
  assert.deepEqual(priceAlertPayload({ ...values, condition: 'BELOW', targetPrice: '99.99' }, assets), { assetId: 7, condition: 'BELOW', targetPrice: 99.99 })
  for (const assetId of ['', '999']) assert.throws(() => priceAlertPayload({ ...values, assetId }, assets), /Choose an asset/)
  for (const targetPrice of ['', ' ', 'NaN', 'Infinity', '-1', '0', '0.001', '1e3', 'abc']) assert.throws(() => priceAlertPayload({ ...values, targetPrice }, assets), /target price/)
  assert.throws(() => priceAlertPayload({ ...values, targetPrice: '100' }, assets), /higher/)
  assert.throws(() => priceAlertPayload({ ...values, condition: 'BELOW', targetPrice: '100' }, assets), /lower/)
  assert.throws(() => priceAlertPayload({ ...values, condition: 'invalid' }, assets), /condition/)
  assert.throws(() => priceAlertPayload(values, [{ ...assets[0], currentPrice: null }]), /unavailable/)
  assert.equal(priceAlertPayload({ ...values, targetPrice: '0.30' }, [{ ...assets[0], currentPrice: '0.29' }]).targetPrice, 0.3)
})

test('create and cancel use authenticated client with empty 201 and 204 responses', async () => {
  const original = globalThis.fetch
  const requests = []
  globalThis.fetch = async (url, options) => {
    requests.push({ url, options })
    return new Response(null, { status: options.method === 'POST' ? 201 : 204 })
  }
  try {
    const api = createApi({ token: 'test-jwt' })
    await createPriceAlert(api, priceAlertPayload(values, assets))
    await cancelPriceAlert(api, 12)
    assert.ok(requests[0].url.endsWith('/price-alerts'))
    assert.deepEqual(JSON.parse(requests[0].options.body), { assetId: 7, targetPrice: 101.25, condition: 'ABOVE' })
    assert.equal(requests[0].options.headers.get('Authorization'), 'Bearer test-jwt')
    assert.ok(requests[1].url.endsWith('/price-alerts/12/cancel'))
    assert.equal(requests[1].options.method, 'PATCH')
  } finally { globalThis.fetch = original }
})

test('backend validation and cancellation errors are shown safely', async () => {
  const original = globalThis.fetch
  try {
    for (const message of ['Target price must be higher than current price', 'Target price must be lower than current price', 'Only active alerts can be cancelled']) {
      globalThis.fetch = async () => new Response(JSON.stringify({ message }), { status: 400 })
      await assert.rejects(createPriceAlert(createApi(), values), { message })
    }
    globalThis.fetch = async () => new Response(JSON.stringify({ message: 'internal database details' }), { status: 500 })
    await assert.rejects(cancelPriceAlert(createApi(), 1), /temporarily unavailable/)
    globalThis.fetch = async () => new Response(JSON.stringify({ targetPrice: 'Target price must be greater than 0' }), { status: 400 })
    await assert.rejects(createPriceAlert(createApi(), values), /Target price must be greater than 0/)
  } finally { globalThis.fetch = original }
})

test('status labels and price alert SSE messages match the backend contract', () => {
  assert.equal(alertCondition('ABOVE'), 'At or above')
  assert.equal(alertCondition('BELOW'), 'At or below')
  assert.equal(alertCondition('INVALID'), '—')
  assert.equal(isPriceAlertNotification('Price alert triggered: AAPL reached $470.00 (target: $469.50)'), true)
  for (const text of ['Purchase completed: 1 shares of AAPL', 'Price alert triggered: invalid', '<script>']) assert.equal(isPriceAlertNotification(text), false)
})
