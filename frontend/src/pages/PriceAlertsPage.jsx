import { useState } from 'react'
import { alertCondition, cancelPriceAlert, createPriceAlert, priceAlertPayload } from '../api/priceAlerts'
import { friendlyError } from '../api/client'
import { date, money } from '../api/format'
import { useResource } from '../hooks/useResource'
import { useAction } from '../hooks/useAction'
import { Field, Notice, Resource, Table } from '../components/UI'

export default function PriceAlertsPage({ api, revision, priceAlertRevision = 0 }) {
  const [version, setVersion] = useState(0)
  const assets = useResource(api, '/assets', `${revision}:${version}:${priceAlertRevision}`)
  const alerts = useResource(api, '/price-alerts', `${revision}:${version}:${priceAlertRevision}`)
  const action = useAction()
  const [values, setValues] = useState({ assetId: '', condition: 'ABOVE', targetPrice: '' })
  const selected = assets.data?.find(asset => String(asset.assetId) === values.assetId)
  const unavailable = assets.loading || Boolean(assets.error) || !assets.data?.length
  const update = event => { action.clear(); setValues(previous => ({ ...previous, [event.target.name]: event.target.value })) }
  const submit = async event => {
    event.preventDefault()
    if (unavailable) return
    let payload
    try { payload = priceAlertPayload(values, assets.data || []) }
    catch (error) { action.warn(friendlyError(error)); return }
    const success = await action.run(async () => {
      try { await createPriceAlert(api, payload) }
      finally { setVersion(value => value + 1) }
    }, 'Price alert created successfully.')
    if (success) setValues(previous => ({ ...previous, targetPrice: '' }))
  }
  const cancel = alert => action.run(async () => {
    try { await cancelPriceAlert(api, alert.id) }
    finally { setVersion(value => value + 1) }
  }, 'Price alert cancelled successfully.')
  return <section className="price-alerts-page">
    <div className="page-heading"><div><span className="eyebrow">Market notifications</span><h2>Price Alerts</h2><p className="muted">Get notified when an asset reaches your target price.</p></div></div>
    <Notice action={action} />
    <article className="card"><h3>Create Alert</h3>
      <Resource resource={assets} empty={!assets.data?.length} emptyText="No assets are available for price alerts.">
        <form onSubmit={submit} className="price-alert-form">
          <div className="price-alert-fields">
            <Field label="Asset" name="assetId" value={values.assetId} onChange={update} required disabled={action.pending}><option value="">Choose an asset</option>{assets.data?.map(asset => <option key={asset.assetId} value={asset.assetId}>{asset.name} ({asset.symbol})</option>)}</Field>
            <Field label="Condition" name="condition" value={values.condition} onChange={update} disabled={action.pending}><option value="ABOVE">At or above</option><option value="BELOW">At or below</option></Field>
            <Field label="Target price (USD)" name="targetPrice" type="number" inputMode="decimal" min="0.01" step="any" value={values.targetPrice} onChange={update} required disabled={action.pending} />
          </div>
          <p className="muted">Current asset price: {selected?.currentPrice != null ? money(selected.currentPrice) : '—'}</p>
          <div className="actions"><button disabled={action.pending || unavailable}>{action.pending ? 'Saving…' : 'Create Alert'}</button><button type="button" className="text-button" disabled={action.pending || assets.loading} onClick={assets.reload}>Refresh assets</button></div>
        </form>
      </Resource>
    </article>
    <article className="card"><div className="panel-heading"><h3>My Price Alerts</h3></div>
      <Resource resource={alerts} empty={!alerts.data?.length} emptyText="No price alerts yet. Create an alert to follow an asset's price.">
        <Table label="My price alerts"><thead><tr>{['Asset', 'Condition', 'Target Price', 'Status', 'Created', 'Triggered', 'Action'].map(label => <th scope="col" key={label}>{label}</th>)}</tr></thead><tbody>{alerts.data?.map(alert => <tr key={alert.id}>
          <th scope="row">{alert.assetSymbol}<small className="alert-asset-name">{alert.assetName}</small></th><td>{alertCondition(alert.condition)}</td><td>{money(alert.targetPrice)}</td><td><span className={`badge price-alert-status ${alert.status === 'ACTIVE' ? 'positive' : alert.status === 'TRIGGERED' ? 'triggered' : 'muted'}`}>{alert.status}</span></td><td>{date(alert.createdAt)}</td><td>{alert.triggeredAt ? date(alert.triggeredAt) : '—'}</td><td>{alert.status === 'ACTIVE' ? <button className="secondary" disabled={action.pending || alerts.loading} onClick={() => cancel(alert)} aria-label={`Cancel alert for ${alert.assetSymbol}`}>Cancel</button> : '—'}</td>
        </tr>)}</tbody></Table>
      </Resource>
    </article>
  </section>
}
