import { useEffect, useId, useRef } from 'react'

export default function ConfirmDialog({ title, children, confirmLabel, cancelLabel = 'Go back', pending, destructive = false, onConfirm, onClose }) {
  const dialog = useRef(null)
  const titleId = useId()
  useEffect(() => {
    const element = dialog.current
    element.showModal()
    return () => element.close()
  }, [])
  return <dialog ref={dialog} className="confirmation-dialog" aria-labelledby={titleId} onCancel={event => { event.preventDefault(); if (!pending) onClose() }}>
    <h3 id={titleId}>{title}</h3>
    <div className="confirmation-content">{children}</div>
    <div className="actions"><button className="secondary" type="button" disabled={pending} onClick={onClose} autoFocus>{cancelLabel}</button><button className={destructive ? 'danger' : ''} type="button" disabled={pending} onClick={onConfirm}>{pending ? 'Please wait…' : confirmLabel}</button></div>
  </dialog>
}
