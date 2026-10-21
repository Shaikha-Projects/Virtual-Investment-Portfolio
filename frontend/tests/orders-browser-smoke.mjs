// Optional real-browser checks with isolated backend fixtures, never production data.
import assert from 'node:assert/strict'

const appUrl = process.env.SMOKE_APP_URL || 'http://localhost:5177'
const debugUrl = process.env.SMOKE_DEBUG_URL || 'http://localhost:9229'
const target = (await (await fetch(`${debugUrl}/json`)).json()).find(item => item.type === 'page')
if (!target) throw new Error('Start a disposable Chrome instance with remote debugging.')
const socket = new WebSocket(target.webSocketDebuggerUrl)
await new Promise(resolve => socket.addEventListener('open', resolve, { once: true }))
let sequence = 0
const requests = new Map()
const exceptions = []
socket.addEventListener('message', ({ data }) => {
  const message = JSON.parse(data)
  if (message.id) {
    const request = requests.get(message.id)
    requests.delete(message.id)
    if (message.error) request.reject(new Error(message.error.message))
    else request.resolve(message.result)
  }
  if (message.method === 'Runtime.exceptionThrown') exceptions.push(message.params.exceptionDetails.text)
})
function send(method, params = {}) {
  return new Promise((resolve, reject) => { const id = ++sequence; requests.set(id, { resolve, reject }); socket.send(JSON.stringify({ id, method, params })) })
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
const input = (name, value) => evaluate(`(() => { const element = document.querySelector('input[name="${name}"]'); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(element, ${JSON.stringify(value)}); element.dispatchEvent(new Event('input', { bubbles: true })); })()`)
const select = (name, value) => evaluate(`(() => { const element = document.querySelector('select[name="${name}"]'); element.value = ${JSON.stringify(value)}; element.dispatchEvent(new Event('change', { bubbles: true })); })()`)
const calls = () => evaluate('JSON.parse(sessionStorage.__ordersFixture).calls')
const state = update => evaluate(`window.__updateOrdersFixture(${JSON.stringify(update)})`)
const header = label => wait(`document.querySelector('.topbar h1')?.textContent === ${JSON.stringify(label)}`)

const fixture = `(() => {
  const originalFetch = window.fetch.bind(window)
  const read = () => JSON.parse(sessionStorage.__ordersFixture || JSON.stringify({ role: 'USER', calls: [], serial: 3, orders: [
    { id: 3, assetId: 99, orderType: 'BUY', quantity: 1, limitPrice: 8, status: 'CANCELLED', createdAt: '2026-10-21T12:00:00', executedAt: null },
    { id: 2, assetId: 1, orderType: 'SELL', quantity: 2, limitPrice: 90, status: 'FILLED', createdAt: '2026-10-21T11:00:00', executedAt: '2026-10-21T11:05:00' },
    { id: 1, assetId: 2, orderType: 'BUY', quantity: 3, limitPrice: 12, status: 'PENDING', createdAt: '2026-10-21T10:00:00', executedAt: null }
  ] }))
  const write = value => { sessionStorage.__ordersFixture = JSON.stringify(value) }
  write(read())
  window.__updateOrdersFixture = update => write({ ...read(), ...update })
  const assets = [{ assetId: 1, symbol: 'AAPL', name: 'Apple', assetType: 'STOCK', currentPrice: 100, assetStatus: 'ACTIVE' }, { assetId: 2, symbol: 'MSFT', name: 'Microsoft', assetType: 'STOCK', currentPrice: 120, assetStatus: 'ACTIVE' }]
  window.fetch = async (url, options = {}) => {
    const target = new URL(url, location.href)
    if (target.origin === location.origin) return originalFetch(url, options)
    const data = read(), path = target.pathname, method = options.method || 'GET'
    data.calls.push({ path, method, body: options.body ? JSON.parse(options.body) : null, bearer: new Headers(options.headers).get('Authorization') }); write(data)
    const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
    const text = body => new Response(body, { headers: { 'Content-Type': 'text/plain' } })
    if (path === '/auth/login') return json({ token: 'synthetic-' + data.role })
    if (path === '/auth/me') return json({ role: data.role, emailAddress: 'orders-account@example.test' })
    if (path === '/notifications/subscribe') return new Response(new ReadableStream({ start(controller) { controller.enqueue(new TextEncoder().encode(': connected\\n\\n')); options.signal.addEventListener('abort', () => { try { controller.close() } catch {} }, { once: true }) } }), { headers: { 'Content-Type': 'text/event-stream' } })
    if (path === '/assets') return data.assetsFailure ? json({ message: 'java.sql.Exception: private' }, 500) : json(assets)
    if (path === '/portfolio/performance') return json({ totalPortfolioValue: 1000, cashBalance: 800, holdingsValue: 200, totalCostBasis: 200, unrealizedGainLoss: 0 })
    if (path === '/portfolio/holdings') return json([{ holdingId: 1, assetId: 1, assetSymbol: 'AAPL', assetName: 'Apple', quantity: 2, averageBuyPrice: 100, currentPrice: 100, currentValue: 200 }])
    if (path === '/portfolio/transactions') return json({ content: [], totalElements: 0, totalPages: 0 })
    if (path === '/watchlist') return json([])
    if (path === '/profile') return json({ firstName: data.role, lastName: 'Account', emailAddress: 'orders-account@example.test', phoneNumber: '12345678' })
    if (path === '/orders' && method === 'GET') {
      if (data.networkFailure) throw new TypeError('Network unavailable')
      return data.ordersFailure ? json({ message: 'java.sql.Exception: private' }, 500) : json(data.orders)
    }
    if (path === '/orders' && method === 'POST') {
      if (data.placeFailure) return json({ message: data.placeFailure }, 400)
      data.orders.unshift({ ...JSON.parse(options.body), id: ++data.serial, status: 'PENDING', createdAt: '2026-10-21T13:00:00', executedAt: null }); write(data)
      await new Promise(resolve => setTimeout(resolve, 100))
      return text('Limit order placed successfully')
    }
    if (/^\\/orders\\/\\d+\\/cancel$/.test(path)) {
      const order = data.orders.find(order => order.id === Number(path.split('/')[2]))
      if (data.cancelRace) { order.status = 'FILLED'; order.executedAt = '2026-10-21T13:01:00'; write(data); return json({ message: 'Only pending orders can be cancelled' }, 400) }
      order.status = 'CANCELLED'; write(data)
      await new Promise(resolve => setTimeout(resolve, 100))
      return text('Order is successfully cancelled')
    }
    if (path === '/admin/assets/execute-pending') {
      if (data.role !== 'ADMIN') return json({}, 403)
      if (data.executeFailure) return json({ message: 'java.lang.Exception: private' }, 500)
      data.orders.forEach(order => { if (order.status === 'PENDING') { order.status = 'FILLED'; order.executedAt = '2026-10-21T14:00:00' } }); write(data)
      await new Promise(resolve => setTimeout(resolve, 100))
      return text('Pending orders processed successfully')
    }
    if (path === '/admin/assets') return json(assets)
    if (path === '/admin/users') return json({ content: [], totalElements: 0, totalPages: 0 })
    return json({}, 404)
  }
})()`

async function reload() {
  const marker = Date.now()
  await evaluate(`window.__ordersReload = ${marker}`)
  await send('Page.reload')
  await wait(`window.__ordersReload !== ${marker} && document.readyState === 'complete' && typeof window.__updateOrdersFixture === 'function'`)
}
async function login(role) {
  await state({ role }); await input('emailAddress', 'orders-account@example.test'); await input('password', 'synthetic-input'); await click('Login')
  await header(role === 'ADMIN' ? 'Admin Dashboard' : 'Dashboard')
}
async function logout() { await click('Logout'); await wait(`Boolean(document.querySelector('input[name=emailAddress]'))`) }
async function openForm(type = 'BUY') {
  await click('Place Limit Order'); await select('assetId', '1'); await select('orderType', type); await input('quantity', '5'); await input('limitPrice', '12')
}

try {
  await send('Runtime.enable'); await send('Page.enable')
  await send('Page.addScriptToEvaluateOnNewDocument', { source: fixture })
  await send('Page.navigate', { url: appUrl })
  await wait(`document.readyState === 'complete' && typeof window.__updateOrdersFixture === 'function'`)
  await evaluate('localStorage.clear(); sessionStorage.clear()'); await reload()
  await wait(`Boolean(document.querySelector('input[name=emailAddress]'))`)
  await login('USER'); await click('My Orders'); await header('My Orders')
  await wait(`document.querySelectorAll('tbody tr').length === 3`)
  assert.deepEqual(await evaluate(`Array.from(document.querySelectorAll('tbody tr'), row => row.cells[4].textContent)`), ['CANCELLED', 'FILLED', 'PENDING'])
  assert.deepEqual(await evaluate(`Array.from(document.querySelectorAll('tbody th'), cell => cell.textContent)`), ['Asset #99', 'AAPL', 'MSFT'])
  assert.equal(await evaluate(`document.querySelectorAll('tbody button').length`), 1)
  assert.equal(await evaluate(`document.body.innerText.includes('Execute Pending Orders')`), false)
  await click('Cancel order 1'); await wait(`Boolean(document.querySelector('dialog[open]'))`); await click('Keep order')
  assert.equal((await calls()).filter(call => call.method === 'PATCH').length, 0)
  await click('Cancel order 1'); await click('Confirm cancellation')
  await wait(`document.body.innerText.includes('Limit order cancelled successfully.') && Boolean(document.querySelector('tbody'))`)
  assert.equal(await evaluate(`document.querySelectorAll('tbody button').length`), 0)
  await openForm()
  assert.equal(await evaluate(`document.querySelector('.trade-estimate').textContent`), 'Estimated order value: $60.00')
  assert.ok(await evaluate(`document.querySelector('.order-rule').textContent.includes('less than or equal')`))
  await input('quantity', '0'); await click('Place limit order')
  assert.equal((await calls()).filter(call => call.path === '/orders' && call.method === 'POST').length, 0)
  await input('quantity', '5')
  await evaluate(`(() => { const button = [...document.querySelectorAll('button')].find(button => button.textContent === 'Place limit order'); button.click(); button.click() })()`)
  await wait(`document.body.innerText.includes('Limit order placed successfully.') && Boolean(document.querySelector('tbody'))`)
  assert.equal((await calls()).filter(call => call.path === '/orders' && call.method === 'POST').length, 1)
  assert.equal(await evaluate(`document.querySelector('tbody tr').cells[4].textContent`), 'PENDING')
  await openForm('SELL')
  assert.ok(await evaluate(`document.querySelector('.order-rule').textContent.includes('greater than or equal')`))
  await state({ placeFailure: 'Insufficient available shares for this order' }); await click('Place limit order')
  await wait(`document.body.innerText.includes('Insufficient available shares for this order')`)
  await state({ placeFailure: null }); await input('quantity', '0.01'); await input('limitPrice', '130'); await click('Place limit order')
  await wait(`document.body.innerText.includes('Limit order placed successfully.') && Boolean(document.querySelector('tbody'))`)
  await wait(`document.querySelector('tbody tr')?.cells[1].textContent === 'SELL'`)
  await state({ cancelRace: true }); await click('Cancel order 5'); await click('Confirm cancellation')
  await wait(`document.querySelector('dialog')?.textContent.includes('Only pending orders can be cancelled')`)
  await click('Keep order'); await state({ cancelRace: false }); await click('Refresh orders')
  await wait(`document.querySelector('tbody tr')?.cells[4].textContent === 'FILLED'`)
  for (const failure of ['ordersFailure', 'networkFailure']) {
    await state({ [failure]: true }); await click('Refresh orders'); await wait(`Boolean(document.querySelector('.alert-error'))`)
    assert.equal(await evaluate(`document.body.innerText.includes('java.sql')`), false)
    assert.ok(await evaluate(`localStorage.getItem('token')`))
    await state({ [failure]: false }); await click('Try again'); await wait(`Boolean(document.querySelector('tbody'))`)
  }
  await state({ assetsFailure: true }); await click('Refresh orders'); await wait(`document.body.innerText.includes('Asset names are temporarily unavailable')`)
  assert.ok(await evaluate(`document.querySelector('tbody th').textContent.startsWith('Asset #')`))
  await state({ assetsFailure: false }); await click('Retry asset names'); await wait(`document.querySelector('tbody th')?.textContent === 'AAPL'`)
  await send('Emulation.setDeviceMetricsOverride', { width: 375, height: 812, deviceScaleFactor: 1, mobile: true })
  await new Promise(resolve => setTimeout(resolve, 250))
  assert.equal(await evaluate('document.documentElement.scrollWidth'), 375)
  await logout(); await login('ADMIN')
  assert.equal(await evaluate(`Array.from(document.querySelectorAll('nav button')).some(button => button.textContent === 'My Orders')`), false)
  await click('Execute Pending Orders'); await click('Go back')
  assert.equal((await calls()).filter(call => call.path === '/admin/assets/execute-pending').length, 0)
  await state({ executeFailure: true }); await click('Execute Pending Orders'); await click('Confirm execution')
  await wait(`Boolean(document.querySelector('dialog .alert-error'))`)
  assert.equal(await evaluate(`document.querySelector('dialog').textContent.includes('java.lang')`), false)
  await state({ executeFailure: false })
  const before = (await calls()).filter(call => call.path === '/admin/assets').length
  await click('Confirm execution'); await wait(`document.body.innerText.includes('Eligible pending orders processed successfully.')`)
  assert.ok((await calls()).filter(call => call.path === '/admin/assets').length > before)
  assert.equal((await calls()).some(call => call.path === '/orders/execute-pending'), false)
  await logout(); await login('USER'); await click('My Orders'); await wait(`Boolean(document.querySelector('tbody'))`)
  assert.equal(await evaluate(`Array.from(document.querySelectorAll('tbody tr')).some(row => row.cells[4].textContent === 'PENDING')`), false)
  await state({ orders: [] }); await click('Refresh orders'); await wait(`document.body.innerText.includes('You have no limit orders yet.')`)
  assert.deepEqual(exceptions, [])
  const mutations = (await calls()).filter(call => call.method === 'POST' || call.method === 'PATCH')
  assert.ok(mutations.filter(call => call.path.startsWith('/orders')).every(call => call.bearer === 'Bearer synthetic-USER'))
  assert.ok(mutations.filter(call => call.path === '/admin/assets/execute-pending').every(call => call.bearer === 'Bearer synthetic-ADMIN'))
  console.log('PASS orders browser smoke: backend ordering/fields, lookup fallback, badges, BUY/SELL/estimates, minimum validation, duplicate prevention, cancellation confirmation/races, safe errors/retry, empty state, mobile layout, ADMIN-only verified execution route, refreshed results, and JWT headers. Mock API only.')
} finally { socket.close() }
