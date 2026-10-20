export const money = value => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(Number(value || 0))
export const date = value => value ? new Date(value).toLocaleString() : '—'
