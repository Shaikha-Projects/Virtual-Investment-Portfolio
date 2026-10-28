import { useMemo, useState } from 'react'
import { portfolioHistory, filterPortfolioHistory, HISTORY_WINDOWS } from '../api/portfolioHistory'
import { useResource } from '../hooks/useResource'
import { Resource } from './UI'
import PriceHistoryChart from './PriceHistoryChart'

export default function PortfolioHistory({ api, revision }) {
  const history = useResource(api, '/portfolio/history', revision)
  const [range, setRange] = useState('ALL')
  const points = useMemo(() => filterPortfolioHistory(portfolioHistory(history.data), range), [history.data, range])
  return <article className="card portfolio-history-panel">
    <div className="panel-heading"><div><span className="eyebrow">Recorded snapshots</span><h3>Portfolio Performance</h3></div><div className="actions" role="group" aria-label="Performance time range">{Object.keys(HISTORY_WINDOWS).map(option => <button type="button" key={option} className={range === option ? '' : 'secondary'} aria-pressed={range === option} onClick={() => setRange(option)}>{option}</button>)}</div></div>
    <Resource resource={history} empty={!points.length} emptyText={range === 'ALL' ? 'No portfolio history is available yet.' : 'No portfolio snapshots in this time range.'}>
      {points.length > 0 && <PriceHistoryChart key={range} points={points} symbol="Portfolio" valueLabel="portfolio value" showDates />}
    </Resource>
    <p className="muted history-caption">Actual recorded portfolio values in USD. 1D, 1W and 1M show the past 24 hours, 7 days and 30 days. {points.length === 1 && 'Only one snapshot is available for this range.'}</p>
  </article>
}
