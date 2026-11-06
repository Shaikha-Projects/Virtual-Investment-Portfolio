import test from 'node:test'
import assert from 'node:assert/strict'
import { unrealizedReturn, holdingPerformers, formatReturn } from '../src/api/analytics.js'

const holding = currentValue => ({ quantity: '2', averageBuyPrice: '50', currentValue })

test('returns use cost basis for positive, negative, zero and total loss', () => {
  assert.equal(unrealizedReturn(holding(125)), 25)
  assert.equal(unrealizedReturn(holding(75)), -25)
  assert.equal(unrealizedReturn(holding(100)), 0)
  assert.equal(unrealizedReturn(holding(0)), -100)
})

test('missing, invalid, zero and negative cost basis produce unavailable returns', () => {
  assert.equal(unrealizedReturn(null), null)
  assert.equal(unrealizedReturn(undefined), null)
  for (const field of ['quantity', 'averageBuyPrice', 'currentValue']) {
    for (const value of [undefined, null, '', ' ', 'invalid', NaN, Infinity, -Infinity, true, [], {}]) {
      assert.equal(unrealizedReturn({ ...holding(120), [field]: value }), null)
    }
  }
  for (const field of ['quantity', 'averageBuyPrice']) {
    for (const value of [0, -1]) assert.equal(unrealizedReturn({ ...holding(120), [field]: value }), null)
  }
  assert.equal(unrealizedReturn({ quantity: 1e308, averageBuyPrice: 1e308, currentValue: 100 }), null)
  assert.equal(unrealizedReturn({ quantity: 1e-308, averageBuyPrice: 1, currentValue: 1e308 }), null)
})

test('ranking uses unrounded returns, excludes invalid holdings and preserves input', () => {
  const low = holding(100.001)
  const high = holding(100.002)
  const input = [low, {}, high]
  const original = structuredClone(input)
  const { best, worst } = holdingPerformers(input)
  assert.equal(best.holding, high)
  assert.equal(worst.holding, low)
  assert.equal(formatReturn(best.returnPercentage), formatReturn(worst.returnPercentage))
  assert.deepEqual(input, original)
  const loss = holding(75)
  assert.equal(holdingPerformers([holding(125), loss, holding(100)]).worst.holding, loss)
  assert.equal(holdingPerformers([holding(50), loss]).best.holding, loss)
})

test('one holding, ties, empty and wholly invalid holdings', () => {
  const only = holding(100)
  const result = holdingPerformers([only])
  assert.equal(result.best.holding, only)
  assert.equal(result.worst.holding, only)
  assert.equal(holdingPerformers([only, holding(100)]).best.holding, only)
  for (const input of [[], [{}], [null, holding(null)]]) {
    assert.deepEqual(holdingPerformers(input), { best: null, worst: null })
  }
  assert.deepEqual(holdingPerformers(), { best: null, worst: null })
})

test('percentage formatting includes two decimals, positive signs and unavailable indicators', () => {
  assert.equal(formatReturn(12.3456), '+12.35%')
  assert.equal(formatReturn(-12.3456), '-12.35%')
  assert.equal(formatReturn(0), '0.00%')
  assert.equal(formatReturn(-0), '0.00%')
  assert.equal(formatReturn(-0.001), '0.00%')
  assert.equal(formatReturn(0.001), '+0.00%')
  for (const value of [null, undefined, NaN, Infinity, -Infinity, '12']) assert.equal(formatReturn(value), '—')
})
