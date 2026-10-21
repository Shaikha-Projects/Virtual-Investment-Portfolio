import { ApiError } from './client.js'

// Verified mapping: AdminAssetController, not InvestmentOrderController.
export const EXECUTE_PENDING_PATH = '/admin/assets/execute-pending'

export function limitOrderPayload(values, assets) {
  const asset = assets.find(item => String(item.assetId) === String(values.assetId))
  if (!asset || !Number.isSafeInteger(Number(asset.assetId)) || Number(asset.assetId) <= 0) {
    throw new ApiError('Choose an available asset for your order.')
  }
  if (!['BUY', 'SELL'].includes(values.orderType)) throw new ApiError('Choose a BUY or SELL order.')
  const quantity = Number(values.quantity)
  const limitPrice = Number(values.limitPrice)
  // CreateOrderRequest enforces DecimalMin("0.01") on both fields.
  if (!Number.isFinite(quantity) || quantity < 0.01) throw new ApiError('Enter a quantity of at least 0.01.')
  if (!Number.isFinite(limitPrice) || limitPrice < 0.01) throw new ApiError('Enter a limit price of at least $0.01.')
  if (!Number.isFinite(quantity * limitPrice)) throw new ApiError('The order value is too large. Reduce the quantity or limit price.')
  return { assetId: Number(asset.assetId), orderType: values.orderType, quantity, limitPrice }
}

export function orderAssetLabel(order, assets = []) {
  return assets.find(asset => String(asset.assetId) === String(order.assetId))?.symbol || `Asset #${order.assetId}`
}

export const placeLimitOrder = (api, payload) => api('/orders', { method: 'POST', body: payload, responseType: 'text' })
export const cancelLimitOrder = (api, id) => api(`/orders/${encodeURIComponent(id)}/cancel`, { method: 'PATCH', responseType: 'text' })
export const executePendingOrders = api => api(EXECUTE_PENDING_PATH, { method: 'POST', responseType: 'text' })
