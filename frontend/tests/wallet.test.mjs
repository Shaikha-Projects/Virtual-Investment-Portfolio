import test from 'node:test'
import assert from 'node:assert/strict'
import { createApi } from '../src/api/client.js'
import { checkoutUrl, clearCompletedWalletRequest, loadWalletRequest, saveWalletRequest, submitWalletRequest, updateWalletDeposit, walletAmount, walletNotification } from '../src/api/wallet.js'
import { createSseParser } from '../src/api/sse.js'
import { sectionFor, workspaceFor } from '../src/auth/roles.js'

const reference = 'f041d08b-415e-4c2c-b450-a9e6c94302e8'
const request = { key: 'cf15ee0b-6973-4cf9-9704-cc87f5319570', amount: '10.00' }
const storage = () => {
  const values = new Map()
  return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) }
}

test('wallet amounts require positive USD cents without exponent or extra precision', () => {
  assert.equal(walletAmount('10'), '10.00')
  assert.equal(walletAmount('0.01'), '0.01')
  for (const value of ['', '0', '-1', '10.001', '10.000', '1e2', 'NaN', 'Infinity', '10000000000000']) {
    assert.throws(() => walletAmount(value), /positive USD/)
  }
})

test('retry keys and wallet references survive redirects and stay scoped to the user and operation', () => {
  const saved = storage()
  saveWalletRequest(saved, 'owner@example.com', 'deposits', { ...request, reference })
  assert.deepEqual(loadWalletRequest(saved, 'owner@example.com', 'deposits'), { ...request, reference })
  assert.equal(loadWalletRequest(saved, 'other@example.com', 'deposits'), null)
  assert.equal(loadWalletRequest(saved, 'owner@example.com', 'withdrawals'), null)
  saveWalletRequest(saved, 'owner@example.com', 'deposits', null)
  assert.equal(loadWalletRequest(saved, 'owner@example.com', 'deposits'), null)
})

test('storage failures and corrupt references fail safely', () => {
  const broken = { getItem() { throw new Error() }, setItem() { throw new Error() } }
  assert.throws(() => loadWalletRequest(broken, 'owner', 'deposits'), /retry information/)
  assert.throws(() => saveWalletRequest(broken, 'owner', 'deposits', request), /session storage/)
  const saved = storage()
  saveWalletRequest(saved, 'owner', 'deposits', { ...request, reference: 'cs_test_session' })
  assert.throws(() => loadWalletRequest(saved, 'owner', 'deposits'), /retry information/)
})

test('deposit and withdrawal retries reuse identical headers and amounts', async () => {
  for (const type of ['deposits', 'withdrawals']) {
    const calls = []
    const api = async (path, options) => {
      calls.push([path, options])
      if (calls.length === 1) throw new Error('Unknown result')
      return { reference, status: type === 'deposits' ? 'PENDING' : 'COMPLETED' }
    }
    await assert.rejects(submitWalletRequest(api, type, request))
    await submitWalletRequest(api, type, request)
    assert.deepEqual(calls[0], calls[1])
    assert.equal(calls[0][0], `/portfolio/wallet/${type}`)
    assert.equal(calls[0][1].headers['Idempotency-Key'], request.key)
    assert.deepEqual(calls[0][1].body, { amount: 10 })
  }
})

test('verification and recovery use wallet UUIDs and preserve pending results', async () => {
  const calls = []
  const api = async (path, options) => { calls.push([path, options]); return { reference, status: 'PENDING' } }
  for (const action of ['verify', 'recover']) {
    const result = await updateWalletDeposit(api, reference, action)
    assert.equal(result.status, 'PENDING')
    assert.deepEqual(calls.at(-1), [`/portfolio/wallet/deposits/${reference}/${action}`, { method: 'POST' }])
  }
  await assert.rejects(updateWalletDeposit(api, 'cs_test_session', 'verify'), /wallet reference/)
  await assert.rejects(updateWalletDeposit(async () => ({ reference: 'other', status: 'COMPLETED' }), reference, 'verify'), /unexpected result/)
  await assert.rejects(updateWalletDeposit(async () => { throw new Error('Unavailable') }, reference, 'verify'), /Unavailable/)
})

test('only secure hosted Stripe checkout links are accepted', () => {
  assert.equal(checkoutUrl('https://checkout.stripe.com/c/pay/test'), 'https://checkout.stripe.com/c/pay/test')
  for (const value of [null, '', 'javascript:alert(1)', 'http://checkout.stripe.com/pay', 'https://checkout.stripe.com.evil.test/pay', 'https://evil.test/pay', 'https://user@checkout.stripe.com/pay']) {
    assert.throws(() => checkoutUrl(value), /secure checkout URL/)
  }
})

