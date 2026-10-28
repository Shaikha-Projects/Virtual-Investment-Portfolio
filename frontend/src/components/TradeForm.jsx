import { useState } from 'react'
import { useAction } from '../hooks/useAction'
import { money } from '../api/format'
import { Field, Notice } from './UI'
import Icon from './Icon'
import { useTransactionFeedback } from '../hooks/TransactionFeedback'

export default function TradeForm({ selected, mode, api, refresh, close }) {
  const [quantity, setQuantity] = useState('')
  const action = useAction()
  const feedback = useTransactionFeedback()
  const sell = mode === 'sell'
  const submit = async event => {
    event.preventDefault()
    const amount = Number(quantity)
    if (!Number.isFinite(amount) || amount < 0.01) { action.warn('Enter a quantity of at least 0.01.'); return }
    if (sell && amount > Number(selected.quantity)) { action.warn('You cannot sell more than you own.'); return }
    const success = `${sell ? 'Sale' : 'Purchase'} completed successfully.`
    const expectedMessage = `${sell ? 'Sale' : 'Purchase'} completed: ${amount} shares of ${selected.symbol || selected.assetSymbol}`
    const request = () => api(`/portfolio/${mode}`, { method: 'POST', body: { assetId: selected.assetId, quantity: amount } })
    if (await action.run(() => feedback ? feedback.run(request, expectedMessage, success) : request())) {
      refresh(feedback ? '' : success)
      close()
    }
  }
  return <form className="trade-panel" onSubmit={submit}><div className="trade-heading"><span className={`icon-tile ${sell ? 'negative' : ''}`}><Icon name={sell ? 'up' : 'down'} /></span><div><span className="eyebrow">{sell ? 'Close a position' : 'Place an investment'}</span><h3>{sell ? 'Sell' : 'Buy'} {selected.symbol || selected.assetSymbol}</h3></div><span className="badge">Virtual order</span></div><p>{sell ? `You own ${selected.quantity} units.` : `${money(selected.currentPrice)} per unit.`}</p><Notice action={action} /><Field label="Quantity" type="number" min="0.01" step="any" max={sell ? selected.quantity : undefined} required value={quantity} onChange={event => { setQuantity(event.target.value); action.clear() }} autoFocus /><p className="trade-estimate">Estimated total: {money(Number(quantity) * Number(selected.currentPrice))}</p><div className="actions"><button disabled={action.pending}>{action.pending ? 'Submitting…' : `Confirm ${sell ? 'sell' : 'buy'}`}</button><button type="button" className="secondary" disabled={action.pending} onClick={close}>Cancel</button></div></form>
}

