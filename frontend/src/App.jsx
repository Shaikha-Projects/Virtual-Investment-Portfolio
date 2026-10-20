import { useSession } from './hooks/useSession'
import { Alert } from './components/UI'
import AuthPage from './pages/AuthPage'
import PortfolioShell from './components/PortfolioShell'
import './styles.css'

export default function App() {
  const session = useSession()
  if (session.checking) return <main className="login-page"><div className="login-card" role="status">Checking your session…</div></main>
  if (session.user) return <PortfolioShell key={JSON.stringify([session.token, session.user.emailAddress, session.user.role])} session={session} />
  if (session.token && !new URLSearchParams(location.search).has('token')) {
    return <main className="login-page"><div className="login-card"><h1>Account access unavailable</h1><Alert>{session.error}</Alert><div className="actions"><button onClick={session.retry}>Retry session check</button><button className="secondary" onClick={() => session.logout()}>Return to login</button></div></div></main>
  }
  return <AuthPage session={session} />
}
