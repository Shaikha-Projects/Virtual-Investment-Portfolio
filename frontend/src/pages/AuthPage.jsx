import Icon from '../components/Icon'
import { useAction } from '../hooks/useAction'
import { useState } from 'react'
import { publicApi } from '../api/client'
import { Alert, Field, Notice } from '../components/UI'

export default function AuthPage({ session }) {
  const resetToken = new URLSearchParams(location.search).get('token') || ''
  const [mode, setMode] = useState(resetToken ? 'reset' : 'login')
  const [completed, setCompleted] = useState(false)
  const action = useAction()
  const changeMode = value => { action.clear(); session.dismissError(); setMode(value); setCompleted(false) }
  const submit = async event => {
    event.preventDefault()
    const form = event.currentTarget
    const data = Object.fromEntries(new FormData(form))
    session.dismissError()
    if (mode === 'register' && (!data.firstName.trim() || !data.lastName.trim())) { action.warn('First and last names are required.'); return }
    if (mode === 'login') { await action.run(() => session.login(data)); return }
    const path = { register: '/auth/register', forgot: '/auth/forgot-password', reset: '/auth/reset-password' }[mode]
    if (mode === 'reset') data.token = resetToken
    const success = { register: 'Account created. Check your email to verify your account before logging in.', forgot: 'If your account is eligible, a password reset link will be sent to your email.', reset: 'Password reset successfully. You can now log in.' }[mode]
    if (await action.run(() => publicApi(path, { method: 'POST', body: data }), success)) {
      setCompleted(true)
      form.reset()
      if (mode === 'reset') window.history.replaceState({}, '', location.pathname)
    }
  }
  return <main className="login-page auth-layout">
    <aside className="auth-story"><div className="auth-wordmark"><span className="brand-icon"><Icon name="logo" size={28} /></span><span>Investment Portfolio</span></div><span className="eyebrow">Your investment workspace</span><h2>A clearer view.<br />Of every<br /><em>move.</em></h2><p>Build your portfolio. Understand your positions. Find your perspective with virtual funds.</p><div className="auth-principles"><div><span>01</span><strong>Explore the market</strong><p>Discover assets and follow simulated price movements.</p></div><div><span>02</span><strong>See the whole picture</strong><p>Keep holdings, cash, and recorded performance in view.</p></div><div><span>03</span><strong>Practice with purpose</strong><p>A virtual account, with room to develop your strategy.</p></div></div><p className="auth-story-footer">Virtual funds. A considered approach.</p></aside>
    <section className="login-card">
    <div className="brand-icon" aria-hidden="true"><Icon name="logo" size={26} /></div><h1>Investment Portfolio</h1>
    <p>Build and manage your investments using virtual money.</p>
    <h2>{{ login: 'Welcome back', register: 'Create an account', forgot: 'Forgot password', reset: 'Reset password' }[mode]}</h2>
    {!action.notice && <Alert>{session.error}</Alert>}<Notice action={action} />
    <form key={mode} onSubmit={submit} className="form-stack">
      {mode === 'register' && <><Field label="First name" name="firstName" autoComplete="given-name" required maxLength={100} /><Field label="Last name" name="lastName" autoComplete="family-name" required maxLength={100} /><Field label="Phone number" name="phoneNumber" type="tel" inputMode="numeric" pattern="[0-9]{8}" title="Exactly 8 digits" required /></>}
      {mode !== 'reset' && <Field label="Email address" name="emailAddress" type="email" autoComplete="email" required />}
      {mode !== 'forgot' && <Field label={mode === 'reset' ? 'New password' : 'Password'} name={mode === 'reset' ? 'newPassword' : 'password'} type="password" minLength={mode === 'login' ? undefined : 8} autoComplete={mode === 'login' ? 'current-password' : 'new-password'} required />}
      <button disabled={action.pending || (completed && mode === 'reset')}>{action.pending ? 'Please wait…' : { login: 'Login', register: 'Create account', forgot: 'Send reset link', reset: 'Reset password' }[mode]}</button>
    </form>
    <div className="auth-links">{mode === 'login' ? <><button className="text-button" onClick={() => changeMode('forgot')}>Forgot password?</button><button className="text-button" onClick={() => changeMode('register')}>Create an account</button></> : <button className="text-button" onClick={() => changeMode('login')}>Back to login</button>}</div>
    <p className="auth-security muted"><Icon name="lock" size={14} />Your virtual investing workspace</p>
  </section></main>
}
