import { isPriceAlertNotification } from '../api/priceAlerts'
import { walletNotification } from '../api/wallet'
import Icon from './Icon'
import { useCallback, useEffect, useRef, useState } from 'react'
import NotificationToasts from './NotificationToasts'
import { createSseParser } from '../api/sse'
import { useTransactionFeedback } from '../hooks/TransactionFeedback'

const NOTIFICATION_BASE = (import.meta.env?.VITE_NOTIFICATION_BASE_URL || 'http://localhost:8083').replace(/\/$/, '')

export default function Notifications({ session, onLimitOrderExecuted, onPriceAlertTriggered }) {
  const [items, setItems] = useState([])
  const [toasts, setToasts] = useState([])
  const dismissToast = useCallback(id => setToasts(previous => previous.filter(item => item.id !== id)), [])
  const [status, setStatus] = useState('Connecting')
  const [open, setOpen] = useState(false)
  const walletSeen = useRef(new Set())
  const { token, api } = session
  const feedback = useTransactionFeedback()
  useEffect(() => {
    const controller = new AbortController()
    const seen = new Set()
    let retryTimer
    let alertRefreshTimer
    let orderRefreshTimer
    let retry = 1000
    let superseded = false
    async function connect() {
      while (!controller.signal.aborted) {
        let reader
        try {
          const response = await fetch(`${NOTIFICATION_BASE}/notifications/subscribe`, { headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' }, signal: controller.signal })
          if (controller.signal.aborted) return
          if (response.status === 401 || response.status === 403) {
            await api('/auth/me', { signal: controller.signal })
            throw new Error('Notification access unavailable')
          }
          if (!response.ok || !response.headers.get('content-type')?.includes('text/event-stream')) throw new Error('Stream unavailable')
          setStatus('Live'); retry = 1000
          const parse = createSseParser(event => {
            if (controller.signal.aborted) return
            if (event.event === 'subscription-replaced') {
              superseded = true
              setStatus('Live notifications are active in another tab.')
              return
            }
            if (event.event !== 'investment-notification') return
            if (event.id && seen.has(event.id)) return
            if (event.id) { seen.add(event.id); if (seen.size > 200) seen.delete(seen.values().next().value) }
            // keep wallet references across reconnects during this session
            const wallet = walletNotification(event.data, walletSeen.current)
            if (!wallet && !isPriceAlertNotification(event.data) && !/^(?:(?:Purchase|Sale) completed|Limit (?:BUY|SELL) order executed): [0-9.]+ shares of [A-Za-z0-9.^_/-]{1,30}$/.test(event.data)) return
            if (isPriceAlertNotification(event.data)) {
              clearTimeout(alertRefreshTimer)
              alertRefreshTimer = setTimeout(() => { if (!controller.signal.aborted) onPriceAlertTriggered?.() }, 200)
            }
            const item = { id: wallet ? `wallet:${wallet.reference}` : event.id || crypto.randomUUID(), text: wallet ? wallet.text : event.data }
            feedback?.received(event.data)
            setItems(previous => [item, ...previous].slice(0, 30))
            setToasts(previous => [...previous, item].slice(-3))
            if (/^Limit (BUY|SELL) order executed: /.test(event.data)) {
              // Several orders can execute together; refresh once after the burst.
              clearTimeout(orderRefreshTimer)
              orderRefreshTimer = setTimeout(() => {
                if (!controller.signal.aborted) onLimitOrderExecuted?.()
              }, 200)
            }
          })
          reader = response.body.getReader()
          const decoder = new TextDecoder()
          while (!controller.signal.aborted) {
            const { value, done } = await reader.read()
            if (done) break
            parse(decoder.decode(value, { stream: true }))
          }
        } catch { /* Report connection state, never response bodies or credentials. */ }
        finally { await reader?.cancel().catch(() => {}); reader?.releaseLock() }
        if (controller.signal.aborted || superseded) return
        setStatus('Reconnecting — live notifications temporarily unavailable')
        await new Promise(resolve => {
          const finish = () => { clearTimeout(retryTimer); controller.signal.removeEventListener('abort', finish); resolve() }
          retryTimer = setTimeout(finish, retry)
          controller.signal.addEventListener('abort', finish, { once: true })
        })
        retry = Math.min(retry * 2, 30000)
      }
    }
    connect()
    return () => { controller.abort(); clearTimeout(retryTimer); clearTimeout(orderRefreshTimer); clearTimeout(alertRefreshTimer) }
  }, [token, api, onLimitOrderExecuted, onPriceAlertTriggered, feedback])
  return <div className="notifications"><button className="icon-button notification-button" aria-label={`Notifications (${items.length})`} title="Notifications" aria-expanded={open} aria-controls="live-notifications" onClick={() => setOpen(value => !value)}><Icon name="bell" size={21} />{items.length > 0 && <span className="notification-count">{items.length}</span>}</button>
    <NotificationToasts items={toasts} onDismiss={dismissToast} />
    {open && <section id="live-notifications" className="notification-panel" aria-label="Live notifications"><div className="panel-heading"><h2>Notifications</h2><button className="icon-button" aria-label="Close notifications" onClick={() => setOpen(false)}><Icon name="close" size={17} /></button></div><p className="muted" role="status">{status}</p><p className="muted">Live events from this session only.</p>{items.length ? <ul>{items.map(item => <li key={item.id}>{item.text}</li>)}</ul> : <p>No notifications received yet.</p>}<button className="text-button" onClick={() => setItems([])}>Clear notifications</button></section>}
  </div>
}
