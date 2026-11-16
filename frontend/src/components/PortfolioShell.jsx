import { useCallback, useEffect, useState } from 'react'
import { Holdings, Transactions, Watchlist, GlobalNotice } from '../pages/InvestorPages'
import MarketPage from '../pages/MarketPage'
import DashboardPage from '../pages/DashboardPage'
import ProfilePage from '../pages/ProfilePage'
import PriceAlertsPage from '../pages/PriceAlertsPage'
import OrdersPage from '../pages/OrdersPage'
import WalletPage from '../pages/WalletPage'
import HelpPage from '../pages/HelpPage'
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
  return <p className="balance" role="status"><Icon name="wallet" size={15} />{resource.loading ? 'Updating cash balance…' : resource.error ? 'Cash balance temporarily unavailable' : `Cash Balance: ${money(resource.data?.cashBalance)}`}</p>
}

export default function PortfolioShell({ session }) {
  const [requestedSection, setSection] = useState(() => location.pathname === '/wallet' && session.user.role === 'USER' ? 'wallet' : workspaceFor(session.user.role)?.home || '')
  const [mobileOpen, setMobileOpen] = useState(false)
  const [revision, setRevision] = useState(0)
  const [priceAlertRevision, setPriceAlertRevision] = useState(0)
  const onPriceAlertTriggered = useCallback(() => setPriceAlertRevision(value => value + 1), [])
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
    document.querySelectorAll('.orbit-header details[open]').forEach(menu => { menu.open = false })
    if (value === 'wallet') window.history.replaceState(null, '', '/wallet')
    else if (location.pathname === '/wallet') window.history.replaceState(null, '', '/')
    requestAnimationFrame(() => {
      const content = document.getElementById('main-content')
      content?.scrollTo(0, 0)
      content?.focus()
    })
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
      const controls = [...sidebar.querySelectorAll('button:not(:disabled), summary')].filter(element => element.getClientRects().length)
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
  useEffect(() => {
    const close = event => {
      document.querySelectorAll('.orbit-header details[open]').forEach(menu => {
        if (event.type === 'keydown' && event.key === 'Escape') {
          menu.open = false
          menu.querySelector('summary')?.focus()
        } else if (event.type === 'pointerdown' && !menu.contains(event.target)) menu.open = false
      })
    }
    document.addEventListener('pointerdown', close)
    document.addEventListener('keydown', close)
    return () => { document.removeEventListener('pointerdown', close); document.removeEventListener('keydown', close) }
  }, [])
  const shared = { api: session.api, revision, orderRevision, priceAlertRevision, refresh, navigate }
  const pages = admin
    ? { admin: AdminDashboard, 'admin-assets': AdminAssets, 'admin-users': AdminUsers, 'admin-audit-logs': AdminAuditLogs, profile: ProfilePage }
    : { dashboard: DashboardPage, assets: MarketPage, holdings: Holdings, transactions: Transactions, orders: OrdersPage, wallet: WalletPage, watchlist: Watchlist, 'price-alerts': PriceAlertsPage, help: HelpPage, profile: ProfilePage }
  const Page = workspace ? pages[section] : null
  const currentLabel = workspace?.navigation.find(([value]) => value === section)?.[1]
  const links = entries => entries.map(([value, label, icon]) => (
    <button key={value} aria-current={section === value ? 'page' : undefined} aria-label={label} onClick={() => navigate(value)}><Icon name={icon} size={19} /><span className="nav-text">{({ holdings: 'Holdings', orders: 'Orders', wallet: 'Wallet' })[value] || label}</span></button>
  ))
  const entries = values => workspace.navigation.filter(([value]) => values.includes(value))
  if (!Page) return <main className="login-page"><div className="login-card"><Alert>We could not confirm your account role. Please sign in again.</Alert><button onClick={() => session.logout()}>Return to login</button></div></main>
  return <TransactionFeedbackProvider value={transactionFeedback}><div className={`dashboard orbit-shell ${!admin && section === 'wallet' ? 'wallet-shell' : ''}`}>
    <a className="skip-link" href="#main-content">Skip to content</a>
    {mobileOpen && <button className="nav-backdrop" onClick={() => setMobileOpen(false)} aria-label="Close navigation" />}
    <div className="dashboard-main"><header className={`topbar orbit-header ${admin ? 'admin-header' : 'investor-header'}`}><a className="orbit-brand" href="#main-content" onClick={event => { event.preventDefault(); navigate(workspace.home) }}><Icon name="logo" size={30} /><strong>Investment Portfolio</strong></a><span className="orbit-account-type">{admin ? 'Administration' : 'Virtual account'}</span>
      <button className="orbit-menu-toggle icon-button" aria-label="Menu" aria-expanded={mobileOpen} aria-controls="primary-navigation" onClick={() => setMobileOpen(value => !value)}><Icon name="menu" /></button>
      <nav className={`orbit-navigation ${mobileOpen ? 'is-open' : ''}`} id="primary-navigation" aria-label="Main navigation" role={mobileOpen ? 'dialog' : undefined} aria-modal={mobileOpen || undefined}><button className="orbit-menu-close icon-button" aria-label="Close navigation" onClick={() => setMobileOpen(false)}><Icon name="close" /></button>
        {links(entries(admin ? ['admin', 'admin-assets', 'admin-users', 'admin-audit-logs'] : ['dashboard', 'assets', 'holdings', 'transactions', 'orders', 'wallet', 'watchlist', 'price-alerts', 'help']))}
      </nav><h1 className="sr-only">{currentLabel}</h1>
      <div className="header-account">{workspace.investmentNotifications && <Notifications session={session} onLimitOrderExecuted={onLimitOrderExecuted} onPriceAlertTriggered={onPriceAlertTriggered} />}<details className="orbit-account-menu"><summary aria-label="Account menu"><span className="account-avatar">{session.user.emailAddress.charAt(0).toUpperCase()}</span><span aria-hidden="true">⌄</span></summary><div className="orbit-nav-popover"><p>{session.user.emailAddress}</p>{links(entries(['profile']))}<button aria-label="Logout" onClick={() => session.logout()}><Icon name="logout" size={18} />Logout</button></div></details></div></header>
      <main id="main-content" className={`content ${!admin && section === 'dashboard' ? 'overview-content' : 'legacy-content'}`} tabIndex={-1}>{!admin && section !== 'dashboard' && section !== 'wallet' && <CashBalance api={session.api} revision={revision} />}<GlobalNotice message={message} /><GlobalNotice message={transactionFallback?.text} /><div className="page-enter" key={section}><Page {...shared} user={session.user} /></div>{section !== 'dashboard' && <footer className="workspace-footer"><span>Investment Portfolio</span><span>Practice with purpose. Invest with perspective.</span></footer>}</main>
    </div>
  </div></TransactionFeedbackProvider>
}
