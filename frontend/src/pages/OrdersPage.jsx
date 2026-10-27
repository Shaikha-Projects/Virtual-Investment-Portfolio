import { useCallback, useState } from 'react'
import { limitOrderPayload, orderAssetLabel, placeLimitOrder, cancelLimitOrder, loadSellHoldings, validateSellQuantity } from '../api/orders'
import { friendlyError, query } from '../api/client'
import { date, money } from '../api/format'
import { useResource } from '../hooks/useResource'
import { useAction } from '../hooks/useAction'
import { Alert, Field, Notice, Pager, Resource, Table } from '../components/UI'
import Icon from '../components/Icon'
import ConfirmDialog from '../components/ConfirmDialog'

function LimitOrderForm({ api, assets, revision, action, onPlaced, onClose }) {
  const [values, setValues] = useState({ assetId: '', orderType: 'BUY', quantity: '', limitPrice: '' })
  const sell = values.orderType === 'SELL'
  const holdingsApi = useCallback((_path, options) => sell ? loadSellHoldings(api, options) : Promise.resolve([]), [api, sell])
  const holdings = useResource(holdingsApi, '/portfolio/holdings', revision)
  const update = event => { action.clear(); setValues(previous => ({ ...previous, [event.target.name]: event.target.value, ...(event.target.name === 'orderType' ? { assetId: '', quantity: '' } : {}) })) }
  const availableAssets = assets.data || []
  const selectableAssets = sell ? availableAssets.filter(asset => holdings.data?.some(holding => String(holding.assetId) === String(asset.assetId) && holding.available > 0)) : availableAssets
  const selectedHolding = holdings.data?.find(holding => String(holding.assetId) === values.assetId)
  const selectedAsset = availableAssets.find(asset => String(asset.assetId) === values.assetId)
  const estimate = Number(values.quantity) * Number(values.limitPrice)
  const submit = async event => {
    event.preventDefault()
    let payload
    try {
      payload = limitOrderPayload(values, selectableAssets)
      if (sell) {
        if (holdings.loading || holdings.error) { action.warn('Wait for your available holdings to load.'); return }
        validateSellQuantity(payload.quantity, selectedHolding)
      }
    }
    catch (error) { action.warn(friendlyError(error)); return }
    if (await action.run(() => placeLimitOrder(api, payload), 'Limit order placed successfully.')) onPlaced()
  }
  const unavailable = assets.loading || Boolean(assets.error) || !selectableAssets.length || (sell && (holdings.loading || Boolean(holdings.error)))
  return <form className="trade-panel limit-order-form" onSubmit={submit}>
    <div className="trade-heading"><span className="icon-tile"><Icon name="clock" /></span><div><span className="eyebrow">Invest on your terms</span><h3>Place a limit order</h3></div><span className="badge">Limit order</span></div>
    <Resource resource={assets} empty={!availableAssets.length} emptyText="No active assets are available for limit orders." />
    {sell && <section aria-label="Your Available Holdings" className="sell-holdings"><h3>Your Available Holdings</h3><Resource resource={holdings} empty={!holdings.data?.length} emptyText="You have no holdings available to sell."><Table label="Available holdings"><thead><tr>{['Asset', 'Owned', 'Reserved', 'Available'].map(label => <th scope="col" key={label}>{label}</th>)}</tr></thead><tbody>{holdings.data?.map(holding => <tr key={holding.holdingId}><th scope="row"><button type="button" className="text-button" disabled={action.pending || holdings.loading || holding.available <= 0 || !selectableAssets.some(asset => String(asset.assetId) === String(holding.assetId))} onClick={() => { action.clear(); setValues(previous => ({ ...previous, assetId: String(holding.assetId), quantity: '' })) }}>{holding.assetSymbol}</button></th><td>{holding.quantity}</td><td>{holding.reserved}</td><td>{holding.available}</td></tr>)}</tbody></Table></Resource></section>}
    <div className="order-fields">
      <Field label="Asset" name="assetId" value={selectableAssets.some(asset => String(asset.assetId) === values.assetId) ? values.assetId : ''} onChange={update} required disabled={unavailable || action.pending}><option value="">Choose an asset</option>{selectableAssets.map(asset => <option value={asset.assetId} key={asset.assetId}>{asset.symbol} — {asset.name}</option>)}</Field>
      <Field label="Order type" name="orderType" value={values.orderType} onChange={update} disabled={action.pending}><option value="BUY">BUY LIMIT</option><option value="SELL">SELL LIMIT</option></Field>
      <Field label="Quantity" name="quantity" type="number" min="0.01" max={sell ? selectedHolding?.available ?? 0 : undefined} step="any" required value={values.quantity} onChange={update} disabled={action.pending} />
      <Field label="Limit price (USD)" name="limitPrice" type="number" min="0.01" step="any" required value={values.limitPrice} onChange={update} disabled={action.pending} />
    </div>
    {sell && <div className="actions"><span className="muted">Available shares: {holdings.loading ? 'Loading…' : holdings.error ? 'Unavailable' : selectedHolding?.available ?? 0}</span><button type="button" className="secondary" disabled={action.pending || unavailable || !(selectedHolding?.available > 0)} onClick={() => { action.clear(); setValues(previous => ({ ...previous, quantity: String(selectedHolding.available) })) }}>Max</button></div>}
    {selectedAsset && <p className="muted">Current market price: {money(selectedAsset.currentPrice)} per unit.</p>}
    <p className="order-rule"><Icon name="info" size={16} />{values.orderType === 'BUY' ? 'BUY LIMIT: Executes when the market price is less than or equal to your limit price.' : 'SELL LIMIT: Executes when the market price is greater than or equal to your limit price.'}</p>
    <p className="muted">Orders reserve available cash or shares. The server checks availability and handles execution; placing an order does not execute it.</p>
    <p className="trade-estimate">Estimated order value: <strong>{Number.isFinite(estimate) ? money(estimate) : '—'}</strong></p>
    <div className="actions"><button disabled={action.pending || unavailable}>{action.pending ? 'Placing order…' : 'Place limit order'}</button><button type="button" className="secondary" disabled={action.pending} onClick={onClose}>Close form</button></div>
  </form>
}

