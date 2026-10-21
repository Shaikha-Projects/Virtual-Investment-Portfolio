export const API_BASE = (import.meta.env?.VITE_API_BASE_URL || 'http://localhost:8081').replace(/\/$/, '')
const safeMessages = new Set([
  'Invalid email or password', 'Please verify your email before logging in', 'Account is inactive',
  'Current password is not correct', 'Asset symbol already exists', 'Insufficient funds',
  'Insufficient quantity to sell', 'Asset is not available for purchase',
  'Cannot add inactive asset to watchlist', 'Asset is already in your watchlist',
  'Admins cannot deactivate their own accounts.', 'User is already active.', 'User is already inactive.',
  'Only image files are allowed', 'Profile picture cannot be empty', 'Image file must have a valid extension',
  'Email address has already been verified', 'Verification token has expired', 'Reset Password token has expired',
  'Asset is not available for trading', 'Insufficient available cash for this order',
  'You do not own this asset', 'Insufficient available shares for this order',
  'You cannot cancel this order', 'Only pending orders can be cancelled', 'Insufficient reserved shares',
])
const messages = {
  400: 'Please check your information and try again.', 401: 'Please log in again to continue.',
  403: 'You do not have permission to perform this action.', 404: 'The requested item is no longer available.',
  409: 'This information already exists or has changed. Please refresh and try again.',
  410: 'This link has expired. Please request a new one.', 413: 'Choose an image under 5 MB.',
  429: 'Too many attempts. Please wait a minute and try again.',
}
export class ApiError extends Error {
  constructor(message, status = 0) { super(message); this.name = 'ApiError'; this.status = status }
}
export function errorMessage(status, body) {
  if (status < 500 && safeMessages.has(body?.message)) return body.message
  return messages[status] || 'The service is temporarily unavailable. Please try again shortly.'
}
export function friendlyError(error) {
  return error instanceof ApiError ? error.message : 'Something went wrong. Please try again.'
}
export function query(values) {
  return new URLSearchParams(Object.entries(values).filter(([, value]) => value !== '' && value != null)).toString()
}
export function createApi({ token = '', onInvalid = () => {}, signal: sessionSignal } = {}) {
  return async function request(path, { body, signal, responseType = 'json', ...options } = {}) {
    const headers = new Headers(options.headers)
    if (token) headers.set('Authorization', `Bearer ${token}`)
    if (body != null && !(body instanceof FormData)) headers.set('Content-Type', 'application/json')
    const timeout = AbortSignal.timeout(20000)
    const combined = AbortSignal.any([timeout, sessionSignal, signal].filter(Boolean))
    let response
    try {
      response = await fetch(`${API_BASE}${path}`, { ...options, headers,
        body: body == null || body instanceof FormData ? body : JSON.stringify(body), signal: combined })
    } catch {
      if (sessionSignal?.aborted || signal?.aborted) throw new DOMException('Cancelled', 'AbortError')
      throw new ApiError(timeout.aborted ? 'The service took too long to respond. Please try again.' : 'Unable to connect. Check your connection and try again.')
    }
    if (combined.aborted) throw new DOMException('Cancelled', 'AbortError')
    if (token && (response.status === 401 || response.status === 403)) {
      let invalid = response.status === 401 || path === '/auth/me'
      if (!invalid) {
        try {
          const identity = await fetch(`${API_BASE}/auth/me`, { headers: { Authorization: `Bearer ${token}` }, signal: combined })
          invalid = identity.status === 401 || identity.status === 403
        } catch { /* Network failure does not prove the session is invalid. */ }
      }
      if (invalid && !combined.aborted) onInvalid()
    }
    if (response.ok && responseType === 'text') {
      const text = await response.text()
      if (combined.aborted) throw new DOMException('Cancelled', 'AbortError')
      return text
    }
    let data = null
    try { data = await response.json() } catch {
      if (response.ok && response.status !== 204) throw new ApiError('The service returned an unexpected response. Please try again.', response.status)
    }
    if (combined.aborted) throw new DOMException('Cancelled', 'AbortError')
    if (!response.ok) throw new ApiError(errorMessage(response.status, data), response.status)
    return data
  }
}
export const publicApi = createApi()
export function pictureUrl(path) {
  return path ? `${API_BASE}/${path.replace(/\\/g, '/').replace(/^\/+/, '')}` : ''
}
