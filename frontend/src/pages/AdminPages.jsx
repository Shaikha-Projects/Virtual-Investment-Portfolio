import Icon from '../components/Icon'
import ExecutePendingOrders from '../components/ExecutePendingOrders'
import { date, money } from '../api/format'
import { useAction } from '../hooks/useAction'
import { useState } from 'react'
import { query } from '../api/client'
import { useResource } from '../hooks/useResource'
import { Field, Notice, Pager, Resource, Table } from '../components/UI'

export function AdminDashboard({ api, revision, navigate, refresh, user }) {
  const assets = useResource(api, '/admin/assets', revision)
  const users = useResource(api, '/admin/users?size=1', revision)
  return <section className="admin-overview"><div className="page-heading"><div><span className="eyebrow">Administration workspace</span><h2>Platform overview<span className="heading-dot">.</span></h2><p className="muted">A clear view of your asset catalog and account access.</p></div><span className="badge admin-access"><Icon name="shield" size={15} />Administrator access</span></div>
    <article className="admin-hero card"><div className="icon-tile"><Icon name="shield" size={28} /></div><div><span className="eyebrow">Manage with confidence</span><h3>Your platform. In perspective.</h3><p className="muted">Keep investment opportunities current and account access in your control.</p></div></article>
    <div className="admin-kpis"><Resource resource={assets}><div className="summary-cards">{[['All assets', assets.data?.length, 'assets'], ['Active assets', assets.data?.filter(asset => asset.assetStatus === 'ACTIVE').length, 'check'], ['Inactive assets', assets.data?.filter(asset => asset.assetStatus === 'INACTIVE').length, 'clock']].map(([label, value, icon]) => <article className="summary-card kpi-card" key={label}><div className="kpi-heading"><span>{label}</span><span className="icon-tile"><Icon name={icon} size={18} /></span></div><h3>{value}</h3><p>Current asset catalog</p></article>)}</div></Resource><Resource resource={users}><article className="summary-card kpi-card"><div className="kpi-heading"><span>Registered users</span><span className="icon-tile"><Icon name="users" size={18} /></span></div><h3>{users.data?.totalElements}</h3><p>All registered accounts</p></article></Resource></div>
    <div className="analytics-grid admin-management"><article className="card"><div className="panel-heading"><span className="icon-tile"><Icon name="assets" /></span><span className="badge">Asset catalog</span></div><h3>Investment opportunities</h3><p className="muted">Create and maintain assets, update prices, and manage availability for investors.</p><button className="secondary" onClick={() => navigate('admin-assets')}>Manage assets <Icon name="arrow" size={17} /></button></article><article className="card"><div className="panel-heading"><span className="icon-tile"><Icon name="users" /></span><span className="badge">Account access</span></div><h3>Your investor community</h3><p className="muted">Review registered accounts and safely manage activation and deactivation.</p><button className="secondary" onClick={() => navigate('admin-users')}>Manage users <Icon name="arrow" size={17} /></button></article></div>
    <ExecutePendingOrders api={api} refresh={refresh} role={user?.role} />
  </section>
}

export function AdminAuditLogs({ api, revision }) {
  const [page, setPage] = useState(0)
  const resource = useResource(api, `/admin/audit-logs?${query({ page, size: 10 })}`, revision)
  const data = resource.data
  return <section className="card">
    <div className="page-heading"><div><span className="eyebrow">Administration / Activity</span><h2>Audit Logs</h2><p className="muted">A read-only record of system activity, newest first.</p></div><span className="icon-tile"><Icon name="clock" /></span></div>
    <Resource resource={resource} empty={!data?.content?.length} emptyText={data?.totalElements ? 'No audit logs on this page. Use Previous to view earlier pages.' : 'No audit logs yet.'}>
      <Table label="Admin audit logs">
        <thead><tr>{['Date & Time', 'User Email', 'Action', 'Details'].map(label => <th scope="col" key={label}>{label}</th>)}</tr></thead>
        <tbody>{data?.content?.map(entry => <tr key={entry.id}>
          <td><time dateTime={entry.createdAt}>{date(entry.createdAt)}</time></td>
          <td>{entry.userEmail}</td>
          <td><span className="badge">{entry.action}</span></td>
          <td>{entry.details || '—'}</td>
        </tr>)}</tbody>
      </Table>
    </Resource>
    {!resource.error && <Pager page={data?.number ?? page} totalPages={data?.totalPages} totalElements={data?.totalElements} disabled={resource.loading || data?.totalPages === 0} onChange={setPage} />}
  </section>
}

const blankAsset = { symbol: '', name: '', assetType: 'STOCK', currentPrice: '' }
function AssetForm({ asset, api, done, cancel }) {
  const action = useAction()
  const submit = async event => {
    event.preventDefault()
    const body = Object.fromEntries(new FormData(event.currentTarget))
    body.symbol = body.symbol.trim().toUpperCase(); body.name = body.name.trim()
    body.currentPrice = Number(body.currentPrice)
    if (!body.symbol || !body.name || !Number.isFinite(body.currentPrice) || body.currentPrice < 0.01) { action.warn('Enter a symbol, name, and price of at least $0.01.'); return }
    if (await action.run(() => api(asset.assetId ? `/admin/assets/${asset.assetId}` : '/admin/assets', { method: asset.assetId ? 'PUT' : 'POST', body }))) done(asset.assetId ? 'Asset updated successfully.' : 'Asset created successfully.')
  }
  return <form onSubmit={submit} className="form-stack sub-form"><h3>{asset.assetId ? 'Edit asset' : 'Add asset'}</h3><Notice action={action} /><Field label="Symbol" name="symbol" defaultValue={asset.symbol} required maxLength={30} /><Field label="Name" name="name" defaultValue={asset.name} required maxLength={200} /><Field label="Asset type" name="assetType" defaultValue={asset.assetType}><option>STOCK</option><option>ETF</option><option>CRYPTO</option></Field><Field label="Current price (USD)" name="currentPrice" type="number" min="0.01" step="any" defaultValue={asset.currentPrice} required /><div className="actions"><button disabled={action.pending}>{action.pending ? 'Saving…' : 'Save asset'}</button><button type="button" className="secondary" disabled={action.pending} onClick={cancel}>Cancel</button></div></form>
}