export default function OrdersPage({ api, revision, orderRevision = 0, refresh }) {
  const [page, setPage] = useState(0)
  const orders = useResource(api, `/orders?${query({ page, size: 10 })}`, `${revision}:${orderRevision}`)
  const data = orders.data
  const currentPage = data?.number ?? page
  const assets = useResource(api, '/assets', revision)
  const action = useAction()
  const [placing, setPlacing] = useState(false)
  const [cancelling, setCancelling] = useState(null)
  const cancel = async () => {
    if (!cancelling || cancelling.status !== 'PENDING') return
    if (await action.run(() => cancelLimitOrder(api, cancelling.id), 'Limit order cancelled successfully.')) {
      setCancelling(null)
      setPage(currentPage)
      refresh()
    }
  }
  const reload = () => { action.clear(); setPage(currentPage); refresh() }
  return <section className="card orders-page">
    <div className="page-heading"><div><span className="eyebrow">Investment workspace</span><h2>My Orders</h2><p className="muted">Place and manage your BUY and SELL limit orders.</p></div><div className="actions"><button className="secondary" onClick={reload} disabled={action.pending || orders.loading}>Refresh orders</button><button onClick={() => { action.clear(); setPlacing(true) }} disabled={action.pending || placing}><Icon name="plus" size={16} />Place Limit Order</button></div></div>
    {!cancelling && <Notice action={action} />}
    {!placing && assets.error && <Alert kind="warning">Asset names are temporarily unavailable. Orders are shown using asset IDs. <button className="text-button" onClick={assets.reload}>Retry asset names</button></Alert>}
    {placing && <LimitOrderForm api={api} assets={assets} revision={`${revision}:${orderRevision}`} action={action} onPlaced={() => { setPlacing(false); setPage(0); refresh() }} onClose={() => { setPlacing(false); action.clear() }} />}
    <Resource resource={orders} empty={!data?.content?.length} emptyText={data?.totalElements ? 'No orders on this page. Use Previous to view earlier pages.' : 'You have no limit orders yet. Place a limit order to get started.'}>
      <Table label="My limit orders"><thead><tr>{['Asset', 'Order Type', 'Quantity', 'Limit Price', 'Status', 'Created Date', 'Executed Date', 'Action'].map(label => <th scope="col" key={label}>{label}</th>)}</tr></thead>
        <tbody>{data?.content?.map(order => <tr key={order.id}><th scope="row">{orderAssetLabel(order, assets.data || [])}</th><td><span className={`badge ${order.orderType.toLowerCase()}`}>{order.orderType}</span></td><td>{order.quantity}</td><td className="financial-value">{money(order.limitPrice)}</td><td><span className={`badge ${order.status.toLowerCase()}`}>{order.status}</span></td><td>{date(order.createdAt)}</td><td>{date(order.executedAt)}</td><td>{order.status === 'PENDING' ? <button className="danger" disabled={action.pending} aria-label={`Cancel order ${order.id}`} onClick={() => { action.clear(); setCancelling(order) }}>Cancel</button> : <span className="muted">—</span>}</td></tr>)}</tbody>
      </Table>
    </Resource>
    {!orders.error && <Pager page={currentPage} totalPages={data?.totalPages} totalElements={data?.totalElements} disabled={orders.loading || action.pending || data?.totalPages === 0} onChange={value => { setPage(value); action.clear(); setCancelling(null) }} />}
    {cancelling && <ConfirmDialog title="Cancel this limit order?" confirmLabel="Confirm cancellation" cancelLabel="Keep order" destructive pending={action.pending} onConfirm={cancel} onClose={() => { setCancelling(null); action.clear() }}><p>{cancelling.orderType} {cancelling.quantity} units of {orderAssetLabel(cancelling, assets.data || [])} at a limit price of {money(cancelling.limitPrice)}.</p><p>The server will cancel the order only if it is still pending and release its reserved cash or shares.</p><Notice action={action} /></ConfirmDialog>}
  </section>
}
