// Optional browser checks: real React UI, isolated API fixtures and controllable
// 10-second timers. No production backend data is replaced by application code.
import assert from 'node:assert/strict'

const appUrl = process.env.SMOKE_APP_URL || 'http://localhost:5178'
const debugUrl = process.env.SMOKE_DEBUG_URL || 'http://localhost:9230'
const target = (await (await fetch(`${debugUrl}/json`)).json()).find(item => item.type === 'page')
if (!target) throw new Error('Start a disposable Chrome instance with remote debugging.')
const socket = new WebSocket(target.webSocketDebuggerUrl)
await new Promise(resolve => socket.addEventListener('open', resolve, { once: true }))
let sequence = 0
const pending = new Map(), exceptions = []
socket.addEventListener('message', ({ data }) => {
  const message = JSON.parse(data)
  if (message.id) {
    const task = pending.get(message.id); pending.delete(message.id)
    if (message.error) task.reject(new Error(message.error.message)); else task.resolve(message.result)
  }
  if (message.method === 'Runtime.exceptionThrown') exceptions.push(message.params.exceptionDetails.text)
})
const send = (method, params = {}) => new Promise((resolve, reject) => { const id = ++sequence; pending.set(id, { resolve, reject }); socket.send(JSON.stringify({ id, method, params })) })
async function evaluate(expression) {
  const result = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || 'Browser evaluation failed')
  return result.result.value
}
async function wait(expression) {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (await evaluate(`Boolean(${expression})`)) return
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  throw new Error(`Timed out: ${expression}`)
}
const click = label => evaluate(`([...document.querySelectorAll('button')].find(button => (button.getAttribute('aria-label') || button.textContent.trim()) === ${JSON.stringify(label)})).click()`)
const input = (selector, value) => evaluate(`(() => { const input = document.querySelector(${JSON.stringify(selector)}); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, ${JSON.stringify(value)}); input.dispatchEvent(new Event('input', { bubbles: true })); })()`)
const state = update => evaluate(`window.__marketState(${JSON.stringify(update)})`)
const tick = () => evaluate('window.__tickMarket()')
const count = path => evaluate(`window.__marketCalls.filter(call => !call.aborted && call.path === ${JSON.stringify(path)}).length`)

const fixture = `(() => {
  const originalFetch = window.fetch.bind(window)
  const originalInterval = window.setInterval.bind(window), originalClear = window.clearInterval.bind(window)
  window.__marketTimers = new Map(); let timerId = 900000
  window.setInterval = (callback, delay, ...args) => { if (delay !== 10000) return originalInterval(callback, delay, ...args); const id = ++timerId; window.__marketTimers.set(id, callback); return id }
  window.clearInterval = id => { if (!window.__marketTimers.delete(id)) originalClear(id) }
  window.__tickMarket = () => Promise.all([...window.__marketTimers.values()].map(callback => callback()))
  const read = () => JSON.parse(sessionStorage.__marketFixture || '{"price":100,"historyCount":125,"quantity":2}')
  const write = data => { sessionStorage.__marketFixture = JSON.stringify(data) }
  window.__marketState = update => write({ ...read(), ...update })
  window.__marketCalls = []
  window.fetch = async (url, options = {}) => {
    const target = new URL(url, location.href)
    if (target.origin === location.origin) return originalFetch(url, options)
    const data = read(), path = target.pathname
    const call = { path, method: options.method || 'GET', query: target.search, bearer: new Headers(options.headers).get('Authorization'), aborted: false }
    window.__marketCalls.push(call)
    options.signal?.addEventListener('abort', () => { call.aborted = true }, { once: true })
    const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
    if (path === '/auth/me') return data.invalid ? json({}, 403) : json({ role: 'USER', emailAddress: 'market-test@example.test' })
    if (path === '/portfolio/performance') return json({ totalPortfolioValue: 1000, cashBalance: 800, holdingsValue: 200, totalCostBasis: 200, unrealizedGainLoss: 0 })
    if (path === '/portfolio/holdings') return json(data.quantity ? [{ holdingId: 1, assetId: 1, assetSymbol: 'AAPL', assetName: 'Apple', quantity: data.quantity, averageBuyPrice: 100, currentPrice: data.price, currentValue: data.price * data.quantity }] : [])
    if (path === '/portfolio/transactions') return json({ content: [], totalPages: 0, totalElements: 0 })
    if (path === '/profile') return json({ firstName: 'Market', lastName: 'Account', emailAddress: 'market-test@example.test', phoneNumber: '12345678' })
    if (path === '/watchlist' || path === '/orders') return json([])
    if (path === '/portfolio/buy' || path === '/portfolio/sell') { const body = JSON.parse(options.body); data.quantity += path.endsWith('buy') ? body.quantity : -body.quantity; write(data); return json({ cashBalance: 800 }) }
    if (path === '/notifications/subscribe') return new Response(new ReadableStream({ start(controller) { controller.enqueue(new TextEncoder().encode(': connected\\n\\n')); options.signal.addEventListener('abort', () => { try { controller.close() } catch {} }, { once: true }) } }), { headers: { 'Content-Type': 'text/event-stream' } })
    if (path === '/assets') {
      if (data.deferCatalog) { window.__marketState({ deferCatalog: false }); await new Promise(resolve => { window.__releaseCatalog = resolve }) }
      if (data.catalogFailure) return json({ message: 'java.sql.Exception: private details' }, 500)
      let assets = [{ assetId: 1, symbol: 'AAPL', name: 'Apple', assetType: 'STOCK', currentPrice: data.price, assetStatus: 'ACTIVE' }, { assetId: 2, symbol: 'BTC', name: 'Bitcoin', assetType: 'CRYPTO', currentPrice: 200, assetStatus: 'ACTIVE' }]
      if (target.searchParams.get('search')) assets = assets.filter(asset => (asset.symbol + asset.name).toLowerCase().includes(target.searchParams.get('search').toLowerCase()))
      if (target.searchParams.get('type')) assets = assets.filter(asset => asset.assetType === target.searchParams.get('type'))
      return json(assets)
    }
    if (path.endsWith('/price-history')) {
      if (data.historyFailure) return json({ message: 'java.lang.Exception: private details' }, data.historyFailure)
      if (data.deferHistory) { await new Promise(resolve => { window.__releaseHistory = resolve }); if (!options.signal?.aborted) window.__marketState({ deferHistory: false }) }
      const rows = Array.from({ length: data.historyCount }, (_, index) => ({ price: Number((data.price - (data.historyCount - 1 - index) * .1).toFixed(2)), recordedAt: new Date(Date.UTC(2026, 9, 21, 12) + index * 10000).toISOString() }))
      return json(rows.reverse())
    }
    return json({}, 404)
  }
})()`

