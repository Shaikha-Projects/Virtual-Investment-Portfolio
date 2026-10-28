const DAY = 24 * 60 * 60 * 1000
export const HISTORY_WINDOWS = { '1D': DAY, '1W': 7 * DAY, '1M': 30 * DAY, ALL: Infinity }

export function portfolioHistory(records) {
  if (!Array.isArray(records)) return []
  return records.flatMap(record => {
    const value = record?.totalPortfolioValue
    const stamp = record?.recordedAt
    if (!['number', 'string'].includes(typeof value) || String(value).trim() === '' || !Number.isFinite(Number(value)) || Number(value) < 0 || typeof stamp !== 'string') return []
    const milliseconds = Date.parse(stamp)
    if (!Number.isFinite(milliseconds)) return []
    // Date.parse truncates sub-millisecond precision from PostgreSQL timestamps.
    const fraction = stamp.match(/\.(\d+)/)?.[1] || ''
    const subMillisecond = Number(`0.${fraction.padEnd(3, '0').slice(3) || '0'}`)
    return [{ price: Number(value), recordedAt: stamp, time: milliseconds + subMillisecond }]
  }).sort((a, b) => a.time - b.time || a.recordedAt.localeCompare(b.recordedAt))
}

export function filterPortfolioHistory(points, range, now = Date.now()) {
  if (range === 'ALL') return points
  const duration = HISTORY_WINDOWS[range]
  return points.filter(point => point.time >= now - duration && point.time <= now)
}
