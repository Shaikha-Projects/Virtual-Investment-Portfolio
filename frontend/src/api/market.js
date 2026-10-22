export function priceMovement(current, previous) {
  const currentPrice = Number(current)
  const previousPrice = Number(previous)
  if (previous == null || current == null || !Number.isFinite(currentPrice) || !Number.isFinite(previousPrice)) {
    return { direction: 'neutral', change: 0, hasPrevious: false }
  }
  const change = currentPrice - previousPrice
  return { direction: change > 0 ? 'up' : change < 0 ? 'down' : 'neutral', change, hasPrevious: true }
}

export function latestPriceHistory(records, count = 100) {
  if (!Array.isArray(records)) return []
  return records.filter(record => record && record.price != null && String(record.price).trim() !== '' && Number.isFinite(Number(record.price)) && Number(record.price) >= 0 && typeof record.recordedAt === 'string' && Number.isFinite(Date.parse(record.recordedAt)))
    .map(record => ({ price: Number(record.price), recordedAt: record.recordedAt, time: Date.parse(record.recordedAt) }))
    .sort((a, b) => a.time - b.time || a.recordedAt.localeCompare(b.recordedAt))
    .slice(-count)
}

export function historyScale(points) {
  const prices = points.map(point => point.price)
  const low = Math.min(...prices)
  const high = Math.max(...prices)
  const padding = high === low ? Math.max(high * 0.01, 0.01) : (high - low) * 0.12
  return { min: Math.max(0, low - padding), max: high + padding }
}
