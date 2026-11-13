import { ApiError } from './client.js'

const referencePattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function walletAmount(value) {
  const text = String(value).trim()
  if (!/^\d{1,13}(\.\d{1,2})?$/.test(text) || Number(text) <= 0) {
    throw new ApiError('Enter a positive USD amount with at most two decimal places.')
  }
  return Number(text).toFixed(2)
}

function storageKey(user, type) {
  return `wallet:${encodeURIComponent(user)}:${type}`
}

export function loadWalletRequest(storage, user, type) {
  try {
    const text = storage.getItem(storageKey(user, type))
    if (!text) return null
    const request = JSON.parse(text)
    if (!referencePattern.test(request.key) || walletAmount(request.amount) !== request.amount
        || (request.reference && !referencePattern.test(request.reference))) throw new Error()
    if (request.status === 'COMPLETED') {
      storage.removeItem(storageKey(user, type))
      return null
    }
    return request
  } catch {
    throw new ApiError('Wallet retry information is unavailable. Check your history before starting a new transaction.')
  }
}

export function saveWalletRequest(storage, user, type, request) {
  try {
    if (request) storage.setItem(storageKey(user, type), JSON.stringify(request))
    else storage.removeItem(storageKey(user, type))
  } catch {
    throw new ApiError('Allow session storage before continuing. Your payment has not been confirmed.')
  }
}

export function clearCompletedWalletRequest(storage, user, type, reference) {
  const request = loadWalletRequest(storage, user, type)
  if (!request || request.reference !== reference) return false
  saveWalletRequest(storage, user, type, null)
  return true
}

export async function submitWalletRequest(api, type, request) {
  if (!['deposits', 'withdrawals'].includes(type)) throw new ApiError('Invalid wallet operation.')
  const result = await api(`/portfolio/wallet/${type}`, {
    method: 'POST', headers: { 'Idempotency-Key': request.key }, body: { amount: Number(walletAmount(request.amount)) },
  })
  if (!referencePattern.test(result?.reference) || !['PENDING', 'COMPLETED'].includes(result.status)
      || (type === 'withdrawals' && result.status !== 'COMPLETED')) throw new ApiError('The wallet returned an unexpected result. Retry the same request or check your history.')
  return result
}

export async function updateWalletDeposit(api, reference, action) {
  if (!referencePattern.test(reference) || !['verify', 'recover'].includes(action)) throw new ApiError('Invalid wallet reference.')
  const result = await api(`/portfolio/wallet/deposits/${reference}/${action}`, { method: 'POST' })
  if (result?.reference !== reference || !['PENDING', 'COMPLETED', 'FAILED', 'CANCELLED'].includes(result.status)) {
    throw new ApiError('The wallet returned an unexpected result. No payment has been confirmed.')
  }
  return result
}

export function checkoutUrl(value) {
  try {
    const url = new URL(value)
    if (url.protocol === 'https:' && url.hostname === 'checkout.stripe.com' && !url.username && !url.password && !url.port) return url.href
  } catch { /* reject unexpected checkout links */ }
  throw new ApiError('A secure checkout URL is not available. Recover this deposit or check its payment status.')
}

export function walletNotification(text, seen) {
  if (typeof text !== 'string') return null
  const match = /^Wallet (deposit|withdrawal) completed: USD (\d{1,13}\.\d{2})\. Reference: ([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$/.exec(text)
  if (!match || Number(match[2]) <= 0) return null
  const reference = match[3].toLowerCase()
  if (seen.has(reference)) return null
  seen.add(reference)
  return { reference, text: `${match[1] === 'deposit' ? 'Deposit successful' : 'Withdrawal completed'}: USD ${match[2]}.` }
}

export function canDownloadReceipt(operation) {
  return operation?.status === 'COMPLETED' && ['DEPOSIT', 'WITHDRAWAL'].includes(operation.operationType)
}

export function walletStatementPath(from, to) {
  const valid = value => /^\d{4}-\d{2}-\d{2}$/.test(value) && Number(value.slice(0, 4)) >= 1900
    && Number.isFinite(Date.parse(`${value}T00:00:00Z`)) && new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value
  if (!valid(from) || !valid(to) || from > to || (Date.parse(to) - Date.parse(from)) / 86400000 > 365) {
    throw new ApiError('Choose a valid statement range of at most 366 days')
  }
  return `/portfolio/wallet/statement?${new URLSearchParams({ from, to })}`
}

async function downloadWalletPdf(api, path, filename) {
  const blob = await api(path, { responseType: 'blob' })
  if (!(blob instanceof Blob) || await blob.slice(0, 5).text() !== '%PDF-') {
    throw new ApiError('The service returned an unexpected PDF. Please try again.')
  }
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  try {
    link.href = url; link.download = filename
    document.body.appendChild(link)
    link.click()
  } finally {
    link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
}

export function downloadWalletReceipt(api, reference) {
  if (!referencePattern.test(reference)) throw new ApiError('Invalid wallet reference.')
  return downloadWalletPdf(api, `/portfolio/wallet/operations/${reference}/receipt`, `wallet-receipt-${reference.toLowerCase()}.pdf`)
}

export function downloadWalletStatement(api, from, to) {
  return downloadWalletPdf(api, walletStatementPath(from, to), `wallet-statement-${from}-to-${to}.pdf`)
}
