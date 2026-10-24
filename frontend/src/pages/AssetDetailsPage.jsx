import { useEffect, useRef, useState } from 'react'
import { latestPriceHistory } from '../api/market'
import { money } from '../api/format'
import { usePollingResource } from '../hooks/usePollingResource'
import { Alert, Resource } from '../components/UI'
import { MarketStatus, PriceMovement } from '../components/PriceIndicators'
import PriceHistoryChart from '../components/PriceHistoryChart'
import TradeForm from '../components/TradeForm'
import Icon from '../components/Icon'

export default function AssetDetailsPage({ api, asset, available, previousPrice, market, holding, holdings, refresh, onBack, navigate }) {
  const history = usePollingResource(api, `/assets/${encodeURIComponent(asset.assetId)}/price-history`)
  const [tradeMode, setTradeMode] = useState(null)
  const heading = useRef(null)
  const points = latestPriceHistory(history.data)
  useEffect(() => { heading.current?.focus() }, [])
  const canSell = available && holding && !holdings.loading && !holdings.error
  const selected = tradeMode === 'sell' && holding ? { ...holding, currentPrice: asset.currentPrice } : asset
  // A transient polling failure keeps the actual last successful series visible.
  const chartResource = { ...history, error: history.data === null ? history.error : '' }
  return <section className="asset-details-page">
    <button className="text-button back-to-market" onClick={onBack}><Icon name="arrow" size={16} />Back to Market</button>
    <div className="card asset-details-summary"><div className="page-heading"><div><span className="eyebrow">Market / Asset details</span><h2 ref={heading} tabIndex={-1}>{asset.name} <span className="muted">{asset.symbol}</span></h2><span className="badge">{asset.assetType}</span></div><MarketStatus resource={market} /></div>
      <div className="asset-details-price"><div><span className="muted">Current market price</span><strong>{money(asset.currentPrice)}</strong><PriceMovement current={asset.currentPrice} previous={previousPrice} /></div><div className="actions"><button disabled={!available} onClick={() => setTradeMode('buy')} aria-label={`Buy ${asset.symbol}`}>Buy</button><button className="danger" disabled={!canSell} title={canSell ? 'Sell units you own' : holdings.error ? 'Holdings are unavailable' : 'You need a holding to sell this asset'} onClick={() => setTradeMode('sell')} aria-label={`Sell ${asset.symbol}`}>Sell</button><button className="secondary" onClick={() => navigate('orders')}>Place limit order</button></div></div>
      <p className="muted movement-note">Price movement compares consecutive market updates, not daily performance.</p>
      {!available && !market.loading && <Alert kind="warning">This asset is no longer in the active market catalog. The displayed price may be outdated; trading here is unavailable.</Alert>}
      {market.error && <Alert kind="warning">{market.error} Showing the last received price. <button className="text-button" onClick={market.reload}>Retry prices</button></Alert>}
      {holdings.error && <Alert kind="warning">Holdings are temporarily unavailable. Selling is disabled until they can be checked. <button className="text-button" onClick={holdings.reload}>Retry holdings</button></Alert>}
      {tradeMode && available && (tradeMode !== 'sell' || canSell) && <TradeForm key={`${asset.assetId}-${tradeMode}`} selected={selected} mode={tradeMode} api={api} refresh={refresh} close={() => setTradeMode(null)} />}
    </div>
    <article className="card asset-history-panel"><div className="panel-heading"><div><span className="eyebrow">Recorded market prices</span><h3>Price history</h3></div><span className="badge">Latest {points.length} / 100 records</span></div>
      {history.error && history.data !== null && <Alert kind="warning">{history.error} Showing the last received history. <button className="text-button" onClick={history.reload}>Retry history</button></Alert>}
      <Resource resource={chartResource} empty={!points.length} emptyText="No price history is available for this asset yet.">{points.length > 0 && <PriceHistoryChart points={points} symbol={asset.symbol} />}</Resource>
      <p className="muted history-caption">Actual recorded prices in USD, ordered chronologically. Updates every 10 seconds.</p>
    </article>
  </section>
}
