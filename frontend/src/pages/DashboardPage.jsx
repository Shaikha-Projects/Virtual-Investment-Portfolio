import { useResource } from '../hooks/useResource'
import { allocation } from '../api/analytics'
import { money } from '../api/format'
import { Resource, Table } from '../components/UI'
import Icon from '../components/Icon'
import PortfolioHistory from '../components/PortfolioHistory'
import AssetPerformance from '../components/AssetPerformance'

const colors = ['#2d5686', '#c3a05b', '#298782', '#8299b1', '#81699a', '#a96b72', '#647985']
function amount(value) { return value == null || String(value).trim() === '' || !Number.isFinite(Number(value)) ? '—' : money(value) }
function tone(value) { return Number(value) > 0 ? 'positive' : Number(value) < 0 ? 'negative' : '' }

function PortfolioValue({ value }) {
  const formatted = amount(value)
  const decimal = formatted.lastIndexOf('.')
  return <p className="folio-value" data-testid="total-portfolio">{decimal < 0 ? formatted : <>{formatted.slice(0, decimal)}<span>{formatted.slice(decimal)}</span></>}</p>
}

function AllocationChart({ rows }) {
  return <div className="folio-allocation-chart"><figure>
    <svg viewBox="0 0 180 180" role="img" aria-label="Allocation by current holdings value. Values are listed alongside.">
      <circle cx="90" cy="90" r="65" fill="none" stroke="var(--folio-border)" strokeWidth="22" />
      {rows.map((row, index) => <circle key={row.holdingId} cx="90" cy="90" r="65" fill="none" stroke={colors[index % colors.length]} strokeWidth="22" pathLength="100" strokeDasharray={`${row.percentage} ${100 - row.percentage}`} strokeDashoffset={-row.offset} transform="rotate(-90 90 90)"><title>{row.assetSymbol}: {row.percentage.toFixed(1)}%, {money(row.currentValue)}</title></circle>)}
    </svg><figcaption><strong>{rows.length}</strong><span>{rows.length === 1 ? 'asset' : 'assets'}</span></figcaption>
  </figure><ul>{rows.map((row, index) => <li key={row.holdingId}><span className="allocation-key" style={{ background: colors[index % colors.length] }} /><strong>{row.assetSymbol}</strong><span>{row.percentage.toFixed(1)}%</span></li>)}</ul></div>
}

export default function DashboardPage({ api, revision, navigate }) {
  const performance = useResource(api, '/portfolio/performance', revision)
  const holdings = useResource(api, '/portfolio/holdings', revision)
  const data = performance.data
  const rows = allocation(holdings.data || [])
  return <section className="orbit-overview" aria-label="Portfolio overview">
    <h2 className="sr-only">Portfolio overview</h2>
    <Resource resource={performance}>{data && <div className="folio-summary">
      <div className="folio-total"><div className="folio-kicker">Total portfolio value <span>Virtual account</span></div><PortfolioValue value={data.totalPortfolioValue} />
        <div className="folio-return"><span>Overall return</span><strong className={tone(data.totalGainLoss)}>{Number(data.totalGainLoss) > 0 ? '+' : ''}{amount(data.totalGainLoss)}</strong><span className={tone(data.totalReturnPercentage)}>({data.totalReturnPercentage == null || String(data.totalReturnPercentage).trim() === '' || !Number.isFinite(Number(data.totalReturnPercentage)) ? '—' : `${Number(data.totalReturnPercentage) > 0 ? '+' : ''}${Number(data.totalReturnPercentage).toFixed(2)}%`})</span></div>
        <dl className="folio-metrics">{[['Invested cost basis', data.totalCostBasis], ['Holdings value', data.holdingsValue], ['Unrealized P/L', data.unrealizedGainLoss, true], ['Realized P/L', data.realizedGainLoss, true]].map(([label, value, semantic]) => <div key={label}><dt>{label}</dt><dd className={semantic ? tone(value) : ''}>{semantic && Number(value) > 0 ? '+' : ''}{amount(value)}</dd></div>)}</dl>
      </div>
      <aside className="folio-wallet" aria-label="Wallet summary"><span className="folio-kicker">Cash Balance</span><strong>{amount(data.cashBalance)}</strong><span>Virtual USD · Includes any reserved cash</span><div className="actions"><button onClick={() => navigate('wallet')}>Deposit</button><button className="secondary" onClick={() => navigate('wallet')}>Withdraw</button></div></aside>
    </div>}</Resource>
    <div className="folio-charts"><PortfolioHistory api={api} revision={revision} integrated /><AssetPerformance api={api} revision={revision} /></div>
    <div className="folio-bottom"><article className="folio-holdings"><div className="folio-section-heading"><h2>My holdings</h2><span>{holdings.data?.length ?? '—'} positions</span><button className="text-button" onClick={() => navigate('holdings')}>View all holdings <Icon name="arrow" size={15} /></button></div>
      <p className="folio-table-hint">Scroll the table sideways to see all values and weights.</p>
      <Resource resource={holdings} empty={!holdings.data?.length} emptyText="Your first investment will appear here."><Table label="Holdings preview"><thead><tr><th scope="col">Asset</th><th scope="col">Quantity</th><th scope="col">Current price</th><th scope="col">Current value</th><th scope="col">Weight of holdings</th></tr></thead><tbody>{holdings.data?.slice(0, 5).map(holding => {
        const index = rows.findIndex(row => row.holdingId === holding.holdingId)
        const weight = rows[index]?.percentage
        return <tr key={holding.holdingId}><th scope="row"><span className="holding-symbol"><span className="holding-monogram" style={{ background: colors[Math.max(index, 0) % colors.length] }}>{holding.assetSymbol?.[0]}</span><strong>{holding.assetSymbol}</strong><small>{holding.assetName}</small></span></th><td>{holding.quantity}</td><td>{amount(holding.currentPrice)}</td><td>{amount(holding.currentValue)}</td><td><span className="holding-weight">{weight == null ? '—' : `${weight.toFixed(1)}%`}{weight != null && <span className="weight-track" aria-hidden="true"><span style={{ width: `${weight}%`, background: colors[index % colors.length] }} /></span>}</span></td></tr>
      })}</tbody></Table></Resource>
    </article><aside className="folio-side"><article><h2>Portfolio allocation</h2><Resource resource={holdings} empty={!rows.length} emptyText="Allocation appears when you hold assets with a positive value."><AllocationChart rows={rows} /></Resource></article><button className="folio-help" onClick={() => navigate('help')}><Icon name="info" size={17} /><strong>AI Help</strong><span>Application guidance</span><Icon name="arrow" size={18} /></button></aside></div>
    <p className="folio-disclosure">Virtual investing, real perspective. Recorded SELL gains may exclude older sales. Holdings and portfolio values refresh when this view loads or after an account action.</p>
  </section>
}
