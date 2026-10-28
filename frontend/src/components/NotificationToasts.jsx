import { useEffect, useLayoutEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import Icon from './Icon'

function Toast({ item, onDismiss }) {
  useEffect(() => {
    const timer = setTimeout(() => onDismiss(item.id), 5000)
    return () => clearTimeout(timer)
  }, [item.id, onDismiss])
  return <div className="notification-toast"><Icon name="check" size={19} /><p>{item.text}</p><button type="button" className="icon-button" aria-label={`Dismiss notification: ${item.text}`} onClick={() => onDismiss(item.id)}><Icon name="close" size={16} /></button></div>
}

export default function NotificationToasts({ items, onDismiss }) {
  const [host] = useState(() => document.createElement('div'))
  useLayoutEffect(() => {
    const placeHost = () => {
      // Native modal dialogs cover and make the rest of the body inert, regardless
      // of z-index. Keep the same portal host inside the active modal when needed.
      const parent = Array.from(document.querySelectorAll('dialog:modal')).at(-1) || document.body
      if (host.parentNode !== parent) parent.appendChild(host)
    }
    placeHost()
    const observer = new MutationObserver(placeHost)
    observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['open'] })
    return () => { observer.disconnect(); host.remove() }
  }, [host])
  return createPortal(<div className="notification-toasts" aria-live="polite" aria-relevant="additions" aria-atomic="false">{items.map(item => <Toast key={item.id} item={item} onDismiss={onDismiss} />)}</div>, host)
}
