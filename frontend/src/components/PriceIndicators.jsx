import { priceMovement } from '../api/market'
import { money } from '../api/format'

export function PriceMovement({ current, previous }) {
  const movement = priceMovement(current, previous)
  const label = !movement.hasPrevious ? 'Awaiting the next price update' : movement.direction === 'neutral' ? 'Unchanged since the previous update' : `Price ${movement.direction === 'up' ? 'increased' : 'decreased'} by ${money(Math.abs(movement.change))} since the previous update`
  return <span className={`price-movement ${movement.direction === 'up' ? 'positive' : movement.direction === 'down' ? 'negative' : 'muted'}`} title={label} aria-label={label}><span aria-hidden="true">{movement.direction === 'up' ? '↑' : movement.direction === 'down' ? '↓' : '—'}</span>{movement.direction === 'neutral' ? movement.hasPrevious ? 'Unchanged' : 'No comparison yet' : money(Math.abs(movement.change))}</span>
}

export function MarketStatus({ resource }) {
  return <span className={`market-live ${resource.error ? 'market-delayed' : ''}`} title={resource.updatedAt ? `Last successful update: ${new Date(resource.updatedAt).toLocaleTimeString()}. Refreshes every 10 seconds.` : 'Connecting to market prices'}><span className="live-dot" />{resource.error ? 'Prices delayed' : resource.loading ? 'Connecting to market' : 'Live Market'}</span>
}
