import { useId, useMemo, useRef, useState, useEffect } from 'react'
import { historyScale } from '../api/market'
import { date, money } from '../api/format'

export default function PriceHistoryChart({ points, symbol }) {
  const container = useRef(null)
  const [width, setWidth] = useState(800)
  const [active, setActive] = useState(null)
  const descriptionId = useId()
  useEffect(() => {
    const observer = new ResizeObserver(entries => setWidth(Math.max(240, entries[0].contentRect.width)))
    observer.observe(container.current)
    return () => observer.disconnect()
  }, [])
  const scale = useMemo(() => historyScale(points), [points])
  const compact = width < 520
  const height = 310, left = 70, right = 34, top = 18, bottom = compact ? 70 : 46
  const plotWidth = width - left - right
  const plotHeight = height - top - bottom
  const first = points[0].time, last = points[points.length - 1].time
  const x = point => first === last ? left + plotWidth / 2 : left + (point.time - first) / (last - first) * plotWidth
  const y = point => top + (scale.max - point.price) / (scale.max - scale.min) * plotHeight
  const line = points.map((point, index) => `${index === 0 ? 'M' : 'L'}${x(point)},${y(point)}`).join(' ')
  // Axis ticks use evenly spaced times, independently of the full price series.
  // Narrow charts stagger labels on two rows so all five remain readable.
  const ticks = Array.from({ length: 5 }, (_, index) => ({
    time: first + (last - first) * index / 4,
    x: left + plotWidth * index / 4,
  }))
  const selected = active == null ? null : points[Math.min(active, points.length - 1)]
  const pointer = event => {
    const rectangle = event.currentTarget.getBoundingClientRect()
    const coordinate = (event.clientX - rectangle.left) / rectangle.width * width
    let closest = 0
    for (let index = 1; index < points.length; index++) if (Math.abs(x(points[index]) - coordinate) < Math.abs(x(points[closest]) - coordinate)) closest = index
    setActive(closest)
  }
  const keyboard = event => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
    event.preventDefault()
    setActive(previous => event.key === 'Home' ? 0 : event.key === 'End' ? points.length - 1 : Math.max(0, Math.min(points.length - 1, (previous ?? points.length - 1) + (event.key === 'ArrowLeft' ? -1 : 1))))
  }
  return <div className="price-history-chart" ref={container} tabIndex={0} role="group" aria-label={`${symbol} price history. Use Left and Right arrows to inspect recorded prices.`} aria-describedby={descriptionId} onKeyDown={keyboard} onFocus={() => setActive(points.length - 1)} onBlur={() => setActive(null)} onPointerLeave={() => { if (document.activeElement !== container.current) setActive(null) }}>
    <p id={descriptionId} className="sr-only">{points.length} actual recorded prices, from {date(points[0].recordedAt)} to {date(points[points.length - 1].recordedAt)}. Hover, touch, or use the arrow keys to inspect values.</p>
    <svg viewBox={`0 0 ${width} ${height}`} width="100%" height={height} className="price-chart-svg" role="img" aria-label={`${symbol} recorded price in USD over time`} onPointerMove={pointer} onPointerDown={pointer}>
      {Array.from({ length: 5 }, (_, index) => { const price = scale.min + (scale.max - scale.min) * index / 4; const coordinate = y({ price }); return <g key={index}><line x1={left} y1={coordinate} x2={width - right} y2={coordinate} className="price-chart-grid" /><text x={left - 10} y={coordinate + 4} textAnchor="end" className="price-chart-label">{money(price)}</text></g> })}
      {ticks.map((tick, index) => <text key={index} x={tick.x} y={height - bottom + 24 + (compact ? index % 2 * 16 : 0)} textAnchor="middle" className="price-chart-label price-chart-time">{new Date(tick.time).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true })}</text>)}
      <text x={left} y={height - 2} className="price-chart-axis">Recorded time</text>
      <path d={line} className="price-chart-line" />
      {points.length === 1 && <circle cx={x(points[0])} cy={y(points[0])} r="4" className="price-chart-point" />}
      {selected && <g><line x1={x(selected)} y1={top} x2={x(selected)} y2={height - bottom} className="price-chart-crosshair" /><circle cx={x(selected)} cy={y(selected)} r="5" className="price-chart-point" /></g>}
    </svg>
    {selected && <div className="price-chart-tooltip" style={{ left: Math.max(0, Math.min(width - Math.min(245, width), x(selected) - 100)) }}><strong>{money(selected.price)}</strong><time dateTime={selected.recordedAt}>{selected.recordedAt.replace('T', ' ')}</time></div>}
    <div className="price-chart-readout" role="status">{selected ? <><strong>{money(selected.price)}</strong><time dateTime={selected.recordedAt}>{selected.recordedAt.replace('T', ' ')}</time></> : <span>Hover, tap, or focus the chart to inspect an actual recorded price.</span>}</div>
  </div>
}
