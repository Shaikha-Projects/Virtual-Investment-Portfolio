import { useResource } from '../hooks/useResource'
import { allocation, percentage, positionGain } from '../api/analytics'
import { date, money } from '../api/format'
import { Resource } from '../components/UI'
import Icon from '../components/Icon'
import PortfolioHistory from '../components/PortfolioHistory'

function metric(value) {
  const amount = value == null || String(value).trim() === '' ? NaN : Number(value)
  return { amount, available: Number.isFinite(amount), tone: Number.isFinite(amount) && amount !== 0 ? amount < 0 ? 'negative' : 'positive' : '' }
}

function ReturnCard({ label, value, detail, icon, color = true }) {
  const { amount, available, tone } = metric(value)
  return <article className="summary-card kpi-card"><div className="kpi-heading"><span>{label}</span><span className="icon-tile"><Icon name={icon || (available && amount < 0 ? 'down' : 'up')} size={18} /></span></div><h3 className={color ? tone : ''}>{available ? money(amount) : '—'}</h3><p>{detail}</p></article>
}

function OverallReturn({ data }) {
  const gain = metric(data.totalGainLoss), percent = metric(data.totalReturnPercentage)
  return <div className="gain-pill overall-return"><span>Overall Return:</span><strong className={gain.tone}>{gain.available ? (gain.amount > 0 ? '+' : '') + money(gain.amount) : '—'}</strong><strong className={percent.tone}>({percent.available ? (percent.amount > 0 ? '+' : '') + percent.amount.toFixed(2) + '%' : '—'})</strong></div>
}

function AllocationChart({ rows }) {
  return <div className="allocation-content">
    <figure className="donut-figure">
      <svg viewBox="0 0 220 220" className="donut" role="img" aria-label={`Allocation of ${rows.length} holdings by current market value. Percentages are listed in the legend.`}>
        <circle className="donut-track" cx="110" cy="110" r="83" fill="none" strokeWidth="22" />
        {rows.map(row => <circle key={row.holdingId} cx="110" cy="110" r="83" fill="none" stroke={row.color} strokeWidth="22" pathLength="100" strokeDasharray={`${row.percentage} ${100 - row.percentage}`} strokeDashoffset={-row.offset} transform="rotate(-90 110 110)"><title>{row.assetSymbol}: {row.percentage.toFixed(1)}%, {money(row.currentValue)}</title></circle>)}
      </svg>
      <figcaption><strong>{rows.length}</strong><span>{rows.length === 1 ? 'position' : 'positions'}</span></figcaption>
    </figure>
    <ul className="allocation-legend">{rows.map(row => <li key={row.holdingId}><span className="legend-dot" style={{ backgroundColor: row.color }} /><span className="legend-name"><strong>{row.assetSymbol}</strong><small>{row.assetName}</small></span><span className="legend-value"><strong>{row.percentage.toFixed(1)}%</strong><small>{money(row.currentValue)}</small></span></li>)}</ul>
  </div>
}

