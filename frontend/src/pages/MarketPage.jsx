import { useState } from 'react'
import { query } from '../api/client'
import { money } from '../api/format'
import { useAction } from '../hooks/useAction'
import { useResource } from '../hooks/useResource'
import { usePollingResource } from '../hooks/usePollingResource'
import { Alert, Field, Notice, Resource, Table } from '../components/UI'
import { MarketStatus, PriceMovement } from '../components/PriceIndicators'
import TradeForm from '../components/TradeForm'
import AssetDetailsPage from './AssetDetailsPage'

export default function MarketPage({ api, revision, refresh, navigate }) {
  const [filters, setFilters] = useState({ search: '', type: '' })
  const [assetSort, setAssetSort] = useState('symbol,asc')
  const [trade, setTrade] = useState(null)
  const [viewedAsset, setViewedAsset] = useState(null)
  const resource = usePollingResource(api, `/assets?${query(filters)}`, revision)
  const holdings = useResource(api, '/portfolio/holdings', revision)
  const watch = useAction()
  const [sortKey, sortDirection] = assetSort.split(',')
  const assets = resource.data || []
  const sortedAssets = [...assets].sort((a, b) => {
    const result = sortKey === 'currentPrice' ? Number(a[sortKey]) - Number(b[sortKey]) : String(a[sortKey]).localeCompare(String(b[sortKey]))
    return sortDirection === 'desc' ? -result : result
  })
  const previousPrice = id => resource.previousData?.find(asset => asset.assetId === id)?.currentPrice
  const holdingFor = id => holdings.data?.find(holding => holding.assetId === id)
  const submit = event => { event.preventDefault(); watch.clear(); setTrade(null); setFilters(Object.fromEntries(new FormData(event.currentTarget))) }
  const openDetails = asset => { watch.clear(); setTrade(null); setViewedAsset(asset) }
  if (viewedAsset) {
    const asset = assets.find(item => item.assetId === viewedAsset.assetId)
    return <AssetDetailsPage key={viewedAsset.assetId} api={api} asset={asset || viewedAsset} available={Boolean(asset)} previousPrice={previousPrice(viewedAsset.assetId)} market={resource} holding={holdingFor(viewedAsset.assetId)} holdings={holdings} refresh={refresh} navigate={navigate} onBack={() => { const id = viewedAsset.assetId; setViewedAsset(null); requestAnimationFrame(() => document.querySelector(`[data-market-asset="${id}"]`)?.focus()) }} />
  }
  const tradeAsset = trade && assets.find(asset => asset.assetId === trade.assetId)
  const tradeHolding = trade && holdingFor(trade.assetId)
  const marketResource = { ...resource, error: resource.data === null ? resource.error : '' }
  return <section className="card market-page"><div className="page-heading"><div><span className="eyebrow">Investment workspace</span><h2>Available Assets</h2><p className="muted">Discover your next investment with live market prices.</p></div><MarketStatus resource={resource} /></div>
    <form className="filters" onSubmit={submit}><Field label="Search assets" name="search" type="search" placeholder="Symbol or name" defaultValue={filters.search} /><Field label="Asset type" name="type" defaultValue={filters.type}><option value="">All types</option><option>STOCK</option><option>ETF</option><option>CRYPTO</option></Field><button>Search</button></form>
    <Notice action={watch} />
    {resource.error && resource.data !== null && <Alert kind="warning">{resource.error} Showing the last received prices. <button className="text-button" onClick={resource.reload}>Retry prices</button></Alert>}
    {holdings.error && <Alert kind="warning">Holdings are temporarily unavailable. Selling is disabled until they can be checked. <button className="text-button" onClick={holdings.reload}>Retry holdings</button></Alert>}
    {tradeAsset && (trade.mode !== 'sell' || (tradeHolding && !holdings.loading && !holdings.error)) && <TradeForm key={`${tradeAsset.assetId}-${trade.mode}`} selected={trade.mode === 'sell' ? { ...tradeHolding, currentPrice: tradeAsset.currentPrice } : tradeAsset} mode={trade.mode} api={api} refresh={refresh} close={() => setTrade(null)} />}
    <p className="muted movement-note">Prices refresh every 10 seconds. Movement is measured against the previous successful update.</p>
    <Resource resource={marketResource} empty={!assets.length} emptyText="No active assets match your search."><Table label="Available assets"><thead><tr>{[['Symbol', 'symbol'], ['Name', 'name'], ['Type', 'assetType'], ['Price', 'currentPrice']].map(([label, key]) => <th scope="col" key={key} aria-sort={key === sortKey ? sortDirection === 'asc' ? 'ascending' : 'descending' : 'none'}><button className="sort-button" onClick={() => setAssetSort(`${key},${key === sortKey && sortDirection === 'asc' ? 'desc' : 'asc'}`)}>{label} {key === sortKey ? sortDirection === 'asc' ? '↑' : '↓' : '↕'}</button></th>)}<th scope="col">Movement</th><th scope="col">Status</th><th scope="col">Actions</th></tr></thead><tbody>{sortedAssets.map(asset => {
      const holding = holdingFor(asset.assetId)
      return <tr key={asset.assetId}><th scope="row"><button className="asset-details-link" data-market-asset={asset.assetId} aria-label={`View ${asset.symbol} details`} onClick={() => openDetails(asset)}>{asset.symbol}</button></th><td><button className="asset-name-link" onClick={() => openDetails(asset)} aria-label={`View ${asset.name} details`}>{asset.name}</button></td><td>{asset.assetType}</td><td className="financial-value">{money(asset.currentPrice)}</td><td><PriceMovement current={asset.currentPrice} previous={previousPrice(asset.assetId)} /></td><td><span className="badge active">{asset.assetStatus}</span></td><td><div className="actions"><button onClick={() => { watch.clear(); setTrade({ assetId: asset.assetId, mode: 'buy' }) }} aria-label={`Buy ${asset.symbol}`}>Buy</button><button className="danger" disabled={!holding || holdings.loading || Boolean(holdings.error)} title={holding ? 'Sell units you own' : 'You need a holding to sell this asset'} onClick={() => { watch.clear(); setTrade({ assetId: asset.assetId, mode: 'sell' }) }} aria-label={`Sell ${asset.symbol}`}>Sell</button><button className="secondary" disabled={watch.pending} onClick={() => watch.run(async () => { await api(`/watchlist/${asset.assetId}`, { method: 'POST' }); refresh() }, `${asset.symbol} added to your watchlist.`)}>+ Watchlist</button></div></td></tr>
    })}</tbody></Table></Resource>
  </section>
}
