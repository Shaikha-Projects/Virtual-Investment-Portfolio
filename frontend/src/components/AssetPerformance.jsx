import { useId, useState } from 'react'
import { usePollingResource } from '../hooks/usePollingResource'
import { latestPriceHistory } from '../api/market'
import { money } from '../api/format'
import { Alert, Resource } from './UI'
import PriceHistoryChart from './PriceHistoryChart'

function SelectedAssetHistory({ api, asset }) {
  const history = usePollingResource(api, `/assets/${encodeURIComponent(asset.assetId)}/price-history`)
  const points = latestPriceHistory(history.data)
  const resource = { ...history, error: history.data === null ? history.error : '' }
  return <>
    <div className="asset-chart-meta"><strong>{asset.symbol} <span>{money(asset.currentPrice)}</span></strong><span>Simulated prices · Refreshes every 10s</span></div>
    {history.error && history.data !== null && <Alert kind="warning">Prices delayed. Showing the last received history. <button className="text-button" onClick={history.reload}>Retry history</button></Alert>}
    <Resource resource={resource} empty={!points.length} emptyText="No recorded prices for this asset yet.">
      {points.length > 0 && <PriceHistoryChart points={points} symbol={asset.symbol} height={240} />}
    </Resource>
    <p className="chart-footnote">Latest {points.length} / 100 recorded prices · USD</p>
  </>
}

export default function AssetPerformance({ api, revision }) {
  const catalog = usePollingResource(api, '/assets', revision)
  const [selectedId, setSelectedId] = useState('')
  const id = useId()
  const selected = catalog.data?.find(asset => String(asset.assetId) === selectedId) || catalog.data?.[0]
  const resource = { ...catalog, error: catalog.data === null ? catalog.error : '' }
  return <article className="asset-performance" aria-labelledby={`${id}-heading`}>
    <div className="chart-heading"><h2 id={`${id}-heading`}>Asset Performance</h2><label className="asset-selector" htmlFor={id}><span className="sr-only">Select asset performance</span><select id={id} value={selected?.assetId ?? ''} disabled={!catalog.data?.length} onChange={event => setSelectedId(event.target.value)}>
      {!catalog.data?.length && <option value="">Select asset</option>}
      {catalog.data?.map(asset => <option key={asset.assetId} value={asset.assetId}>{asset.symbol} · {asset.name}</option>)}
    </select></label></div>
    {catalog.error && catalog.data !== null && <Alert kind="warning">Asset prices delayed. <button className="text-button" onClick={catalog.reload}>Retry prices</button></Alert>}
    <Resource resource={resource} empty={!selected} emptyText="No active assets are available.">
      {selected && <SelectedAssetHistory key={selected.assetId} api={api} asset={selected} />}
    </Resource>
  </article>
}
