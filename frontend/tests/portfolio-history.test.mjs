import test from 'node:test'
import assert from 'node:assert/strict'
import { portfolioHistory, filterPortfolioHistory } from '../src/api/portfolioHistory.js'

test('portfolio history sorts all snapshots, preserving close and identical timestamps and API values', () => {
  const records = [
    { totalPortfolioValue: 101181.45, recordedAt: '2026-10-28T14:04:50.792688' },
    { totalPortfolioValue: '101211.21', recordedAt: '2026-10-28T14:04:50.792687' },
    { totalPortfolioValue: 0, recordedAt: '2026-10-28T14:04:50.792688' },
  ]
  const original = structuredClone(records)
  const points = portfolioHistory(records)
  assert.deepEqual(points.map(point => point.price), [101211.21, 101181.45, 0])
  assert.ok(points[0].time < points[1].time)
  assert.equal(points[1].time, points[2].time)
  assert.deepEqual(records, original)
  assert.equal(portfolioHistory(Array.from({ length: 200 }, () => records[0])).length, 200)
})

test('missing and invalid portfolio history produces no fabricated data; one snapshot stays one', () => {
  for (const input of [null, undefined, {}, []]) assert.deepEqual(portfolioHistory(input), [])
  const invalid = [null, {}, ...[null, '', ' ', true, [], {}, 'oops', Infinity, -1].map(totalPortfolioValue => ({ totalPortfolioValue, recordedAt: '2026-10-28T14:00:00' })), { totalPortfolioValue: 100, recordedAt: 'bad' }]
  assert.deepEqual(portfolioHistory(invalid), [])
  assert.equal(portfolioHistory([{ totalPortfolioValue: 100000, recordedAt: '2026-10-28T14:00:00' }]).length, 1)
})

test('1D, 1W, 1M and ALL filter actual timestamps with inclusive boundaries', () => {
  const now = Date.parse('2026-10-28T14:00:00Z'), day = 86400000
  const points = [31, 30, 8, 7, 2, 1, 0, -1].map(age => ({ time: now - age * day }))
  assert.equal(filterPortfolioHistory(points, '1D', now).length, 2)
  assert.equal(filterPortfolioHistory(points, '1W', now).length, 4)
  assert.equal(filterPortfolioHistory(points, '1M', now).length, 6)
  assert.equal(filterPortfolioHistory(points, 'ALL', now), points)
})
