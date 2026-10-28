// Optional browser smoke test. Requires a running Vite server and a disposable
// Chrome instance with remote debugging. All backend responses are fixtures.
import assert from 'node:assert/strict'

const appUrl = process.env.SMOKE_APP_URL || 'http://localhost:5176'
const debugUrl = process.env.SMOKE_DEBUG_URL || 'http://localhost:9228'
const target = (await (await fetch(`${debugUrl}/json`)).json()).find(item => item.type === 'page')
if (!target) throw new Error('Start a disposable Chrome instance with remote debugging enabled.')
const socket = new WebSocket(target.webSocketDebuggerUrl)
await new Promise(resolve => socket.addEventListener('open', resolve, { once: true }))
let sequence = 0
const pending = new Map()
const exceptions = []
socket.addEventListener('message', ({ data }) => {
  const message = JSON.parse(data)
  if (message.id) {
    const task = pending.get(message.id)
    pending.delete(message.id)
    if (message.error) task.reject(new Error(message.error.message))
    else task.resolve(message.result)
  }
  if (message.method === 'Runtime.exceptionThrown') exceptions.push(message.params.exceptionDetails.text)
})
function send(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++sequence
    pending.set(id, { resolve, reject })
    socket.send(JSON.stringify({ id, method, params }))
  })
}
async function evaluate(expression) {
  const result = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || 'Browser evaluation failed')
  return result.result.value
}
async function wait(expression) {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (await evaluate(expression)) return
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  throw new Error(`Timed out: ${expression}`)
}
const click = label => evaluate(`([...document.querySelectorAll('button')].find(button => (button.getAttribute('aria-label') || button.textContent.trim()) === ${JSON.stringify(label)})).click()`)
const field = (name, value) => evaluate(`(() => { const input = document.querySelector('input[name="${name}"]'); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, ${JSON.stringify(value)}); input.dispatchEvent(new Event('input', { bubbles: true })); })()`)

