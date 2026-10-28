import { useCallback, useEffect, useState } from 'react'
import { Holdings, Transactions, Watchlist, GlobalNotice } from '../pages/InvestorPages'
import MarketPage from '../pages/MarketPage'
import DashboardPage from '../pages/DashboardPage'
import ProfilePage from '../pages/ProfilePage'
import OrdersPage from '../pages/OrdersPage'
import { AdminDashboard, AdminAssets, AdminUsers, AdminAuditLogs } from '../pages/AdminPages'
import Notifications from './Notifications'
import Icon from './Icon'
import { useResource } from '../hooks/useResource'
import { money } from '../api/format'
import { sectionFor, workspaceFor } from '../auth/roles'
import { Alert } from './UI'
import { createTransactionFeedback } from '../api/transactionFeedback'
import { TransactionFeedbackProvider } from '../hooks/TransactionFeedback'

function CashBalance({ api, revision }) {
  const resource = useResource(api, '/portfolio/performance', revision)
  return <p className="balance" role="status"><Icon name="wallet" size={15} />{resource.loading ? 'Updating cash balance…' : resource.error ? 'Cash balance temporarily unavailable' : `Available cash: ${money(resource.data?.cashBalance)}`}</p>
}

export default function PortfolioShell({ session }) {
  const [requestedSection, setSection] = useState(() => workspaceFor(session.user.role)?.home || '')
  const [mobileOpen, setMobileOpen] = useState(false)
  const [collapsed, setCollapsed] = useState(false)
  const [revision, setRevision] = useState(0)
  const [orderRevision, setOrderRevision] = useState(0)
  // Keep this stable so a refreshed order list does not reconnect the SSE stream.
  const onLimitOrderExecuted = useCallback(() => setOrderRevision(value => value + 1), [])
  const [message, setMessage] = useState('')
  const [transactionFallback, setTransactionFallback] = useState(null)
  const [transactionFeedback] = useState(() => createTransactionFeedback(setTransactionFallback, id => setTransactionFallback(previous => previous?.id === id ? null : previous)))
  useEffect(() => () => transactionFeedback.dispose(), [transactionFeedback])
  const admin = session.user.role === 'ADMIN'
  const workspace = workspaceFor(session.user.role)
  const section = sectionFor(session.user.role, requestedSection)
  const refresh = (notice = '') => { setRevision(value => value + 1); setMessage(notice) }
  const navigate = value => {
    if (!workspace?.navigation.some(([allowed]) => allowed === value)) return
    setSection(value); setMessage(''); setMobileOpen(false)
    requestAnimationFrame(() => document.getElementById('main-content')?.focus())
  }
  useEffect(() => {
    if (!mobileOpen) return
    const previousFocus = document.activeElement
    const sidebar = document.getElementById('primary-navigation')
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    sidebar.querySelector('button')?.focus()
    const keyboard = event => {
      if (event.key === 'Escape') setMobileOpen(false)
      if (event.key !== 'Tab') return
      const controls = [...sidebar.querySelectorAll('button:not(:disabled)')].filter(element => element.getClientRects().length)
      const first = controls[0], last = controls[controls.length - 1]
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
      else if (!sidebar.contains(document.activeElement)) { event.preventDefault(); first?.focus() }
    }
    document.addEventListener('keydown', keyboard)
    return () => {
      document.removeEventListener('keydown', keyboard)
      document.body.style.overflow = previousOverflow
      if (previousFocus?.isConnected) previousFocus.focus()
    }
  }, [mobileOpen])
  const shared = { api: session.api, revision, orderRevision, refresh, navigate }
  const pages = admin
    ? { admin: AdminDashboard, 'admin-assets': AdminAssets, 'admin-users': AdminUsers, 'admin-audit-logs': AdminAuditLogs, profile: ProfilePage }
    : { dashboard: DashboardPage, assets: MarketPage, holdings: Holdings, transactions: Transactions, orders: OrdersPage, watchlist: Watchlist, profile: ProfilePage }
  const Page = workspace ? pages[section] : null
  const currentLabel = workspace?.navigation.find(([value]) => value === section)?.[1]
  const links = entries => entries.map(([value, label, icon]) => (
    <button key={value} aria-current={section === value ? 'page' : undefined} aria-label={label} title={collapsed ? label : undefined} onClick={() => navigate(value)}><Icon name={icon} size={19} /><span className="nav-text">{label}</span>{section === value && <span className="nav-active-dot" />}</button>
  ))
  if (!Page) return <main className="login-page"><div className="login-card"><Alert>We could not confirm your account role. Please sign in again.</Alert><button onClick={() => session.logout()}>Return to login</button></div></main>
  return <TransactionFeedbackProvider value={transactionFeedback}><div className={`dashboard ${collapsed ? 'sidebar-collapsed' : ''}`}>
    <a className="skip-link" href="#main-content">Skip to content</a>
    {mobileOpen && <button className="nav-backdrop" onClick={() => setMobileOpen(false)} aria-label="Close navigation" />}
    <aside className={`sidebar ${mobileOpen ? 'is-open' : ''}`} id="primary-navigation" role={mobileOpen ? 'dialog' : undefined} aria-modal={mobileOpen || undefined} aria-label="Application sidebar">
      <div className="brand"><div className="brand-icon"><Icon name="logo" size={25} /></div><div className="brand-text"><h2>Virtual Portfolio</h2><span>INVESTMENT PLATFORM</span></div><button className="sidebar-close icon-button" aria-label="Close navigation" onClick={() => setMobileOpen(false)}><Icon name="close" /></button></div>
      <nav aria-label="Main navigation"><p className="nav-label">{admin ? 'Administration' : 'Workspace'}</p>{links(workspace.navigation)}</nav>
      <div className="sidebar-bottom"><div className="practice-note"><Icon name="shield" size={20} /><div><strong>{admin ? 'Administration workspace' : 'Built for your next move'}</strong><p>{admin ? 'Manage assets and account access.' : 'A virtual account. Real experience.'}</p></div></div><button className="collapse-button secondary" onClick={() => setCollapsed(value => !value)} aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} aria-expanded={!collapsed}><Icon name="collapse" /><span className="nav-text">Collapse sidebar</span></button></div>
    </aside>
    <div className="dashboard-main"><header className="topbar"><button className="mobile-menu icon-button" aria-label="Menu" aria-expanded={mobileOpen} aria-controls="primary-navigation" onClick={() => setMobileOpen(value => !value)}><Icon name="menu" /></button><div className="topbar-title"><span className="breadcrumb">{admin ? 'Administration' : 'Workspace'} <span>/</span></span><h1>{currentLabel}</h1></div><div className="header-account"><span className="account-mode"><span className="live-dot" />{admin ? 'Admin workspace' : 'Virtual account'}</span>{workspace.investmentNotifications && <Notifications session={session} onLimitOrderExecuted={onLimitOrderExecuted} />}<div className="account-identity"><span className="account-avatar">{session.user.emailAddress.charAt(0).toUpperCase()}</span><div><strong>{session.user.emailAddress}</strong><span>{admin ? 'Administrator' : 'Investor account'}</span></div></div><button className="icon-button logout-button" aria-label="Logout" title="Logout" onClick={() => session.logout()}><Icon name="logout" size={19} /></button></div></header>
      <main id="main-content" className="content" tabIndex={-1}>{!admin && section !== 'dashboard' && <CashBalance api={session.api} revision={revision} />}<GlobalNotice message={message} /><GlobalNotice message={transactionFallback?.text} /><div className="page-enter" key={section}><Page {...shared} user={session.user} /></div><footer className="workspace-footer"><span>Virtual Investment Portfolio</span><span>Practice with purpose. Invest with perspective.</span></footer></main>
    </div>
  </div></TransactionFeedbackProvider>
}