test('wallet requests retain JWT authentication and route to Investment without calling providers', async () => {
  const previous = globalThis.fetch
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push([url, options])
    return new Response(JSON.stringify({ reference, status: 'PENDING' }), { status: 201, headers: { 'Content-Type': 'application/json' } })
  }
  try {
    await submitWalletRequest(createApi({ token: 'test-token' }), 'deposits', request)
    assert.equal(calls[0][0], 'http://localhost:8085/portfolio/wallet/deposits')
    assert.equal(calls[0][1].headers.get('Authorization'), 'Bearer test-token')
    assert.equal(calls[0][1].headers.get('Idempotency-Key'), request.key)
  } finally { globalThis.fetch = previous }
})

test('cash wallet navigation is available only to investors', () => {
  assert.equal(sectionFor('USER', 'wallet'), 'wallet')
  assert.equal(sectionFor('ADMIN', 'wallet'), 'admin')
  assert.ok(workspaceFor('USER').navigation.some(([section, label]) => section === 'wallet' && label === 'Cash Wallet'))
})

test('completed wallet messages use simple deposit and withdrawal confirmations', () => {
  for (const type of ['deposit', 'withdrawal']) {
    const result = walletNotification(`Wallet ${type} completed: USD 10.00. Reference: ${reference}`, new Set())
    assert.equal(result.reference, reference)
    assert.equal(result.text, type === 'deposit' ? 'Deposit successful: USD 10.00.' : 'Withdrawal completed: USD 10.00.')
  }
})

test('pending, failed and malformed wallet messages never produce success notifications', () => {
  const seen = new Set()
  for (const text of [null, '', `Wallet deposit pending: USD 10.00. Reference: ${reference}`,
    `Wallet deposit failed: USD 10.00. Reference: ${reference}`, `Wallet withdrawal cancelled: USD 10.00. Reference: ${reference}`,
    `Wallet deposit completed: USD 0.00. Reference: ${reference}`, `Wallet deposit completed: USD -10.00. Reference: ${reference}`,
    `Wallet deposit completed: USD 10.001. Reference: ${reference}`, `Wallet deposit completed: EUR 10.00. Reference: ${reference}`,
    'Wallet deposit completed: USD 10.00. Reference: invalid']) {
    assert.equal(walletNotification(text, seen), null)
  }
  assert.equal(seen.size, 0)
})

test('wallet redelivery is deduplicated by operation even with different SSE ids and reconnects', () => {
  const seen = new Set()
  const items = []
  const receive = event => {
    if (event.event !== 'investment-notification') return
    const wallet = walletNotification(event.data, seen)
    if (wallet) items.push(wallet)
  }
  createSseParser(receive)(`event: investment-notification\nid: first\ndata: Wallet deposit completed: USD 10.00. Reference: ${reference}\n\n`)
  createSseParser(receive)(`event: investment-notification\nid: second\ndata: Wallet deposit completed: USD 10.00. Reference: ${reference.toUpperCase()}\n\n`)
  assert.equal(items.length, 1)
  const next = '8e40cdb8-c0a8-4a4f-8988-002500507c36'
  createSseParser(receive)(`event: investment-notification\nid: third\ndata: Wallet withdrawal completed: USD 20.00. Reference: ${next}\n\n`)
  assert.equal(items.length, 2)
  assert.equal(walletNotification(`Wallet deposit completed: USD 10.00. Reference: ${reference}`, new Set()).reference, reference)
})

test('completed deposits and withdrawals are not restored as retry requests after reload', () => {
  for (const type of ['deposits', 'withdrawals']) {
    const saved = storage()
    saveWalletRequest(saved, 'owner', type, { ...request, reference, status: 'COMPLETED' })
    assert.equal(loadWalletRequest(saved, 'owner', type), null)
    assert.equal(loadWalletRequest(saved, 'owner', type), null)
  }
})