let injection
try {
  await send('Runtime.enable'); await send('Page.enable')
  injection = await send('Page.addScriptToEvaluateOnNewDocument', { source: fixture })
  await send('Page.navigate', { url: appUrl })
  await wait(`document.readyState === 'complete' && typeof window.__marketState === 'function'`)
  await evaluate(`localStorage.clear(); sessionStorage.clear(); localStorage.token = 'synthetic-market-user'; window.__marketReload = true`)
  await send('Page.reload'); await wait(`!window.__marketReload && document.querySelector('.portfolio-overview')`)
  assert.equal(await evaluate(`document.querySelector('.price-history-chart')`), null)
  assert.equal(await evaluate('window.__marketTimers.size'), 0)
  await click('Assets'); await wait(`document.querySelectorAll('tbody tr').length === 2`)
  assert.equal(await evaluate('window.__marketTimers.size'), 1)
  assert.equal(await count('/assets/1/price-history'), 0)
  await click('Buy AAPL'); await input('.trade-panel input', '3')
  let before = await count('/assets')
  await state({ price: 103 }); await tick(); await wait(`document.querySelector('tbody tr').cells[3].textContent === '$103.00'`)
  assert.equal(await count('/assets'), before + 1)
  assert.equal(await evaluate(`document.querySelector('.trade-panel input').value`), '3')
  assert.ok(await evaluate(`document.querySelector('tbody .price-movement').classList.contains('positive')`))
  await state({ price: 101 }); await tick(); await wait(`document.querySelector('tbody .price-movement').classList.contains('negative')`)
  await tick(); await wait(`document.querySelector('tbody .price-movement').textContent.includes('Unchanged')`)
  await click('Cancel')
  await input('input[name=search]', 'Apple')
  await evaluate(`(() => { const select = document.querySelector('select[name=type]'); select.value = 'STOCK'; select.dispatchEvent(new Event('change', { bubbles: true })) })()`)
  await click('Search'); await wait(`document.querySelectorAll('tbody tr').length === 1`)
  await evaluate(`document.querySelector('.sort-button').click()`)
  before = await count('/assets')
  await click('View AAPL details'); await wait(`document.body.innerText.includes('Latest 100 / 100 records') && Boolean(document.querySelector('.price-history-chart'))`)
  assert.equal(await count('/assets'), before)
  assert.equal(await evaluate('window.__marketTimers.size'), 2)
  assert.equal(await evaluate(`document.querySelectorAll('nav button').length`), 7)
  await evaluate(`document.querySelector('.price-history-chart').focus()`)
  await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Home', code: 'Home', windowsVirtualKeyCode: 36 })
  await wait(`document.querySelector('.price-chart-readout').textContent.includes('$91.10')`)
  await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'End', code: 'End', windowsVirtualKeyCode: 35 })
  await wait(`document.querySelector('.price-chart-tooltip').textContent.includes('$101.00')`)
  const bounds = await evaluate(`document.querySelector('.price-chart-svg').getBoundingClientRect().toJSON()`)
  await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: bounds.right - 19, y: bounds.y + 140 })
  assert.ok(await evaluate(`document.querySelector('.price-chart-tooltip').textContent.includes('$101.00')`))
  const historyBefore = await count('/assets/1/price-history'); before = await count('/assets')
  await state({ price: 104, historyCount: 130 }); await tick()
  await wait(`document.querySelector('.asset-details-price strong').textContent === '$104.00'`)
  assert.equal(await count('/assets'), before + 1)
  assert.equal(await count('/assets/1/price-history'), historyBefore + 1)
  assert.ok(await evaluate(`document.body.innerText.includes('Latest 100 / 100 records')`))
  for (const width of [1440, 768, 375]) {
    await send('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: width < 800 })
    await new Promise(resolve => setTimeout(resolve, 250))
    assert.ok(await evaluate('document.documentElement.scrollWidth') <= width, `No horizontal overflow at ${width}px`)
    assert.ok(await evaluate(`document.querySelector('.price-chart-svg').getBoundingClientRect().width <= document.querySelector('.asset-history-panel').getBoundingClientRect().width`))
  }
  await click('Sell AAPL'); await input('.trade-panel input', '0.01'); await click('Confirm sell')
  await wait(`document.body.innerText.includes('Sale completed successfully.')`)
  await state({ historyFailure: 500, catalogFailure: true }); await tick()
  await wait(`document.body.innerText.includes('Prices delayed') && document.body.innerText.includes('Showing the last received history')`)
  assert.ok(await evaluate(`Boolean(document.querySelector('.price-history-chart'))`))
  assert.equal(await evaluate(`document.body.innerText.includes('java.lang') || document.body.innerText.includes('java.sql')`), false)
  await state({ historyFailure: 0, catalogFailure: false }); await tick(); await wait(`document.body.innerText.includes('Live Market') && !document.querySelector('.alert-warning')`)
  await state({ historyCount: 0 }); await tick(); await wait(`document.body.innerText.includes('No price history is available')`)
  await state({ historyCount: 1 }); await tick(); await wait(`Boolean(document.querySelector('.price-history-chart'))`)
  assert.ok(await evaluate(`Boolean(document.querySelector('.price-chart-point'))`))
  await click('Back to Market'); await wait(`Boolean(document.querySelector('input[name=search]'))`)
  assert.equal(await evaluate(`document.querySelector('input[name=search]').value`), 'Apple')
  assert.equal(await evaluate(`document.querySelector('select[name=type]').value`), 'STOCK')
  assert.equal(await evaluate(`document.querySelector('thead th').getAttribute('aria-sort')`), 'descending')
  assert.equal(await evaluate('window.__marketTimers.size'), 1)
  await state({ deferCatalog: true, price: 107 }); before = await count('/assets')
  await evaluate('void window.__tickMarket()'); await wait(`typeof window.__releaseCatalog === 'function'`)
  await evaluate('void window.__tickMarket()'); assert.equal(await count('/assets'), before + 1)
  await click('Menu'); await click('Profile'); await wait(`document.body.innerText.includes('Market') && document.querySelector('.profile-page')`)
  assert.equal(await evaluate('window.__marketTimers.size'), 0)
  assert.ok(await evaluate(`window.__marketCalls.filter(call => call.path === '/assets').at(-1).aborted`))
  await evaluate('window.__releaseCatalog()'); before = await count('/assets'); await tick(); assert.equal(await count('/assets'), before)
  await click('Menu'); await click('Assets'); await wait(`Boolean(document.querySelector('tbody'))`)
  await state({ historyFailure: 404 }); await click('View BTC details'); await wait(`document.querySelector('.asset-history-panel').textContent.includes('Try again')`)
  assert.equal(await evaluate(`document.querySelector('.price-history-chart')`), null)
  await state({ historyFailure: 0 }); await click('Try again'); await wait(`Boolean(document.querySelector('.price-history-chart'))`)
  await click('Back to Market'); await wait(`Boolean(document.querySelector('tbody'))`)
  await state({ deferHistory: true }); await click('View AAPL details'); await wait(`typeof window.__releaseHistory === 'function'`)
  await wait(`Boolean(document.querySelector('.asset-history-panel .loading-state'))`)
  await click('Back to Market'); await evaluate('window.__releaseHistory()')
  assert.equal(await evaluate('window.__marketTimers.size'), 1)
  await click('Menu'); await click('Dashboard'); await wait(`Boolean(document.querySelector('.portfolio-overview'))`)
  assert.equal(await evaluate('window.__marketTimers.size'), 0)
  assert.equal(await evaluate(`document.querySelector('.price-history-chart')`), null)
  assert.ok(await evaluate(`window.__marketCalls.every(call => call.bearer === 'Bearer synthetic-market-user')`))
  assert.deepEqual(exceptions, [])
  console.log('PASS market browser smoke: 10-second polling, movement, retained trade input, filters/sort/back, shared catalog request, real latest-100 history, hover/keyboard, responsive chart, sell, safe stale/error/empty/single/loading states, retry, no overlapping requests, abort/timer cleanup, and unchanged Dashboard. Mock API and controlled timers only.')
} finally {
  if (injection) await send('Page.removeScriptToEvaluateOnNewDocument', { identifier: injection.identifier })
  socket.close()
}
