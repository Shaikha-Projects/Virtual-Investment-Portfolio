import test from 'node:test'
import assert from 'node:assert/strict'
import { createTransactionFeedback } from '../src/api/transactionFeedback.js'

const message = 'Purchase completed: 3 shares of TSLA'
function setup(t) {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  let fallback = null
  const feedback = createTransactionFeedback(value => { fallback = value }, id => { if (fallback?.id === id) fallback = null })
  t.after(() => feedback.dispose())
  return { feedback, current: () => fallback }
}
test('SSE before or after a successful response suppresses the redundant success banner', async t => {
  const { feedback, current } = setup(t)
  await feedback.run(async () => { feedback.received(message) }, message, 'Success')
  t.mock.timers.tick(2000); assert.equal(current(), null)
  await feedback.run(async () => {}, message, 'Success')
  feedback.received(message)
  t.mock.timers.tick(2000); assert.equal(current(), null)
})
test('missing SSE shows a fallback; a late matching event clears it, unrelated events do not', async t => {
  const { feedback, current } = setup(t)
  await feedback.run(async () => {}, message, 'Purchase completed successfully.')
  t.mock.timers.tick(1499); assert.equal(current(), null)
  t.mock.timers.tick(1); assert.equal(current().text, 'Purchase completed successfully.')
  feedback.received('Limit BUY order executed: 3 shares of TSLA')
  assert.ok(current())
  feedback.received(message); assert.equal(current(), null)
})
test('failures and disposed requests never report successful operations', async t => {
  const { feedback, current } = setup(t)
  await assert.rejects(feedback.run(async () => { throw new Error('Rejected') }, message, 'Success'), /Rejected/)
  let complete
  const operation = feedback.run(() => new Promise(resolve => { complete = resolve }), message, 'Success')
  feedback.dispose(); complete(); await operation
  t.mock.timers.tick(5000); assert.equal(current(), null)
})
test('one event acknowledges only one of two identical operations', async t => {
  const { feedback, current } = setup(t)
  await feedback.run(async () => {}, message, 'First')
  await feedback.run(async () => {}, message, 'Second')
  feedback.received(message)
  t.mock.timers.tick(1500); assert.equal(current().text, 'Second')
  feedback.received(message); assert.equal(current(), null)
})