export function AdminAssets({ api, revision, refresh }) {
  const resource = useResource(api, '/admin/assets', revision)
  const [editing, setEditing] = useState(null)
  const action = useAction()
  const changeStatus = asset => action.run(async () => {
    await api(`/admin/assets/${asset.assetId}/status`, { method: 'PATCH', body: { assetStatus: asset.assetStatus === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE' } })
    refresh()
  }, 'Asset status updated successfully.')
  return <section className="card"><div className="section-heading"><div><span className="eyebrow">Administration / Catalog</span><h2>Asset Management</h2><p className="muted">Curate the assets available to your investors.</p></div><button disabled={action.pending} onClick={() => { action.clear(); setEditing(blankAsset) }}><Icon name="plus" size={17} />Add asset</button></div><Notice action={action} />
    {editing && <AssetForm key={editing.assetId || 'new'} asset={editing} api={api} cancel={() => setEditing(null)} done={message => { setEditing(null); refresh(message) }} />}
    <Resource resource={resource} empty={!resource.data?.length} emptyText="No assets yet. Add your first asset."><Table label="Admin assets"><thead><tr>{['Symbol', 'Name', 'Type', 'Price', 'Status', 'Actions'].map(label => <th scope="col" key={label}>{label}</th>)}</tr></thead><tbody>{resource.data?.map(asset => <tr key={asset.assetId}><th scope="row">{asset.symbol}</th><td>{asset.name}</td><td>{asset.assetType}</td><td>{money(asset.currentPrice)}</td><td><span className={`badge ${asset.assetStatus.toLowerCase()}`}>{asset.assetStatus}</span></td><td><div className="actions"><button className="secondary" disabled={action.pending} onClick={() => { action.clear(); setEditing(asset) }}>Edit</button><button className={asset.assetStatus === 'ACTIVE' ? 'danger' : ''} disabled={action.pending} onClick={() => changeStatus(asset)}>{asset.assetStatus === 'ACTIVE' ? 'Deactivate' : 'Activate'}</button></div></td></tr>)}</tbody></Table></Resource></section>
}

export function AdminUsers({ api, revision, refresh, user }) {
  const [page, setPage] = useState(0)
  const [confirmation, setConfirmation] = useState(null)
  const resource = useResource(api, `/admin/users?${query({ page, size: 10 })}`, revision)
  const action = useAction()
  const changeStatus = async (account, status) => {
    if (await action.run(async () => {
      await api(`/admin/users/${account.id}/status?${query({ status })}`, { method: 'PATCH' })
      refresh()
    }, 'Account status updated successfully.')) setConfirmation(null)
  }
  const data = resource.data
  return <section className="card"><div className="page-heading"><div><span className="eyebrow">Administration / Accounts</span><h2>User Management</h2><p className="muted">Manage account access with care and confidence.</p></div><span className="icon-tile"><Icon name="users" /></span></div><Notice action={action} />
    {confirmation && <div className="confirmation" role="group" aria-label="Confirm account deactivation"><h3><Icon name="shield" size={20} />Deactivate account?</h3><p>Deactivate {confirmation.emailAddress}? This user will lose access to their account.</p><div className="actions"><button className="danger" disabled={action.pending} onClick={() => changeStatus(confirmation, 'INACTIVE')}>Confirm deactivation</button><button className="secondary" disabled={action.pending} onClick={() => { setConfirmation(null); action.clear() }}>Cancel</button></div></div>}
    <Resource resource={resource} empty={!data?.content?.length} emptyText="No users found."><Table label="Admin users"><thead><tr>{['Name', 'Email', 'Role', 'Status', 'Actions'].map(label => <th scope="col" key={label}>{label}</th>)}</tr></thead><tbody>{data?.content?.map(account => {
      const self = account.emailAddress.toLowerCase() === user.emailAddress.toLowerCase()
      return <tr key={account.id}><th scope="row">{account.firstName} {account.lastName}</th><td>{account.emailAddress}{self && ' (you)'}</td><td><span className={`badge ${account.role === 'ADMIN' ? 'admin-access' : ''}`}>{account.role}</span></td><td><span className={`badge ${account.status.toLowerCase()}`}>{account.status}</span></td><td><div className="actions">{account.status !== 'ACTIVE' && <button disabled={action.pending} onClick={() => { setConfirmation(null); changeStatus(account, 'ACTIVE') }}>Activate</button>}{account.status !== 'INACTIVE' && <button className="danger" disabled={self || action.pending} title={self ? 'You cannot deactivate your own account' : undefined} onClick={() => { action.clear(); setConfirmation(account) }}>Deactivate</button>}</div></td></tr>
    })}</tbody></Table></Resource>
    {!resource.error && <Pager page={page} totalPages={data?.totalPages} totalElements={data?.totalElements} disabled={resource.loading || action.pending} onChange={value => { setPage(value); action.clear(); setConfirmation(null) }} />}
  </section>
}
