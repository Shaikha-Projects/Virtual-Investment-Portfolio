import Icon from '../components/Icon'
import { positionGain } from '../api/analytics'
import { date, money } from '../api/format'
import { useAction } from '../hooks/useAction'
import { useState } from 'react'
import { query } from '../api/client'
import { useResource } from '../hooks/useResource'
import { Alert, Field, Notice, Pager, Resource, Table } from '../components/UI'

function TradeForm({ selected, mode, api, refresh, close }) {
  const [quantity, setQuantity] = useState('')
  const action = useAction()
  const sell = mode === 'sell'
  const submit = async event => {
    event.preventDefault()
    const amount = Number(quantity)
    if (!Number.isFinite(amount) || amount < 0.01) { action.warn('Enter a quantity of at least 0.01.'); return }
    if (sell && amount > Number(selected.quantity)) { action.warn('You cannot sell more than you own.'); return }
    if (await action.run(() => api(`/portfolio/${mode}`, { method: 'POST', body: { assetId: selected.assetId, quantity: amount } }))) {
      refresh(`${sell ? 'Sale' : 'Purchase'} completed successfully.`)
      close()
    }
  }
  return <form className="trade-panel" onSubmit={submit}><div className="trade-heading"><span className={`icon-tile ${sell ? 'negative' : ''}`}><Icon name={sell ? 'up' : 'down'} /></span><div><span className="eyebrow">{sell ? 'Close a position' : 'Place an investment'}</span><h3>{sell ? 'Sell' : 'Buy'} {selected.symbol || selected.assetSymbol}</h3></div><span className="badge">Virtual order</span></div><p>{sell ? `You own ${selected.quantity} units.` : `${money(selected.currentPrice)} per unit.`}</p><Notice action={action} /><Field label="Quantity" type="number" min="0.01" step="any" max={sell ? selected.quantity : undefined} required value={quantity} onChange={event => { setQuantity(event.target.value); action.clear() }} autoFocus /><p className="trade-estimate">Estimated total: {money(Number(quantity) * Number(selected.currentPrice))}</p><div className="actions"><button disabled={action.pending}>{action.pending ? 'Submitting…' : `Confirm ${sell ? 'sell' : 'buy'}`}</button><button type="button" className="secondary" disabled={action.pending} onClick={close}>Cancel</button></div></form>
}

export function Assets({ api, revision, refresh }) {
  const [filters, setFilters] = useState({ search: '', type: '' })
  const [assetSort, setAssetSort] = useState('symbol,asc')
  const [selected, setSelected] = useState(null)
  const resource = useResource(api, `/assets?${query(filters)}`, revision)
  const watch = useAction()
  const [sortKey, sortDirection] = assetSort.split(',')
  const sortedAssets = [...(resource.data || [])].sort((a, b) => {
    const result = sortKey === 'currentPrice' ? Number(a[sortKey]) - Number(b[sortKey]) : String(a[sortKey]).localeCompare(String(b[sortKey]))
    return sortDirection === 'desc' ? -result : result
  })
  const submit = event => { event.preventDefault(); watch.clear(); setSelected(null); setFilters(Object.fromEntries(new FormData(event.currentTarget))) }
  return <section className="card"><div className="page-heading"><div><span className="eyebrow">Investment workspace</span><h2>Available Assets</h2><p className="muted">Discover your next investment. Explore the available asset catalog.</p></div></div><form className="filters" onSubmit={submit}><Field label="Search assets" name="search" type="search" placeholder="Symbol or name" /><Field label="Asset type" name="type"><option value="">All types</option><option>STOCK</option><option>ETF</option><option>CRYPTO</option></Field><button>Search</button></form><Notice action={watch} />
    {selected && <TradeForm key={selected.assetId} selected={selected} mode="buy" api={api} refresh={refresh} close={() => setSelected(null)} />}
    <Resource resource={resource} empty={!resource.data?.length} emptyText="No active assets match your search."><Table label="Available assets"><thead><tr>{[['Symbol', 'symbol'], ['Name', 'name'], ['Type', 'assetType'], ['Price', 'currentPrice']].map(([label, key]) => <th scope="col" key={key} aria-sort={key === sortKey ? sortDirection === 'asc' ? 'ascending' : 'descending' : 'none'}><button className="sort-button" onClick={() => setAssetSort(`${key},${key === sortKey && sortDirection === 'asc' ? 'desc' : 'asc'}`)}>{label} {key === sortKey ? sortDirection === 'asc' ? '\u2191' : '\u2193' : '\u2195'}</button></th>)}<th scope="col">Status</th><th scope="col">Actions</th></tr></thead><tbody>{sortedAssets.map(asset => <tr key={asset.assetId}><th scope="row">{asset.symbol}</th><td>{asset.name}</td><td>{asset.assetType}</td><td>{money(asset.currentPrice)}</td><td><span className="badge active">{asset.assetStatus}</span></td><td><div className="actions"><button onClick={() => { watch.clear(); setSelected(asset) }} aria-label={`Buy ${asset.symbol}`}>Buy</button><button className="secondary" disabled={watch.pending} onClick={() => watch.run(async () => { await api(`/watchlist/${asset.assetId}`, { method: 'POST' }); refresh() }, `${asset.symbol} added to your watchlist.`)}>+ Watchlist</button></div></td></tr>)}</tbody></Table></Resource></section>
}

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
