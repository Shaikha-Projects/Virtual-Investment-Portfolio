import { ApiError } from './client.js'

export const alertCondition = condition => condition === 'ABOVE' ? 'At or above' : condition === 'BELOW' ? 'At or below' : '—'

// Compare decimal strings exactly, without calculating financial totals.
function decimal(value) {
  const text = String(value ?? '').trim()
  if (!/^\d+(?:\.\d+)?$/.test(text)) return null
  const [whole, fraction = ''] = text.split('.')
  return { units: BigInt(whole + fraction), scale: fraction.length }
}
function compare(a, b) {
  const scale = Math.max(a.scale, b.scale)
  const left = a.units * 10n ** BigInt(scale - a.scale)
  const right = b.units * 10n ** BigInt(scale - b.scale)
  return left > right ? 1 : left < right ? -1 : 0
}

export function priceAlertPayload(values, assets) {
  const asset = assets.find(item => String(item.assetId) === String(values.assetId))
  if (!asset) throw new ApiError('Choose an asset.')
  const target = decimal(values.targetPrice)
  if (!target || compare(target, decimal('0.01')) < 0 || !Number.isFinite(Number(values.targetPrice))) throw new ApiError('Enter a target price of at least $0.01.')
  const current = decimal(asset.currentPrice)
  if (!current) throw new ApiError('The current asset price is unavailable. Refresh assets and try again.')
  if (!['ABOVE', 'BELOW'].includes(values.condition)) throw new ApiError('Choose an alert condition.')
  const difference = compare(target, current)
  if (values.condition === 'ABOVE' && difference <= 0) throw new ApiError('Target price must be higher than current price')
  if (values.condition === 'BELOW' && difference >= 0) throw new ApiError('Target price must be lower than current price')
  return { assetId: asset.assetId, targetPrice: Number(values.targetPrice), condition: values.condition }
}

export const createPriceAlert = (api, body) => api('/price-alerts', { method: 'POST', body, responseType: 'text' })
export const cancelPriceAlert = (api, id) => api(`/price-alerts/${encodeURIComponent(id)}/cancel`, { method: 'PATCH', responseType: 'text' })

export const isPriceAlertNotification = text => /^Price alert triggered: [A-Za-z0-9.^_/-]{1,30} reached \$\d+(?:\.\d+)? \(target: \$\d+(?:\.\d+)?\)$/.test(text)
