import { useEffect, useRef, useState } from 'react'
import { ApiError, friendlyError, query } from '../api/client'
import { money, date } from '../api/format'
import { checkoutUrl, clearCompletedWalletRequest, loadWalletRequest, saveWalletRequest, submitWalletRequest, updateWalletDeposit, walletAmount, canDownloadReceipt, downloadWalletReceipt, downloadWalletStatement } from '../api/wallet'
import { useResource } from '../hooks/useResource'
import { useAction } from '../hooks/useAction'
import { Alert, Field, Notice, Pager, Resource, Table } from '../components/UI'
import Icon from '../components/Icon'

function savedRequest(user, type) {
  try { return loadWalletRequest(sessionStorage, user, type) }
  catch (error) { return { blocked: true, error: friendlyError(error) } }
}

function WalletForm({ api, user, type, balance, refresh, onComplete, clearVersion, busy, onBusy }) {
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  const [request, setRequest] = useState(() => savedRequest(user, type))
  const [amount, setAmount] = useState(() => request?.amount || '')
  const action = useAction()
  const [noticeVersion, setNoticeVersion] = useState(clearVersion)
  const deposit = type === 'deposits'
  const submit = event => {
    event.preventDefault()
    if (busy || action.pending || request?.blocked) return
    setNoticeVersion(clearVersion)
    action.run(async () => {
      onBusy(true)
      try {
        const value = walletAmount(amount)
        if (!deposit && !request && (balance.loading || balance.error || !Number.isFinite(Number(balance.data?.availableCash)) || Number(value) > Number(balance.data?.availableCash))) {
          throw new ApiError('The withdrawal must not exceed your available cash. Refresh your balance and try again.')
        }
        const current = request || { key: crypto.randomUUID(), amount: value }
        if (current.amount !== value) throw new ApiError('Retry the original amount or explicitly start a new transaction.')
        // save the key before sending any request
        saveWalletRequest(sessionStorage, user, type, current)
        setRequest(current)
        const result = await submitWalletRequest(api, type, current)
        if (result.status === 'COMPLETED') {
          saveWalletRequest(sessionStorage, user, type, null)
          setRequest(null); setAmount(''); action.clear()
          onComplete(deposit ? 'Deposit completed.' : 'Withdrawal completed.')
          return
        }
        refresh()
        const stored = { ...current, reference: result.reference, status: result.status }
        saveWalletRequest(sessionStorage, user, type, stored)
        setRequest(stored)
        if (deposit && result.status === 'PENDING') {
          if (!result.paymentUrl) throw new ApiError('Your deposit is pending without a checkout link. Use Recover checkout in your history. Do not create another deposit to retry it.')
          if (mounted.current) window.location.assign(checkoutUrl(result.paymentUrl))
        }
      } finally { onBusy(false) }
    })
  }
  const reset = () => {
    setNoticeVersion(clearVersion)
    try {
      saveWalletRequest(sessionStorage, user, type, null)
      setRequest(null); setAmount(''); action.clear()
    } catch (error) { action.warn(friendlyError(error)) }
  }
  return <form className="card wallet-form" onSubmit={submit}>
    <div className="panel-heading"><div><span className="eyebrow">{deposit ? 'Stripe sandbox' : 'Simulated withdrawal'}</span><h3>{deposit ? 'Deposit virtual cash' : 'Withdraw virtual cash'}</h3></div><Icon name={deposit ? 'plus' : 'wallet'} /></div>
    <p className="muted">{deposit ? 'Continue to Stripe hosted checkout using test payment details only. No real money is deposited.' : 'Withdraw available virtual USD. No bank transfer or payout takes place.'}</p>
    <Field label="Amount (USD)" name={`wallet-${type}-amount`} type="text" inputMode="decimal" required value={amount} disabled={busy || action.pending || Boolean(request)} onChange={event => setAmount(event.target.value)} />
    {request && <p className="muted">{request.blocked ? request.error : request.status === 'COMPLETED' ? 'This transaction is completed. A new transaction requires a new request.' : 'Retries reuse this request. A previous attempt may already exist; check history before starting a different transaction.'}</p>}
    {noticeVersion === clearVersion && <Notice action={action} />}
    <div className="actions"><button disabled={busy || action.pending || request?.blocked || request?.status === 'COMPLETED' || (!deposit && !request && (balance.loading || Boolean(balance.error)))}>{action.pending ? 'Processing...' : request ? 'Retry same request' : deposit ? 'Continue to Stripe' : 'Withdraw cash'}</button>{request && <button type="button" className="secondary" disabled={busy || action.pending} onClick={reset}>Start a new transaction</button>}</div>
  </form>
}

