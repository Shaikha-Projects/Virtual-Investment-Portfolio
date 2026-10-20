import test from 'node:test'
import assert from 'node:assert/strict'
import { allocation, percentage, positionGain } from '../src/api/analytics.js'

test('allocation uses actual market values, ordered by value, with contiguous donut segments', () => {
  const input = [
    { holdingId: 1, currentValue: '200', assetSymbol: 'SECOND' },
    { holdingId: 2, currentValue: '300', assetSymbol: 'FIRST' },
  ]
  const rows = allocation(input)
  assert.deepEqual(rows.map(row => [row.assetSymbol, row.percentage, row.offset]), [
    ['FIRST', 60, 0], ['SECOND', 40, 60],
  ])
  assert.equal(rows.reduce((sum, row) => sum + row.percentage, 0), 100)
  assert.equal(input[0].assetSymbol, 'SECOND')
})

test('empty and nonpositive positions produce no fabricated allocation', () => {
  assert.deepEqual(allocation([]), [])
  assert.deepEqual(allocation([{ currentValue: 0 }, { currentValue: -10 }, { currentValue: 'invalid' }]), [])
  assert.equal(percentage(0, 0), 0)
})

test('display gains and composition percentages derive from current backend fields', () => {
  assert.equal(positionGain({ currentValue: '300', quantity: '3', averageBuyPrice: '90' }), 30)
  assert.equal(positionGain({ currentValue: '200', quantity: '2', averageBuyPrice: '120' }), -40)
  assert.equal(percentage(500, 10000), 5)
  assert.equal(percentage(9500, 10000), 95)
})