const fixture = `(() => {
  const original = window.fetch.bind(window)
  window.__historyCalls = []
  const now = Date.now(), day = 86400000
  window.__history = [40, 20, 4, 0.5].map((age, index) => ({ totalPortfolioValue: 100000 + index * 100, recordedAt: new Date(now - age * day).toISOString() }))
  window.fetch = async (input, options = {}) => {
    const url = new URL(input, location.href)
    if (url.origin === location.origin) return original(input, options)
    const reply = (data, status = 200) => new Response(JSON.stringify(data), { status })
    if (url.pathname === '/auth/login') return reply({ token: 'history-fixture' })
    if (url.pathname === '/auth/me') return reply({ role: 'USER', emailAddress: 'history@example.test' })
    if (url.pathname === '/notifications/subscribe') return new Response(new ReadableStream({ start(controller) { options.signal.addEventListener('abort', () => controller.close(), { once: true }) } }))
    if (url.pathname === '/portfolio/history') {
      window.__historyCalls.push(new Headers(options.headers).get('Authorization'))
      if (window.__delayHistory) await new Promise(resolve => { window.__releaseHistory = resolve })
      return reply(window.__history, window.__historyError ? 500 : 200)
    }
    if (url.pathname === '/portfolio/performance') return reply({ cashBalance: 100000, holdingsValue: 300, totalPortfolioValue: 100300, totalCostBasis: 200, unrealizedGainLoss: 100, realizedGainLoss: 200, totalGainLoss: 300, totalReturnPercentage: 0.3, ...window.__performance })
    if (url.pathname === '/portfolio/transactions') return reply({ content: [] })
    if (url.pathname === '/portfolio/holdings') return reply([{ holdingId: 1, assetId: 1, assetSymbol: 'AAPL', assetName: 'Apple', quantity: 3, currentValue: 300, averageBuyPrice: 66.6667 }])
    return reply([])
  }
})()`
const count = () => evaluate(`Number(document.querySelector('.portfolio-history-panel .sr-only')?.textContent.split(' ')[0])`)
const remount = async () => {
  await click('My Holdings'); await wait(`document.querySelector('.topbar h1')?.textContent === 'My Holdings'`)
  await click('Dashboard'); await wait(`Boolean(document.querySelector('.portfolio-history-panel'))`)
}
try {
  await send('Runtime.enable'); await send('Page.enable')
  await send('Page.addScriptToEvaluateOnNewDocument', { source: fixture })
  await send('Page.navigate', { url: appUrl })
  await wait(`document.readyState === 'complete'`)
  await evaluate('localStorage.clear()')
  await send('Page.reload')
  await wait(`Boolean(document.querySelector('input[name=emailAddress]'))`)
  await field('emailAddress', 'history@example.test'); await field('password', 'fixture-input'); await click('Login')
  await wait(`Boolean(document.querySelector('.portfolio-history-panel svg'))`)
  assert.equal(await count(), 4)
  assert.equal(await evaluate(`document.querySelectorAll('.kpi-card').length`), 4)
  assert.equal(await evaluate(`document.querySelector('.overall-return').textContent`), 'Overall Return:+$300.00(+0.30%)')
  assert.ok(await evaluate(`document.querySelector('.overall-return strong').classList.contains('positive')`))
  assert.ok(await evaluate(`document.querySelector('.holdings-cost-basis').textContent.includes('$200.00')`))
  assert.equal(await evaluate(`document.body.textContent.includes('Historical performance is not available.')`), false)
  assert.deepEqual(await evaluate(`Array.from(document.querySelector('.portfolio-overview').children, element => element.className)`), ['page-heading', 'portfolio-hero', 'kpi-grid', 'card portfolio-history-panel', 'dashboard-allocation', 'activity-grid', 'quick-actions'])
  for (const [width, columns, activityColumns] of [[1440, 4, 2], [800, 2, 1], [375, 1, 1]]) {
    await send('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: width < 540 })
    assert.equal(await evaluate(`getComputedStyle(document.querySelector('.kpi-grid')).gridTemplateColumns.split(' ').length`), columns)
    assert.equal(await evaluate(`getComputedStyle(document.querySelector('.activity-grid')).gridTemplateColumns.split(' ').length`), activityColumns)
    assert.ok(await evaluate(`document.documentElement.scrollWidth <= ${width}`), 'Dashboard must not overflow horizontally')
  }
  const initialHistoryCalls = await evaluate('window.__historyCalls.length')
  for (const [range, expected] of [['1D', 1], ['1W', 2], ['1M', 3], ['ALL', 4]]) {
    await click(range); await wait(`document.querySelector('button[aria-pressed=true]')?.textContent === '${range}'`)
    assert.equal(await count(), expected)
  }
  assert.equal(await evaluate('window.__historyCalls.length'), initialHistoryCalls, 'Time filters must not issue extra API requests')
  assert.ok(await evaluate(`window.__historyCalls.every(token => token === 'Bearer history-fixture')`))
  const chart = '.portfolio-history-panel .price-history-chart'
  await evaluate(`document.querySelector('${chart}').focus()`)
  await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Home' })
  assert.equal(await evaluate(`document.querySelector('.portfolio-history-panel .price-chart-readout strong').textContent`), '$100,000.00')
  await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'ArrowRight' })
  assert.equal(await evaluate(`document.querySelector('.portfolio-history-panel .price-chart-readout strong').textContent`), '$100,100.00')
  await send('Emulation.setDeviceMetricsOverride', { width: 375, height: 812, deviceScaleFactor: 1, mobile: true })
  await wait(`document.querySelector('.portfolio-history-panel svg').viewBox.baseVal.width < 520`)
  assert.equal(await evaluate('document.documentElement.scrollWidth'), 375)
  await evaluate(`window.__history = [window.__history[0]]`); await remount()
  await wait(`Boolean(document.querySelector('.portfolio-history-panel svg'))`)
  assert.equal(await count(), 1)
  assert.equal(await evaluate(`document.querySelectorAll('.portfolio-history-panel .price-chart-point').length`), 1)
  await click('1D'); await wait(`document.querySelector('.portfolio-history-panel').textContent.includes('No portfolio snapshots in this time range.')`)
  await evaluate(`window.__history = [{totalPortfolioValue: null, recordedAt: 'bad'}]`); await remount()
  await wait(`document.querySelector('.portfolio-history-panel').textContent.includes('No portfolio history is available yet.')`)
  await evaluate(`window.__historyError = true`); await remount()
  await wait(`Boolean(document.querySelector('.portfolio-history-panel .alert-error'))`)
  await evaluate(`window.__historyError = false; window.__delayHistory = true`)
  await click('Try again'); await wait(`Boolean(document.querySelector('.portfolio-history-panel .loading-spinner'))`)
  await evaluate(`window.__history = [{totalPortfolioValue: 123456.78, recordedAt: new Date().toISOString()}]; window.__releaseHistory()`)
  await wait(`Boolean(document.querySelector('.portfolio-history-panel svg'))`)
  assert.equal(await count(), 1)
  assert.equal(await evaluate(`document.querySelectorAll('.kpi-card').length`), 4)
  await evaluate('window.__delayHistory = false')
  for (const [label, title] of [['Buy Assets', 'Available Assets'], ['View Holdings', 'My Holdings'], ['Transactions', 'Transaction History'], ['Watchlist', 'My Watchlist']]) {
    await evaluate(`Array.from(document.querySelectorAll('.quick-actions button')).find(button => button.textContent === '${label}').click()`)
    await wait(`document.querySelector('.content h2')?.textContent === '${title}'`)
    await click('Dashboard'); await wait(`Boolean(document.querySelector('.portfolio-history-panel svg'))`)
  }
  for (const [value, expected, tone] of [[-300, 'Overall Return:-$300.00(-300.00%)', 'negative'], [0, 'Overall Return:$0.00(0.00%)', ''], [null, 'Overall Return:—(—)', '']]) {
    await evaluate(`window.__performance = {totalGainLoss: ${value}, totalReturnPercentage: ${value}, realizedGainLoss: ${value}, unrealizedGainLoss: ${value}}`)
    await remount(); await wait(`Boolean(document.querySelector('.overall-return'))`)
    assert.equal(await evaluate(`document.querySelector('.overall-return').textContent`), expected)
    assert.equal(await evaluate(`document.querySelector('.overall-return strong').className`), tone)
    assert.equal(await evaluate(`document.querySelectorAll('.kpi-card h3')[3].className`), tone)
  }
  assert.deepEqual(exceptions, [])
  console.log('PASS dashboard browser: four cards, signed/neutral/missing returns, section order, 4/2/1 responsive columns, quick navigation, authenticated history API, all time filters, keyboard inspection, single snapshot, empty/invalid history, loading and error/retry. Mock API only.')
} finally {
  await send('Emulation.clearDeviceMetricsOverride'); socket.close()
}
