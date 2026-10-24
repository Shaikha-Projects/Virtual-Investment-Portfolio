import TradeForm from '../components/TradeForm'
import { positionGain } from '../api/analytics'
import { date, money } from '../api/format'
import { useAction } from '../hooks/useAction'
import { useState } from 'react'
import { query } from '../api/client'
import { useResource } from '../hooks/useResource'
import { Alert, Field, Notice, Pager, Resource, Table } from '../components/UI'

export function Holdings({ api, revision, refresh }) {
  const resource = useResource(api, '/portfolio/holdings', revision)
  const [selected, setSelected] = useState(null)
  return <section className="card"><div className="page-heading"><div><span className="eyebrow">Investment workspace</span><h2>My Holdings</h2><p className="muted">Your positions, market values, and unrealized returns in one place.</p></div></div>{selected && <TradeForm key={selected.holdingId} selected={selected} mode="sell" api={api} refresh={refresh} close={() => setSelected(null)} />}<Resource resource={resource} empty={!resource.data?.length} emptyText="You have no holdings yet. Browse Assets to start investing."><Table label="My holdings"><thead><tr>{['Symbol', 'Asset', 'Quantity', 'Average Buy Price', 'Current Price', 'Current Value', 'Unrealized P/L', 'Action'].map(label => <th scope="col" key={label}>{label}</th>)}</tr></thead><tbody>{resource.data?.map(holding => <tr key={holding.holdingId}><th scope="row">{holding.assetSymbol}</th><td>{holding.assetName}</td><td>{holding.quantity}</td><td>{money(holding.averageBuyPrice)}</td><td>{money(holding.currentPrice)}</td><td className="financial-value">{money(holding.currentValue)}</td><td className={positionGain(holding) < 0 ? 'negative' : 'positive'}>{positionGain(holding) >= 0 ? '+' : ''}{money(positionGain(holding))}</td><td><button className="danger" onClick={() => setSelected(holding)} aria-label={`Sell ${holding.assetSymbol}`}>Sell</button></td></tr>)}</tbody></Table></Resource></section>
}

export function Transactions({ api, revision }) {
  const [options, setOptions] = useState({ page: 0, symbol: '', type: '', sort: '' })
  const resource = useResource(api, `/portfolio/transactions?${query({ ...options, size: 10 })}`, revision)
  const data = resource.data
  const sort = column => setOptions(previous => ({ ...previous, page: 0, sort: previous.sort === `${column},asc` ? `${column},desc` : previous.sort === `${column},desc` ? '' : `${column},asc` }))
  const columns = [['Type', 'transactionType'], ['Asset', 'asset.symbol'], ['Quantity', 'quantity'], ['Price Per Unit', 'pricePerUnit'], ['Total Amount', 'totalAmount'], ['Status', 'transactionStatus'], ['Date', 'createdAt']]
  return <section className="card"><div className="page-heading"><div><span className="eyebrow">Investment workspace</span><h2>Transaction History</h2><p className="muted">A complete record of your investment decisions.</p></div></div><form className="filters" onSubmit={event => { event.preventDefault(); const filters = Object.fromEntries(new FormData(event.currentTarget)); setOptions(previous => ({ ...previous, ...filters, page: 0 })) }}><Field label="Asset symbol" name="symbol" type="search" /><Field label="Transaction type" name="type"><option value="">All types</option><option>BUY</option><option>SELL</option></Field><button>Search</button></form>
    <Resource resource={resource} empty={!data?.content?.length} emptyText="No transactions match these filters."><Table label="Transaction history"><thead><tr>{columns.map(([label, column]) => <th key={column} scope="col" aria-sort={options.sort.startsWith(`${column},`) ? options.sort.endsWith('asc') ? 'ascending' : 'descending' : 'none'}><button className="sort-button" onClick={() => sort(column)}>{label} {options.sort.startsWith(`${column},`) ? options.sort.endsWith('asc') ? '↑' : '↓' : '↕'}</button></th>)}</tr></thead><tbody>{data?.content?.map(item => <tr key={item.transactionId}><td><span className={`badge ${item.transactionType.toLowerCase()}`}>{item.transactionType}</span></td><td>{item.assetSymbol}</td><td>{item.quantity}</td><td>{money(item.pricePerUnit)}</td><td>{money(item.totalAmount)}</td><td><span className={`badge ${item.transactionStatus.toLowerCase()}`}>{item.transactionStatus}</span></td><td>{date(item.createdAt)}</td></tr>)}</tbody></Table></Resource>
    {!resource.error && <Pager page={options.page} totalPages={data?.totalPages} totalElements={data?.totalElements} disabled={resource.loading} onChange={page => setOptions(previous => ({ ...previous, page }))} />}
  </section>
}

export function Watchlist({ api, revision, refresh }) {
  const resource = useResource(api, '/watchlist', revision)
  const action = useAction()
  return <section className="card"><div className="page-heading"><div><span className="eyebrow">Investment workspace</span><h2>My Watchlist</h2><p className="muted">Keep your next opportunities close.</p></div></div><Notice action={action} /><Resource resource={resource} empty={!resource.data?.length} emptyText="No assets in your watchlist yet. Add one from Assets."><Table label="Watchlist"><thead><tr>{['Symbol', 'Asset', 'Current Price', 'Added Date', 'Action'].map(label => <th scope="col" key={label}>{label}</th>)}</tr></thead><tbody>{resource.data?.map(item => <tr key={item.watchlistId}><th scope="row">{item.assetSymbol}</th><td>{item.assetName}</td><td>{money(item.currentPrice)}</td><td>{date(item.createdAt)}</td><td><button className="danger" disabled={action.pending} aria-label={`Remove ${item.assetSymbol} from watchlist`} onClick={() => action.run(async () => { await api(`/watchlist/${item.watchlistId}`, { method: 'DELETE' }); refresh() }, 'Asset removed from your watchlist.')}>Remove</button></td></tr>)}</tbody></Table></Resource></section>
}

export function GlobalNotice({ message }) { return <Alert kind="success">{message}</Alert> }