export default function WalletPage({ api, user, revision, refresh }) {
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  const [page, setPage] = useState(0)
  const [busy, setBusy] = useState(false)
  const [completion, setCompletion] = useState(null)
  const [clearVersion, setClearVersion] = useState(0)
  const [depositVersion, setDepositVersion] = useState(0)
  const [dates, setDates] = useState(() => {
    const now = new Date()
    const to = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
    return { from: `${to.slice(0, 8)}01`, to }
  })
  const pdfAction = useAction()
  const [download, setDownload] = useState('')
  const downloadPdf = (reference = '') => pdfAction.run(async () => {
    setDownload(reference || 'statement')
    try {
      if (reference) await downloadWalletReceipt(api, reference)
      else await downloadWalletStatement(api, dates.from, dates.to)
    } finally { setDownload('') }
  })
  const [returning] = useState(() => new URLSearchParams(location.search).get('payment'))
  const checked = useRef(false)
  const balance = useResource(api, '/portfolio/wallet/balance', revision)
  const history = useResource(api, `/portfolio/wallet/history?${query({ page, size: 10 })}`, revision)
  const action = useAction()
  const refreshWallet = () => { setPage(0); refresh() }
  const complete = text => {
    setCompletion({ text, revision })
    action.clear()
    refreshWallet()
  }
  const refreshPage = () => {
    action.clear(); setCompletion(null)
    setClearVersion(value => value + 1)
    refreshWallet()
  }
  const refreshing = completion && (completion.revision === revision || balance.loading || history.loading)
  useEffect(() => {
    if (!completion || refreshing) return
    const timer = setTimeout(() => setCompletion(null), 4000)
    return () => clearTimeout(timer)
  }, [completion, refreshing])
  const checkDeposit = async (reference, operation = 'verify', resume = false) => {
    if (busy) return
    await action.run(async () => {
      setBusy(true)
      setCompletion(null)
      try {
        const result = await updateWalletDeposit(api, reference, operation)
        if (result.status === 'COMPLETED') {
          if (clearCompletedWalletRequest(sessionStorage, user.emailAddress, 'deposits', reference)) {
            setDepositVersion(value => value + 1)
          }
          complete('Deposit completed.')
          return
        }
        refreshWallet()
        const saved = loadWalletRequest(sessionStorage, user.emailAddress, 'deposits')
        if (saved?.reference === reference) saveWalletRequest(sessionStorage, user.emailAddress, 'deposits', { ...saved, status: result.status })
        if (result.status === 'PENDING') action.warn('This deposit is still pending. No payment has been confirmed. You can check again or recover its checkout.')
        else action.warn(`Deposit status: ${result.status}. No new credit was confirmed.`)
        if (resume && result.status === 'PENDING' && mounted.current) window.location.assign(checkoutUrl(result.paymentUrl))
      } finally { setBusy(false) }
    })
  }
  useEffect(() => {
    if (!returning || checked.current) return
    checked.current = true
    const url = new URL(location.href)
    url.searchParams.delete('payment'); url.searchParams.delete('session_id')
    window.history.replaceState(null, '', `${url.pathname}${url.search}${url.hash}`)
    let saved
    try { saved = loadWalletRequest(sessionStorage, user.emailAddress, 'deposits') }
    catch (error) { action.warn(friendlyError(error)); return }
    if (returning === 'cancelled') {
      action.warn('Checkout was cancelled. No payment is confirmed by this redirect. The deposit remains available in history for checking or recovery.')
    }
    if (saved?.reference) {
      checkDeposit(saved.reference)
    } else {
      action.warn('No saved wallet reference was found. Check the deposit in your history; the redirect alone does not confirm payment.')
    }
    // verify the saved wallet reference once after the hosted redirect
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  const data = history.data
  return <section className="wallet-page">
    <div className="page-heading"><div><span className="eyebrow">Your virtual funds</span><h2>Cash Wallet</h2><p className="muted">Manage demonstration USD funds and review your wallet history.</p></div><button className="secondary" disabled={busy || action.pending} onClick={refreshPage}>Refresh wallet</button></div>
    <Notice action={action} />
    {completion && <Alert kind="success">{completion.text}{refreshing ? ' Refreshing balance and history...' : balance.error || history.error ? ' Refresh failed. Please refresh your wallet again.' : ''}</Alert>}
    <Resource resource={balance}><div className="summary-cards">{[['Cash balance', 'cashBalance', 'Total virtual cash in your portfolio'], ['Reserved cash', 'reservedCash', 'Held for pending BUY limit orders'], ['Available cash', 'availableCash', 'Available for investing or withdrawal']].map(([label, field, description]) => <article className="summary-card" key={field}><span>{label}</span><h3>{money(balance.data?.[field])}</h3><p>{description}</p></article>)}</div></Resource>
    <div className="wallet-forms"><WalletForm key={depositVersion} api={api} user={user.emailAddress} type="deposits" balance={balance} refresh={refreshWallet} onComplete={complete} clearVersion={clearVersion} busy={busy} onBusy={setBusy} /><WalletForm api={api} user={user.emailAddress} type="withdrawals" balance={balance} refresh={refreshWallet} onComplete={complete} clearVersion={clearVersion} busy={busy} onBusy={setBusy} /></div>
    <article className="card"><div className="panel-heading"><div><span className="eyebrow">Permanent history</span><h3>Deposits and withdrawals</h3></div><Icon name="clock" /></div>
      <form className="wallet-statement" onSubmit={event => { event.preventDefault(); downloadPdf() }}>
        <Field label="From" name="statementFrom" type="date" required max={dates.to} value={dates.from} disabled={pdfAction.pending} onChange={event => { pdfAction.clear(); setDates(previous => ({ ...previous, from: event.target.value })) }} />
        <Field label="To" name="statementTo" type="date" required min={dates.from} value={dates.to} disabled={pdfAction.pending} onChange={event => { pdfAction.clear(); setDates(previous => ({ ...previous, to: event.target.value })) }} />
        <button className="secondary" disabled={busy || pdfAction.pending}>{download === 'statement' ? 'Downloading statement...' : 'Export Statement'}</button>
      </form>
      <Notice action={pdfAction} />
      <Resource resource={history} empty={!data?.content?.length} emptyText="No wallet operations on this page."><Table label="Wallet operation history"><thead><tr>{['Created', 'Type', 'Amount', 'Status', 'Balance after', 'Reference', 'Actions'].map(label => <th scope="col" key={label}>{label}</th>)}</tr></thead><tbody>{data?.content?.map(item => <tr key={item.reference}><td>{date(item.createdAt)}</td><td>{item.operationType}</td><td className="financial-value">{money(item.amount)}</td><td><span className={`badge ${item.status.toLowerCase()}`}>{item.status}</span></td><td>{item.balanceAfter == null ? '—' : money(item.balanceAfter)}</td><td className="wallet-reference">{item.reference}</td><td>{item.operationType === 'DEPOSIT' && item.status === 'PENDING' ? <div className="actions"><button className="secondary" disabled={busy || action.pending} onClick={() => checkDeposit(item.reference)}>Check payment</button><button className="secondary" disabled={busy || action.pending} onClick={() => checkDeposit(item.reference, 'recover', true)}>Recover checkout</button></div> : canDownloadReceipt(item) ? <button className="secondary" disabled={busy || pdfAction.pending} onClick={() => downloadPdf(item.reference)}>{download === item.reference ? 'Downloading...' : 'Download PDF'}</button> : '—'}</td></tr>)}</tbody></Table></Resource>
      {!history.error && <Pager page={data?.number ?? page} totalPages={data?.totalPages} totalElements={data?.totalElements} disabled={history.loading || busy || action.pending || data?.totalPages === 0} onChange={setPage} />}
    </article>
    <Alert kind="warning">Sandbox funds only. Use Stripe test payment details. A checkout redirect never confirms a deposit; the server must verify it.</Alert>
  </section>
}
