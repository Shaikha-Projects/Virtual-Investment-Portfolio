import test from 'node:test'
import assert from 'node:assert/strict'
import { historyScale, latestPriceHistory, priceMovement } from '../src/api/market.js'

test('movement compares consecutive real prices and handles first/unchanged snapshots neutrally', () => {
  assert.deepEqual(priceMovement(103, 100), { direction: 'up', change: 3, hasPrevious: true })
  assert.deepEqual(priceMovement(101, 103), { direction: 'down', change: -2, hasPrevious: true })
  assert.deepEqual(priceMovement(101, 101), { direction: 'neutral', change: 0, hasPrevious: true })
  assert.deepEqual(priceMovement(101, undefined), { direction: 'neutral', change: 0, hasPrevious: false })
  assert.equal(priceMovement('invalid', 100).hasPrevious, false)
})

test('history displays only the latest 100 actual records in chronological order without mutating the response', () => {
  const records = Array.from({ length: 125 }, (_, index) => ({ price: 100 + index, recordedAt: new Date(Date.UTC(2026, 9, 21) + index * 10000).toISOString() })).reverse()
  const firstOriginal = records[0]
  const points = latestPriceHistory(records)
  assert.equal(points.length, 100)
  assert.equal(points[0].price, 125)
  assert.equal(points[99].price, 224)
  assert.ok(points.every((point, index) => !index || point.time >= points[index - 1].time))
  assert.equal(records[0], firstOriginal)
  assert.equal(points[99].recordedAt, firstOriginal.recordedAt)
})

test('empty or invalid history never creates fake samples', () => {
  assert.deepEqual(latestPriceHistory([]), [])
  assert.deepEqual(latestPriceHistory(null), [])
  assert.deepEqual(latestPriceHistory([{ price: 10, recordedAt: 'invalid' }, { price: null, recordedAt: '2026-10-21T12:00:00' }, { price: 'NaN', recordedAt: '2026-10-21T12:00:00' }]), [])
})

test('microsecond timestamps preserve order and exact recorded values', () => {
  const points = latestPriceHistory([{ price: '347.48', recordedAt: '2026-10-21T21:23:08.353372' }, { price: '348.64', recordedAt: '2026-10-21T21:22:58.301199' }])
  assert.deepEqual(points.map(point => point.price), [348.64, 347.48])
  assert.equal(points[0].recordedAt, '2026-10-21T21:22:58.301199')
})

test('single-point and flat prices have a nonzero chart scale without inventing records', () => {
  const scale = historyScale([{ price: 100 }, { price: 100 }])
  assert.ok(scale.min < 100 && scale.max > 100)
  const zero = historyScale([{ price: 0 }])
  assert.equal(zero.min, 0)
  assert.ok(zero.max > 0)
})