export default function DashboardPage({ api, revision, navigate }) {
  const performance = useResource(api, '/portfolio/performance', revision)
  const holdings = useResource(api, '/portfolio/holdings', revision)
  const transactions = useResource(api, '/portfolio/transactions?size=5&sort=createdAt,desc', revision)
  const data = performance.data
  const rows = allocation(holdings.data || [])
  const quickActions = [['assets', 'Buy Assets', 'plus'], ['holdings', 'View Holdings', 'holdings'], ['transactions', 'Transactions', 'transactions'], ['watchlist', 'Watchlist', 'watchlist']]
  return <section className="portfolio-overview">
    <div className="page-heading"><div><span className="eyebrow">Your investment workspace</span><h2>Portfolio overview<span className="heading-dot">.</span></h2><p className="muted">Welcome back. Here’s where your portfolio stands.</p></div><button onClick={() => navigate('assets')}><Icon name="plus" size={17} />Buy assets</button></div>
    <Resource resource={performance}>{data && <>
      <article className="portfolio-hero"><div><span className="hero-label"><span className="live-dot" />Total portfolio value</span><p className="hero-value" data-testid="total-portfolio">{money(data.totalPortfolioValue)}</p><OverallReturn data={data} /></div><div className="hero-aside"><Icon name="shield" size={24} /><strong>Your strategy. Your portfolio.</strong><span>Explore investing with virtual funds.</span><span className="badge">Virtual trading account</span></div></article>
      <div className="kpi-grid">
        <ReturnCard label="Available Cash" value={data.cashBalance} icon="wallet" color={false} detail="Ready for your next investment" />
        <ReturnCard label="Holdings Market Value" value={data.holdingsValue} icon="holdings" color={false} detail="Current value of your positions" />
        <ReturnCard label="Unrealized Profit/Loss" value={data.unrealizedGainLoss} detail={Number(data.totalCostBasis) > 0 ? percentage(data.unrealizedGainLoss, data.totalCostBasis).toFixed(2) + '% of invested cost basis' : 'Build your first position'} />
        <ReturnCard label="Realized Profit/Loss" value={data.realizedGainLoss} detail="Recorded SELL profits and losses. Older sales may be excluded." />
      </div>
    </>}</Resource>
    <PortfolioHistory api={api} revision={revision} />
    <div className="dashboard-allocation"><article className="card allocation-panel"><div className="panel-heading"><div><span className="eyebrow">Diversification</span><h3>Asset allocation</h3></div><Icon name="pie" /></div><Resource resource={holdings} empty={!rows.length} emptyText="Your allocation starts with your first investment. Browse assets to build your portfolio."><AllocationChart rows={rows} /></Resource></article></div>
    <div className="activity-grid"><article className="card"><div className="panel-heading"><div><span className="eyebrow">Your positions</span><h3>Holdings breakdown</h3></div><button className="text-button" onClick={() => navigate('holdings')}>View all <Icon name="arrow" size={15} /></button></div><Resource resource={holdings} empty={!rows.length} emptyText="No holdings yet. Your investments will appear here."><>{data && <p className="muted holdings-cost-basis">Total invested: <strong>{money(data.totalCostBasis)}</strong> - Cost basis of current holdings</p>}<ul className="holdings-breakdown">{rows.slice(0, 5).map(row => { const gain = positionGain(row); return <li key={row.holdingId}><div className="asset-monogram" style={{ color: row.color }}>{row.assetSymbol.slice(0, 2)}</div><div className="position-detail"><strong>{row.assetSymbol}<span>{row.percentage.toFixed(1)}%</span></strong><small>{row.quantity} units · {row.assetName}</small><div className="position-track"><span style={{ width: `${row.percentage}%`, backgroundColor: row.color }} /></div></div><div className="position-value"><strong>{money(row.currentValue)}</strong><small className={gain < 0 ? 'negative' : 'positive'}>{gain >= 0 ? '+' : ''}{money(gain)}</small></div></li> })}</ul></></Resource></article>
      <article className="card"><div className="panel-heading"><div><span className="eyebrow">Latest activity</span><h3>Recent transactions</h3></div><button className="text-button" onClick={() => navigate('transactions')}>View all <Icon name="arrow" size={15} /></button></div><Resource resource={transactions} empty={!transactions.data?.content?.length} emptyText="No transactions yet. Your trading activity will appear here."><ul className="recent-transactions">{transactions.data?.content?.map(item => <li key={item.transactionId}><span className={`activity-icon ${item.transactionType === 'SELL' ? 'sell' : ''}`}><Icon name={item.transactionType === 'SELL' ? 'up' : 'down'} size={18} /></span><div><strong>{item.transactionType === 'SELL' ? 'Sold' : 'Bought'} {item.assetSymbol}</strong><small>{item.quantity} units · {date(item.createdAt)}</small></div><div className="transaction-amount"><strong>{money(item.totalAmount)}</strong><span className={`badge ${item.transactionType.toLowerCase()}`}>{item.transactionType}</span></div></li>)}</ul></Resource></article>
    </div>
    <div className="quick-actions" aria-label="Portfolio quick actions">{quickActions.map(([target, label, icon]) => <button className="secondary" key={target} onClick={() => navigate(target)}><Icon name={icon} size={18} /><span>{label}</span><Icon name="arrow" size={16} /></button>)}</div>
  </section>
}
