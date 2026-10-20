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

export function percentage(value, total) {
  return Number(total) > 0 ? Number(value) / Number(total) * 100 : 0
}
