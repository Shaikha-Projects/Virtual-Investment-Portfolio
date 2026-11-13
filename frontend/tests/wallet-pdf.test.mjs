import test from 'node:test'
import assert from 'node:assert/strict'
import { createApi, ApiError } from '../src/api/client.js'
import { canDownloadReceipt, downloadWalletReceipt, downloadWalletStatement, walletStatementPath } from '../src/api/wallet.js'

const reference = 'f041d08b-415e-4c2c-b450-a9e6c94302e8'

test('receipt controls are limited to completed deposits and withdrawals', () => {
  for (const operationType of ['DEPOSIT', 'WITHDRAWAL']) {
    assert.equal(canDownloadReceipt({ operationType, status: 'COMPLETED' }), true)
    for (const status of ['PENDING', 'FAILED', 'CANCELLED', null]) assert.equal(canDownloadReceipt({ operationType, status }), false)
  }
  assert.equal(canDownloadReceipt(null), false)
  assert.equal(canDownloadReceipt({ operationType: 'BUY', status: 'COMPLETED' }), false)
})

test('statement dates are required, real calendar dates and limited to 366 inclusive days', () => {
  assert.equal(walletStatementPath('2026-11-01', '2026-11-30'), '/portfolio/wallet/statement?from=2026-11-01&to=2026-11-30')
  assert.ok(walletStatementPath('2024-01-01', '2024-12-31'))
  assert.ok(walletStatementPath('2026-11-12', '2026-11-12'))
  for (const dates of [['', '2026-11-30'], ['2026-11-30', '2026-11-01'], ['2026-02-30', '2026-03-01'],
    ['2024-01-01', '2025-01-01'], ['1900-02-29', '1900-03-01'], ['bad', 'bad']]) {
    assert.throws(() => walletStatementPath(...dates), /valid statement range/)
  }
})

test('PDF requests use the existing Investment routing and JWT header', async () => {
  const previous = globalThis.fetch
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push([url, options])
    return new Response('%PDF-test', { status: 200, headers: { 'Content-Type': 'application/pdf' } })
  }
  try {
    const api = createApi({ token: 'test-token' })
    const blob = await api(`/portfolio/wallet/operations/${reference}/receipt`, { responseType: 'blob' })
    assert.equal(await blob.text(), '%PDF-test')
    assert.equal(calls[0][0], `http://localhost:8085/portfolio/wallet/operations/${reference}/receipt`)
    assert.equal(calls[0][1].headers.get('Authorization'), 'Bearer test-token')
  } finally { globalThis.fetch = previous }
})

test('PDF errors retain normal API handling and never return an error body as a file', async () => {
  const previous = globalThis.fetch
  try {
    globalThis.fetch = async () => new Response(JSON.stringify({ message: 'Receipts are available only for completed wallet operations' }),
      { status: 400, headers: { 'Content-Type': 'application/json' } })
    await assert.rejects(createApi()('/portfolio/wallet/statement', { responseType: 'blob' }),
      error => error instanceof ApiError && error.status === 400 && error.message.includes('completed wallet operations'))
    globalThis.fetch = async () => new Response('<html>not a PDF</html>', { headers: { 'Content-Type': 'text/html' } })
    await assert.rejects(createApi()('/portfolio/wallet/statement', { responseType: 'blob' }), /unexpected PDF/)
  } finally { globalThis.fetch = previous }
})

test('receipt and statement downloads use safe filenames and clean up browser resources', async () => {
  const previous = { document: globalThis.document, create: URL.createObjectURL, revoke: URL.revokeObjectURL, timeout: globalThis.setTimeout }
  const links = []
  const revoked = []
  const timers = []
  const paths = []
  globalThis.document = { body: { appendChild() {} }, createElement: () => {
    const link = { click() { this.clicked = true }, remove() { this.removed = true } }
    links.push(link)
    return link
  } }
  URL.createObjectURL = () => 'blob:wallet-test'
  URL.revokeObjectURL = url => revoked.push(url)
  globalThis.setTimeout = callback => { timers.push(callback) }
  const api = async (path, options) => { paths.push([path, options]); return new Blob(['%PDF-test'], { type: 'application/pdf' }) }
  try {
    await downloadWalletReceipt(api, reference)
    await downloadWalletStatement(api, '2026-11-01', '2026-11-30')
    assert.equal(links[0].download, `wallet-receipt-${reference}.pdf`)
    assert.equal(links[1].download, 'wallet-statement-2026-11-01-to-2026-11-30.pdf')
    assert.ok(links.every(link => link.clicked && link.removed))
    assert.deepEqual(paths[0], [`/portfolio/wallet/operations/${reference}/receipt`, { responseType: 'blob' }])
    assert.deepEqual(paths[1], ['/portfolio/wallet/statement?from=2026-11-01&to=2026-11-30', { responseType: 'blob' }])
    timers.forEach(callback => callback())
    assert.equal(revoked.length, 2)
    assert.throws(() => downloadWalletReceipt(api, '../private'), /wallet reference/)
    await assert.rejects(downloadWalletReceipt(async () => { throw new ApiError('Unavailable') }, reference), /Unavailable/)
    await assert.rejects(downloadWalletReceipt(async () => new Blob(['not a PDF']), reference), /unexpected PDF/)
    assert.equal(links.length, 2)
  } finally {
    if (previous.document === undefined) delete globalThis.document
    else globalThis.document = previous.document
    URL.createObjectURL = previous.create; URL.revokeObjectURL = previous.revoke
    globalThis.setTimeout = previous.timeout
  }
})