test('completion clears only the matching deposit and preserves unrelated requests', () => {
  const saved = storage()
  saveWalletRequest(saved, 'owner', 'deposits', { ...request, reference, status: 'PENDING' })
  saveWalletRequest(saved, 'owner', 'withdrawals', request)
  saveWalletRequest(saved, 'other', 'deposits', { ...request, reference, status: 'PENDING' })
  assert.equal(clearCompletedWalletRequest(saved, 'owner', 'deposits', '8e40cdb8-c0a8-4a4f-8988-002500507c36'), false)
  assert.equal(loadWalletRequest(saved, 'owner', 'deposits').key, request.key)
  assert.equal(clearCompletedWalletRequest(saved, 'owner', 'deposits', reference), true)
  assert.equal(loadWalletRequest(saved, 'owner', 'deposits'), null)
  assert.deepEqual(loadWalletRequest(saved, 'owner', 'withdrawals'), request)
  assert.equal(loadWalletRequest(saved, 'other', 'deposits').key, request.key)
})

test('pending, failed and unknown requests retain their original keys and amounts', () => {
  for (const status of ['PENDING', 'FAILED', undefined]) {
    const saved = storage()
    const unresolved = { ...request, ...(status ? { status } : {}) }
    saveWalletRequest(saved, 'owner', 'deposits', unresolved)
    assert.deepEqual(loadWalletRequest(saved, 'owner', 'deposits'), unresolved)
    assert.equal(clearCompletedWalletRequest(saved, 'owner', 'deposits', reference), false)
    assert.deepEqual(loadWalletRequest(saved, 'owner', 'deposits'), unresolved)
  }
})

test('a timeout preserves retry state until the backend confirms completion', async () => {
  const saved = storage()
  saveWalletRequest(saved, 'owner', 'withdrawals', request)
  await assert.rejects(submitWalletRequest(async () => { throw new Error('Unknown result') }, 'withdrawals', request))
  assert.deepEqual(loadWalletRequest(saved, 'owner', 'withdrawals'), request)
  const result = await submitWalletRequest(async () => ({ reference, status: 'COMPLETED' }), 'withdrawals', request)
  assert.equal(result.status, 'COMPLETED')
  saveWalletRequest(saved, 'owner', 'withdrawals', null)
  assert.equal(loadWalletRequest(saved, 'owner', 'withdrawals'), null)
})

test('wallet forms reload empty after completion but keep unresolved retry controls', async () => {
  const { createServer } = await import('vite')
  const { createElement } = await import('react')
  const { renderToStaticMarkup } = await import('react-dom/server')
  const { fileURLToPath } = await import('node:url')
  const server = await createServer({ root: fileURLToPath(new URL('..', import.meta.url)),
    server: { middlewareMode: true, hmr: false, watch: null }, appType: 'custom' })
  const oldStorage = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage')
  const oldLocation = Object.getOwnPropertyDescriptor(globalThis, 'location')
  const saved = storage()
  Object.defineProperty(globalThis, 'sessionStorage', { value: saved, configurable: true })
  Object.defineProperty(globalThis, 'location', { value: { search: '?payment=success' }, configurable: true })
  try {
    const { default: WalletPage } = await server.ssrLoadModule('/src/pages/WalletPage.jsx')
    let calls = 0
    const props = { api: () => { calls += 1 }, user: { emailAddress: 'owner' }, revision: 0, refresh() {} }
    for (const type of ['deposits', 'withdrawals']) {
      saveWalletRequest(saved, 'owner', type, { ...request, reference, status: 'COMPLETED' })
    }
    const completed = renderToStaticMarkup(createElement(WalletPage, props))
    assert.doesNotMatch(completed, /Retry same request|Start a new transaction|Your updated balance and history are loading/)
    assert.match(completed, /name="wallet-deposits-amount"[^>]*value=""/)
    assert.match(completed, /name="wallet-withdrawals-amount"[^>]*value=""/)
    saveWalletRequest(saved, 'owner', 'deposits', { ...request, reference, status: 'PENDING' })
    saveWalletRequest(saved, 'owner', 'withdrawals', request)
    const pending = renderToStaticMarkup(createElement(WalletPage, props))
    assert.equal((pending.match(/Retry same request/g) || []).length, 2)
    assert.match(pending, /name="wallet-deposits-amount"[^>]*value="10.00"/)
    assert.match(pending, /name="wallet-withdrawals-amount"[^>]*value="10.00"/)
    assert.equal(calls, 0)
    assert.equal(loadWalletRequest(saved, 'owner', 'deposits').key, request.key)
  } finally {
    if (oldStorage) Object.defineProperty(globalThis, 'sessionStorage', oldStorage)
    else delete globalThis.sessionStorage
    if (oldLocation) Object.defineProperty(globalThis, 'location', oldLocation)
    else delete globalThis.location
    await server.close()
  }
})
