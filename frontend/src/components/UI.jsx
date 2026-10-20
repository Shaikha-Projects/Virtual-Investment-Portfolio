import Icon from './Icon'
import { useId } from 'react'

export function Alert({ kind = 'error', children }) {
  return children ? <div className={`alert alert-${kind}`} role={kind === 'error' ? 'alert' : 'status'}><Icon name={kind === 'success' ? 'check' : 'info'} size={18} /><span>{children}</span></div> : null
}
export function Field({ label, children, ...props }) {
  const id = useId()
  return <label className="field" htmlFor={id}><span>{label}</span>{children ? <select id={id} {...props}>{children}</select> : <input id={id} {...props} />}</label>
}
export function Resource({ resource, empty = false, emptyText = 'No items found.', children }) {
  if (resource.loading) return <div role="status" className="empty loading-state"><span className="loading-spinner" aria-hidden="true" />Loading…</div>
  if (resource.error) return <><Alert>{resource.error}</Alert><button onClick={resource.reload}>Try again</button></>
  if (empty) return <div className="empty empty-state"><span className="icon-tile"><Icon name="holdings" size={24} /></span><p>{emptyText}</p></div>
  return children
}
export function Table({ children, label }) {
  return <div className="table-scroll" role="region" aria-label={label} tabIndex={0}><table>{children}</table></div>
}
export function Pager({ page, totalPages = 0, totalElements = 0, onChange, disabled = false }) {
  return <div className="pager" aria-label="Pagination"><span>{totalElements} total · Page {totalPages ? page + 1 : 0} of {totalPages}</span><div className="actions"><button disabled={disabled || page === 0} onClick={() => onChange(page - 1)}>Previous</button><button disabled={disabled || page + 1 >= totalPages} onClick={() => onChange(page + 1)}>Next</button></div></div>
}
export function Notice({ action }) { return action.notice && <Alert kind={action.notice.kind}>{action.notice.text}</Alert> }
