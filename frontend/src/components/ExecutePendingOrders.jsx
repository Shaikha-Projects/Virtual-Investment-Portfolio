import { useState } from 'react'
import { executePendingOrders } from '../api/orders'
import { useAction } from '../hooks/useAction'
import { Notice } from './UI'
import Icon from './Icon'
import ConfirmDialog from './ConfirmDialog'

export default function ExecutePendingOrders({ api, refresh, role }) {
  const [confirming, setConfirming] = useState(false)
  const action = useAction()
  if (role !== 'ADMIN') return null
  const execute = async () => {
    if (await action.run(() => executePendingOrders(api), 'Eligible pending orders processed successfully. Investors can refresh My Orders to see updated statuses.')) {
      setConfirming(false)
      refresh()
    }
  }
  return <article className="card order-execution-panel"><div className="panel-heading"><div><span className="eyebrow">Temporary testing control</span><h3>Limit order execution</h3></div><span className="badge admin-access"><Icon name="shield" size={14} />ADMIN</span></div>
    <p className="muted">Manually check pending orders against current market prices. Only eligible orders are filled. This control will be replaced by automatic market processing.</p>
    {!confirming && <Notice action={action} />}
    <button disabled={action.pending} onClick={() => { action.clear(); setConfirming(true) }}>Execute Pending Orders</button>
    {confirming && <ConfirmDialog title="Execute eligible pending orders?" confirmLabel="Confirm execution" cancelLabel="Go back" pending={action.pending} onConfirm={execute} onClose={() => { setConfirming(false); action.clear() }}><p>This will process pending limit orders across investor accounts using current market prices. Orders that do not meet their limit price remain pending.</p><p>Execution can change investor cash, holdings, and transaction history.</p><Notice action={action} /></ConfirmDialog>}
  </article>
}
