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
  let notificationStream
  window.__emitOrderNotification = (message, id, eventName = 'investment-notification') => {
    if (!notificationStream) throw new Error('Notification stream is not connected')
    const frame = ['id: ' + id, 'event: ' + eventName, 'data: ' + message, '', ''].join(String.fromCharCode(10))
    notificationStream.enqueue(new TextEncoder().encode(frame))
  }
  window.__fillOrder = (id, eventId) => {
    const data = read(), order = data.orders.find(order => order.id === id)
    order.status = 'FILLED'; order.executedAt = '2026-10-27T10:00:00'; write(data)
    window.__emitOrderNotification('Limit ' + order.orderType + ' order executed: ' + order.quantity + ' shares of AAPL', eventId)
  }
  const assets = [{ assetId: 1, symbol: 'AAPL', name: 'Apple', assetType: 'STOCK', currentPrice: 100, assetStatus: 'ACTIVE' }, { assetId: 2, symbol: 'MSFT', name: 'Microsoft', assetType: 'STOCK', currentPrice: 120, assetStatus: 'ACTIVE' }]
  window.fetch = async (url, options = {}) => {
    const target = new URL(url, location.href)
    if (target.origin === location.origin) return originalFetch(url, options)
    const data = read(), path = target.pathname, method = options.method || 'GET'
    data.calls.push({ path, page: target.searchParams.get('page'), size: target.searchParams.get('size'), method, body: options.body ? JSON.parse(options.body) : null, bearer: new Headers(options.headers).get('Authorization') }); write(data)
    const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
    const text = body => new Response(body, { headers: { 'Content-Type': 'text/plain' } })
    if (path === '/auth/login') return json({ token: 'synthetic-' + data.role })
    if (path === '/auth/me') return json({ role: data.role, emailAddress: 'orders-account@example.test' })
    if (path === '/notifications/subscribe') return new Response(new ReadableStream({ start(controller) { notificationStream = controller; controller.enqueue(new TextEncoder().encode(': connected\\n\\n')); options.signal.addEventListener('abort', () => { try { controller.close() } catch {} }, { once: true }) } }), { headers: { 'Content-Type': 'text/event-stream' } })
    if (path === '/assets') return data.assetsFailure ? json({ message: 'java.sql.Exception: private' }, 500) : json(assets)
    if (path === '/portfolio/performance') return json({ totalPortfolioValue: 1000, cashBalance: 800, holdingsValue: 200, totalCostBasis: 200, unrealizedGainLoss: 0 })
    if (path === '/portfolio/holdings') return data.holdingsFailure ? json({}, 500) : json(data.holdings ?? [{ holdingId: 1, assetId: 1, assetSymbol: 'AAPL', assetName: 'Apple', quantity: 2, averageBuyPrice: 100, currentPrice: 100, currentValue: 200 }])
    if (path === '/portfolio/transactions') return json({ content: [], totalElements: 0, totalPages: 0 })
    if (path === '/watchlist') return json([])
    if (path === '/profile') return json({ firstName: data.role, lastName: 'Account', emailAddress: 'orders-account@example.test', phoneNumber: '12345678' })
    if (path === '/orders' && method === 'GET') {
      if (data.networkFailure) throw new TypeError('Network unavailable')
      if (data.ordersFailure) return json({ message: 'java.sql.Exception: private' }, 500)
      const page = Number(target.searchParams.get('page') || 0), size = Number(target.searchParams.get('size') || 10)
      const totalPages = Math.ceil(data.orders.length / size)
      const result = { content: data.orders.slice(page * size, (page + 1) * size), totalElements: data.orders.length, totalPages, number: page, size, first: page === 0, last: page + 1 >= totalPages }
      if (data.delayOrders) return new Promise(resolve => { window.__releaseOrders = () => resolve(json(result)) })
      return json(result)
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
  await click('Place Limit Order'); await select('orderType', type)
  await wait(`Boolean(document.querySelector('select[name=assetId] option[value="1"]'))`)
  await select('assetId', '1'); await input('quantity', '5'); await input('limitPrice', '12')
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
  await evaluate(`window.__emitOrderNotification('Sale completed: 1 shares of AAPL', 'before-modal-toast')`)
  await wait(`Boolean(document.querySelector('.notification-toast'))`)
  await evaluate(`void (window.__toastBeforeModal = document.querySelector('.notification-toast'))`)
  await click('Cancel order 1'); await wait(`Boolean(document.querySelector('dialog[open]'))`)
  // A real ConfirmDialog occupies the browser top layer, above ordinary z-indexes.
  await evaluate(`window.__emitOrderNotification('Purchase completed: 1 shares of AAPL', 'modal-toast')`)
  await wait(`Array.from(document.querySelectorAll('.notification-toast p')).some(p => p.textContent === 'Purchase completed: 1 shares of AAPL')`)
  assert.ok(await evaluate(`(() => {
    const toast = document.querySelector('.notification-toast'), box = toast.getBoundingClientRect()
    const topmost = document.elementFromPoint(box.right - 20, box.top + 20)
    return toast.contains(topmost)
  })()`), 'An SSE toast must be visible and clickable above the actual confirmation dialog')
  assert.ok(await evaluate(`window.__toastBeforeModal === document.querySelector('.notification-toast')`), 'Opening a modal must preserve the toast and its existing timer')
  await click('Dismiss notification: Purchase completed: 1 shares of AAPL')
  await click('Keep order')
  await wait(`!document.querySelector('dialog[open]')`)
  assert.ok(await evaluate(`window.__toastBeforeModal === document.querySelector('.notification-toast') && !document.querySelector('.notification-toast').closest('dialog')`), 'Closing the modal must keep the existing toast visible in the application')
  await click('Dismiss notification: Sale completed: 1 shares of AAPL')
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
  assert.deepEqual(await evaluate(`Array.from(document.querySelector('.sell-holdings tbody tr').cells, cell => cell.textContent)`), ['AAPL', '2', '0', '2'])
  const postsBeforeOversell = (await calls()).filter(call => call.path === '/orders' && call.method === 'POST').length
  await click('Place limit order')
  assert.equal((await calls()).filter(call => call.path === '/orders' && call.method === 'POST').length, postsBeforeOversell)
  await click('AAPL'); await click('Max')
  assert.equal(await evaluate(`document.querySelector('input[name=quantity]').value`), '2')
  assert.equal(await evaluate(`document.querySelector('select[name=assetId]').value`), '1')
  assert.deepEqual(await evaluate(`Array.from(document.querySelector('select[name=assetId]').options, option => option.value)`), ['', '1'])
  await input('quantity', '1')
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
  // Multiple backend pages: 10 / 10 / 5 rows, preserved order and zero-based queries.
  const pagedOrders = Array.from({ length: 25 }, (_, index) => ({ id: 100 - index, assetId: 1, orderType: index % 2 ? 'SELL' : 'BUY', quantity: 100 - index, limitPrice: 110, status: 'PENDING', createdAt: '2026-10-26T10:00:00', executedAt: null }))
  await state({ orders: pagedOrders, serial: 100 }); await click('Refresh orders')
  await wait(`document.querySelector('.pager')?.textContent.includes('Page 1 of 3') && document.querySelectorAll('tbody tr').length === 10`)
  assert.ok(await evaluate(`document.querySelector('.pager').textContent.includes('25 total')`))
  assert.equal(await evaluate(`[...document.querySelectorAll('.pager button')].find(button => button.textContent === 'Previous').disabled`), true)
  assert.equal(await evaluate(`document.querySelector('tbody tr').cells[2].textContent`), '100')
  await state({ delayOrders: true }); await click('Next')
  await wait(`typeof window.__releaseOrders === 'function' && Boolean(document.querySelector('.loading-spinner'))`)
  assert.equal(await evaluate(`[...document.querySelectorAll('.pager button')].every(button => button.disabled)`), true)
  await state({ delayOrders: false }); await evaluate('window.__releaseOrders()')
  await wait(`document.querySelector('.pager')?.textContent.includes('Page 2 of 3') && document.querySelectorAll('tbody tr').length === 10`)
  assert.equal(await evaluate(`document.querySelector('tbody tr').cells[2].textContent`), '90')
  await click('Next'); await wait(`document.querySelector('.pager')?.textContent.includes('Page 3 of 3') && document.querySelectorAll('tbody tr').length === 5`)
  assert.equal(await evaluate(`[...document.querySelectorAll('.pager button')].find(button => button.textContent === 'Next').disabled`), true)
  await click('Previous'); await wait(`document.querySelector('.pager')?.textContent.includes('Page 2 of 3')`)
  // Cancellation reloads page 2, while creation returns to page 1 for the new record.
  await click('Cancel order 90'); await click('Confirm cancellation')
  await wait(`document.querySelector('.pager')?.textContent.includes('Page 2 of 3') && document.querySelector('tbody tr')?.cells[4].textContent === 'CANCELLED'`)
  assert.equal((await calls()).filter(call => call.path === '/orders' && call.method === 'GET').at(-1).page, '1')
  // Execution SSE events refresh ONLY the current order page, over the existing connection.
  const beforeExecution = await calls()
  const orderGetsBefore = beforeExecution.filter(call => call.path === '/orders' && call.method === 'GET').length
  const subscriptionsBefore = beforeExecution.filter(call => call.path === '/notifications/subscribe').length
  const assetGetsBefore = beforeExecution.filter(call => call.path === '/assets').length
  const cashGetsBefore = beforeExecution.filter(call => call.path === '/portfolio/performance').length
  const documentMarker = Date.now()
  await evaluate(`window.__sameOrdersDocument = ${documentMarker}; window.__fillOrder(89, 'executed-sell'); window.__fillOrder(88, 'executed-buy')`)
  await wait(`['89', '88'].every(quantity => [...document.querySelectorAll('tbody tr')].some(row => row.cells[2].textContent === quantity && row.cells[4].textContent === 'FILLED'))`)
  assert.ok(await evaluate(`document.querySelector('.pager').textContent.includes('Page 2 of 3')`))
  assert.equal(await evaluate(`document.querySelectorAll('tbody tr').length`), 10)
  assert.equal(await evaluate('window.__sameOrdersDocument'), documentMarker)
  const afterExecution = await calls()
  assert.equal(afterExecution.filter(call => call.path === '/orders' && call.method === 'GET').length, orderGetsBefore + 1, 'Batch execution notifications into one GET')
  assert.equal(afterExecution.filter(call => call.path === '/orders' && call.method === 'GET').at(-1).page, '1')
  assert.equal(afterExecution.filter(call => call.path === '/notifications/subscribe').length, subscriptionsBefore)
  assert.equal(afterExecution.filter(call => call.path === '/assets').length, assetGetsBefore)
  assert.equal(afterExecution.filter(call => call.path === '/portfolio/performance').length, cashGetsBefore)
  await click('Notifications (2)')
  await wait(`document.querySelector('.notification-panel').textContent.includes('Limit BUY order executed: 88 shares of AAPL') && document.querySelector('.notification-panel').textContent.includes('Limit SELL order executed: 89 shares of AAPL')`)
  await click('Close notifications')
  await evaluate(`window.__fillOrder(88, 'executed-buy'); window.__emitOrderNotification('Purchase completed: 1 shares of AAPL', 'normal-buy'); window.__emitOrderNotification('Limit SELL order executed: 1 shares of AAPL', 'other-event', 'other')`)
  await new Promise(resolve => setTimeout(resolve, 350))
  assert.equal((await calls()).filter(call => call.path === '/orders' && call.method === 'GET').length, orderGetsBefore + 1, 'Duplicate IDs and unrelated events must not refetch orders')
  // A failed event-driven GET retains the requested page and can use the existing retry UI.
  await state({ ordersFailure: true }); await evaluate(`window.__fillOrder(87, 'executed-with-get-error')`)
  await wait(`Boolean(document.querySelector('.alert-error'))`)
  await state({ ordersFailure: false }); await click('Try again')
  await wait(`document.querySelector('.pager')?.textContent.includes('Page 2 of 3') && [...document.querySelectorAll('tbody tr')].some(row => row.cells[2].textContent === '87' && row.cells[4].textContent === 'FILLED')`)
  const getsBeforeLeaving = (await calls()).filter(call => call.path === '/orders' && call.method === 'GET').length
  await click('My Holdings'); await header('My Holdings')
  await evaluate(`window.__fillOrder(86, 'executed-offscreen')`)
  await new Promise(resolve => setTimeout(resolve, 350))
  assert.equal((await calls()).filter(call => call.path === '/orders' && call.method === 'GET').length, getsBeforeLeaving, 'Do not fetch orders while another section is open')
  await click('My Orders'); await header('My Orders'); await wait(`document.querySelector('.pager')?.textContent.includes('Page 1 of 3')`)
  await click('Next'); await wait(`document.querySelector('.pager')?.textContent.includes('Page 2 of 3')`)
  assert.equal((await calls()).filter(call => call.path === '/notifications/subscribe').length, subscriptionsBefore, 'Navigation and refreshes must reuse the SSE connection')
  await openForm(); await click('Place limit order')
  await wait(`document.querySelector('.pager')?.textContent.includes('Page 1 of 3') && Boolean(document.querySelector('button[aria-label="Cancel order 101"]'))`)
  assert.equal((await calls()).filter(call => call.path === '/orders' && call.method === 'GET').at(-1).page, '0')
  assert.ok((await calls()).filter(call => call.path === '/orders' && call.method === 'GET').every(call => call.size === '10' && call.bearer === 'Bearer synthetic-USER'))
  await send('Emulation.setDeviceMetricsOverride', { width: 375, height: 812, deviceScaleFactor: 1, mobile: true })
  assert.equal(await evaluate('document.documentElement.scrollWidth'), 375)
  await click('Next'); await wait(`document.querySelector('.pager')?.textContent.includes('Page 2 of 3')`)
  await click('Previous'); await wait(`document.querySelector('.pager')?.textContent.includes('Page 1 of 3')`)
  await state({ orders: [] }); await click('Refresh orders'); await wait(`document.body.innerText.includes('You have no limit orders yet.')`)
  assert.equal(await evaluate(`[...document.querySelectorAll('.pager button')].every(button => button.disabled)`), true)
  await openForm('SELL')
  assert.equal(await evaluate('document.documentElement.scrollWidth'), 375)
  await state({ orders: [201, 202].map(id => ({ id, assetId: 1, orderType: 'SELL', quantity: 1, limitPrice: 120, status: 'PENDING' })) })
  await click('Refresh orders')
  await wait(`document.querySelector('.sell-holdings tbody tr')?.cells[3].textContent === '0'`)
  assert.equal(await evaluate(`document.querySelector('.sell-holdings button').disabled`), true)
  assert.equal(await evaluate(`document.querySelector('select[name=assetId]').options.length`), 1)
  await evaluate(`window.__fillOrder(201, 'sell-holdings-refresh')`)
  await wait(`document.querySelector('.sell-holdings tbody tr')?.cells[3].textContent === '1'`)
  await click('AAPL'); await click('Max')
  assert.equal(await evaluate(`document.querySelector('input[name=quantity]').value`), '1')
  const postsBeforeRetry = (await calls()).filter(call => call.path === '/orders' && call.method === 'POST').length
  await state({ holdingsFailure: true }); await click('Refresh orders')
  await wait(`Boolean(document.querySelector('.sell-holdings .alert-error'))`)
  await state({ holdingsFailure: false }); await click('Try again')
  await wait(`document.querySelector('.sell-holdings tbody tr')?.cells[3].textContent === '1'`)
  assert.equal((await calls()).filter(call => call.path === '/orders' && call.method === 'POST').length, postsBeforeRetry)
  await state({ holdings: [] }); await click('Refresh orders')
  await wait(`document.querySelector('.sell-holdings').textContent.includes('You have no holdings available to sell.')`)
  await click('Close form')
  assert.deepEqual(exceptions, [])
  const mutations = (await calls()).filter(call => call.method === 'POST' || call.method === 'PATCH')
  assert.ok(mutations.filter(call => call.path.startsWith('/orders')).every(call => call.bearer === 'Bearer synthetic-USER'))
  assert.ok(mutations.filter(call => call.path === '/admin/assets/execute-pending').every(call => call.bearer === 'Bearer synthetic-ADMIN'))
  console.log('PASS orders browser smoke: SELL holdings, reservations, asset selection, Max, oversell prevention, SSE availability refresh, error/retry and empty/mobile states; existing BUY/SELL placement, cancellation, pagination, SSE notifications, duplicate prevention and JWT checks pass. Mock API only.')
} finally { await send('Emulation.clearDeviceMetricsOverride'); socket.close() }
