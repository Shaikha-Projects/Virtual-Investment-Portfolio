import { useCallback, useEffect, useRef, useState } from 'react'
import { createApi, friendlyError, publicApi } from '../api/client'
import { validateIdentity } from '../auth/roles'

export function useSession() {
  const [token, setToken] = useState(() => localStorage.getItem('token') || '')
  const [user, setUser] = useState(null)
  const [error, setError] = useState('')
  const [checking, setChecking] = useState(() => Boolean(localStorage.getItem('token')) && !new URLSearchParams(location.search).has('token'))
  const [attempt, setAttempt] = useState(0)
  const epoch = useRef(0)
  const requests = useRef(new Set())
  const logout = useCallback((message = '') => {
    epoch.current += 1
    requests.current.forEach(controller => controller.abort())
    requests.current.clear()
    localStorage.removeItem('token')
    setToken(''); setUser(null); setError(message); setChecking(false)
  }, [])
  const invalid = useCallback(() => logout('Your session has expired or your account is no longer active. Please log in again.'), [logout])
  const api = useCallback(async (path, options = {}) => {
    const controller = new AbortController()
    const current = epoch.current
    requests.current.add(controller)
    try {
      const result = await createApi({ token, onInvalid: invalid, signal: controller.signal })(path, options)
      if (current !== epoch.current) throw new DOMException('Cancelled', 'AbortError')
      return result
    } finally { requests.current.delete(controller) }
  }, [token, invalid])
  useEffect(() => {
    const controller = new AbortController()
    if (token && !new URLSearchParams(location.search).has('token')) {
      api('/auth/me', { signal: controller.signal }).then(identity => {
        const verified = validateIdentity(identity)
        if (!controller.signal.aborted) { setUser(verified); setError(''); setChecking(false) }
      }).catch(failure => {
        if (!controller.signal.aborted && failure.name !== 'AbortError') {
          setUser(null); setError(friendlyError(failure)); setChecking(false)
        }
      })
    }
    return () => controller.abort()
  }, [token, api, attempt])
  const login = async credentials => {
    const current = ++epoch.current
    setError('')
    const result = await publicApi('/auth/login', { method: 'POST', body: credentials })
    const identity = validateIdentity(await createApi({ token: result.token })('/auth/me'))
    if (current !== epoch.current) return
    localStorage.setItem('token', result.token)
    setToken(result.token); setUser(identity); setError(''); setChecking(false)
  }
  const retry = () => { setError(''); setChecking(true); setAttempt(value => value + 1) }
  useEffect(() => {
    const sync = event => { if (event.key === 'token' && event.newValue !== token) logout('Your session changed in another tab. Please log in again.') }
    window.addEventListener('storage', sync)
    return () => window.removeEventListener('storage', sync)
  }, [token, logout])
  return { user, token, api, login, logout, checking, error, retry, dismissError: () => setError('') }
}
