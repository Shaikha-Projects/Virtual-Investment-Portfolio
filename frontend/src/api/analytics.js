export const allocationColors = ['#10B981', '#38BDF8', '#A78BFA', '#FBBF24', '#60A5FA', '#F472B6', '#94A3B8']

// Presentation-only analytics from current positions; never a historical series.
export function allocation(holdings = []) {
  const positions = holdings.filter(item => Number.isFinite(Number(item.currentValue)) && Number(item.currentValue) > 0)
    .slice().sort((a, b) => Number(b.currentValue) - Number(a.currentValue))
  const total = positions.reduce((sum, item) => sum + Number(item.currentValue), 0)
  let offset = 0
  return positions.map((item, index) => {
    const percentage = Number(item.currentValue) / total * 100
    const row = { ...item, percentage, offset, color: allocationColors[index % allocationColors.length] }
    offset += percentage
    return row
  })
}

export function positionGain(holding) {
  return Number(holding.currentValue) - Number(holding.quantity) * Number(holding.averageBuyPrice)
}

export function unrealizedReturn(holding) {
  const values = [holding?.quantity, holding?.averageBuyPrice, holding?.currentValue]
  if (!values.every(value => (typeof value === 'number' || typeof value === 'string' && value.trim() !== '') && Number.isFinite(Number(value)))) return null
  const [quantity, averageBuyPrice, currentValue] = values.map(Number)
  const costBasis = quantity * averageBuyPrice
  if (!Number.isFinite(costBasis) || costBasis <= 0) return null
  const result = (currentValue - costBasis) / costBasis * 100
  return Number.isFinite(result) ? result : null
}

export function holdingPerformers(holdings = []) {
  let best = null
  let worst = null
  for (const holding of holdings) {
    const returnPercentage = unrealizedReturn(holding)
    if (returnPercentage === null) continue
    const position = { holding, returnPercentage }
    if (!best || returnPercentage > best.returnPercentage) best = position
    if (!worst || returnPercentage < worst.returnPercentage) worst = position
  }
  return { best, worst }
}

export function formatReturn(value) {
  if (!Number.isFinite(value)) return '—'
  const rounded = value.toFixed(2)
  return `${value > 0 ? '+' : ''}${rounded === '-0.00' ? '0.00' : rounded}%`
}

export function percentage(value, total) {
  return Number(total) > 0 ? Number(value) / Number(total) * 100 : 0
}
