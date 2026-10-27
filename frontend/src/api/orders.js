import { ApiError } from './client.js'

// Verified mapping: AdminAssetController, not InvestmentOrderController.
export const EXECUTE_PENDING_PATH = '/admin/assets/execute-pending'

// HoldingResponse does not include reservations, so include every pending SELL page.
export async function loadSellHoldings(api, options) {
  const [holdings, first] = await Promise.all([
    api('/portfolio/holdings', options), api('/orders?page=0&size=10', options),
  ])
  const orders = [...first.content]
  for (let page = 1; page < first.totalPages; page++) {
    const next = await api(`/orders?page=${page}&size=10`, options)
    orders.push(...next.content)
  }
  return holdings.map(holding => {
    const reserved = Number(orders.filter(order => order.orderType === 'SELL' && order.status === 'PENDING' && String(order.assetId) === String(holding.assetId)).reduce((sum, order) => sum + Number(order.quantity), 0).toFixed(4))
    // Holdings use four decimal places; avoid floating-point subtraction artifacts.
    const available = Math.max(0, Number((Number(holding.quantity) - reserved).toFixed(4)))
    return { ...holding, reserved, available }
  })
}

export function validateSellQuantity(quantity, holding) {
  if (!holding || !(holding.available > 0) || Number(quantity) > holding.available) {
    throw new ApiError('Choose a holding and enter no more than its available shares.')
  }
}

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
