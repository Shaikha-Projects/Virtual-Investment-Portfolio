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
const navigation = () => evaluate(`Array.from(document.querySelectorAll('nav[aria-label="Main navigation"] button'), button => button.getAttribute('aria-label'))`)
const header = text => wait(`document.querySelector('.topbar h1')?.textContent === ${JSON.stringify(text)}`)
const apiCalls = () => evaluate('JSON.parse(sessionStorage.__roleBackend).calls')

// Role comes from the mock identity endpoint, independent of login email.
const fixture = `(() => {
  const originalFetch = window.fetch.bind(window)
  const load = () => JSON.parse(sessionStorage.__roleBackend || JSON.stringify({ identities: {}, calls: [], serial: 0, next: { role: 'ADMIN', emailAddress: 'same-account@example.test' }, quantity: 2, cash: 1000, asset: { assetId: 1, symbol: 'AAPL', name: 'Apple', assetType: 'STOCK', currentPrice: 100, assetStatus: 'ACTIVE' } }))
  const save = data => { sessionStorage.__roleBackend = JSON.stringify(data) }
  save(load())
  window.__fixture = update => { const data = load(); Object.assign(data, update); save(data) }
  let stream
  window.fetch = async (input, options = {}) => {
    const url = new URL(input, location.href)
    if (url.origin === location.origin) return originalFetch(input, options)
    const data = load(), path = url.pathname, method = options.method || 'GET'
    data.calls.push({ path, method, query: url.search }); save(data)
    const reply = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
    const token = (new Headers(options.headers).get('Authorization') || '').replace('Bearer ', '')
    const identity = data.identities[token]
    if (path === '/auth/login') {
      const token = 'fixture-' + (++data.serial)
      data.identities[token] = { ...data.next }; save(data)
      return reply({ token, message: 'Login successful' })
    }
    if (path === '/auth/me') return data.identityFailure ? reply({}, 500) : identity ? reply(identity) : reply({}, 403)
    if (!identity) return reply({}, 403)
    if (path === '/notifications/subscribe') return new Response(new ReadableStream({ start(controller) { stream = controller; controller.enqueue(new TextEncoder().encode(': connected\\n\\n')); options.signal.addEventListener('abort', () => { try { controller.close() } catch {} }, { once: true }) } }), { headers: { 'Content-Type': 'text/event-stream' } })
    if (path === '/profile') {
      const profile = { firstName: identity.role + ' Profile', lastName: 'Account', phoneNumber: '12345678', emailAddress: identity.emailAddress }
      if (window.__deferProfileOnce) { window.__deferProfileOnce = false; return new Promise(resolve => { window.__releaseProfile = () => resolve(reply({ ...profile, firstName: 'Previous account data' })) }) }
      return reply(profile)
    }
    if (path === '/portfolio/performance') return reply({ cashBalance: data.cash, holdingsValue: data.quantity * 100, totalPortfolioValue: data.cash + data.quantity * 100, totalCostBasis: data.quantity * 100, unrealizedGainLoss: 0 })
    if (path === '/portfolio/holdings') return reply(data.quantity ? [{ holdingId: 1, assetId: 1, assetSymbol: 'AAPL', assetName: 'Apple', quantity: data.quantity, currentPrice: 100, averageBuyPrice: 100, currentValue: data.quantity * 100 }] : [])
    if (path === '/portfolio/buy' || path === '/portfolio/sell') {
      const quantity = JSON.parse(options.body).quantity, buy = path.endsWith('buy')
      data.quantity += buy ? quantity : -quantity; data.cash += buy ? -quantity * 100 : quantity * 100; save(data)
      stream?.enqueue(new TextEncoder().encode('id: ' + crypto.randomUUID() + '\\nevent: investment-notification\\ndata: ' + (buy ? 'Purchase' : 'Sale') + ' completed: ' + quantity + ' shares of AAPL\\n\\n'))
      return reply({ cashBalance: data.cash })
    }
    if (path === '/portfolio/transactions') return reply({ content: [{ transactionId: 1, transactionType: 'BUY', assetSymbol: 'AAPL', quantity: 1, pricePerUnit: 100, totalAmount: 100, transactionStatus: 'COMPLETED', createdAt: '2026-10-20T10:00:00' }], totalPages: 2, totalElements: 11 })
    if (path === '/watchlist') return reply([])
    if (path === '/assets') return reply([data.asset])
    if (path.startsWith('/admin/') && identity.role !== 'ADMIN') return reply({}, 403)
    if (path === '/admin/assets') {
      if (method === 'POST') { data.asset = { ...data.asset, ...JSON.parse(options.body) }; save(data) }
      return reply(method === 'GET' ? [data.asset] : data.asset)
    }
    if (path.startsWith('/admin/assets/')) { data.asset = { ...data.asset, ...JSON.parse(options.body) }; save(data); return reply(data.asset) }
    if (path === '/admin/users') return reply({ content: [{ id: 1, firstName: 'Current', lastName: 'Admin', emailAddress: identity.emailAddress, role: 'ADMIN', status: 'ACTIVE' }, { id: 2, firstName: 'Other', lastName: 'User', emailAddress: 'other@example.test', role: 'USER', status: data.otherStatus || 'ACTIVE' }], totalElements: 2, totalPages: 1 })
    if (path === '/admin/users/2/status') { data.otherStatus = url.searchParams.get('status'); save(data); return reply({ message: 'Status changed' }) }
    return reply({ message: 'OK' })
  }
})()`

async function reload() {
  const marker = Date.now()
  await evaluate(`window.__reloadProbe = ${marker}`)
  await send('Page.reload')
  await wait(`window.__reloadProbe !== ${marker} && document.readyState === 'complete' && typeof window.__fixture === 'function'`)
}

async function login(role) {
  await evaluate(`window.__fixture({ next: { role: ${JSON.stringify(role)}, emailAddress: 'same-account@example.test' } })`)
  await field('emailAddress', 'same-account@example.test')
  await field('password', 'synthetic-test-input')
  await click('Login')
}
async function logout() {
  await click('Logout')
  await wait(`document.body.innerText.includes('Welcome back')`)
  assert.equal(await evaluate(`localStorage.getItem('token')`), null)
}
async function forceSection(section) {
  await evaluate(`(() => { const element = document.querySelector('.dashboard'); let fiber = element[Object.keys(element).find(key => key.startsWith('__reactFiber'))]; while (fiber && fiber.type?.name !== 'PortfolioShell') fiber = fiber.return; if (!fiber) throw Error('Shell not found'); fiber.memoizedState.queue.dispatch(${JSON.stringify(section)}); })()`)
}

try {
  await send('Runtime.enable'); await send('Page.enable')
  await send('Page.addScriptToEvaluateOnNewDocument', { source: fixture })
  await send('Page.navigate', { url: appUrl })
  await wait(`document.readyState === 'complete' && typeof window.__fixture === 'function'`)
  await evaluate(`localStorage.clear(); sessionStorage.clear()`)
  await reload()
  await wait(`document.body.innerText.includes('Welcome back')`)

  await login('ADMIN'); await header('Admin Dashboard')
  assert.deepEqual(await navigation(), ['Admin Dashboard', 'Asset Management', 'User Management', 'Profile'])
  assert.equal(await evaluate(`Boolean(document.querySelector('.notifications'))`), false)
  for (const section of ['dashboard', 'assets', 'holdings', 'transactions', 'watchlist']) { await forceSection(section); await header('Admin Dashboard') }
  assert.equal((await apiCalls()).some(call => call.path.startsWith('/portfolio') || call.path === '/assets' || call.path === '/notifications/subscribe'), false)
  await click('Profile'); await wait(`document.body.innerText.includes('ADMIN Profile')`)
  await reload(); await header('Admin Dashboard')
  await click('Asset Management'); await wait(`document.body.innerText.includes('Apple')`)
  await click('Edit'); await field('name', 'Updated asset'); await click('Save asset')
  await wait(`document.body.innerText.includes('Asset updated successfully.')`)
  await click('User Management'); await wait(`document.body.innerText.includes('other@example.test')`)
  assert.equal(await evaluate(`document.querySelector('tbody tr:first-child button').disabled`), true)
  await evaluate(`document.querySelector('tbody tr:nth-child(2) button').click()`)
  await click('Confirm deactivation'); await wait(`document.body.innerText.includes('Account status updated successfully.')`)
  await evaluate('window.__deferProfileOnce = true'); await click('Profile'); await wait(`typeof window.__releaseProfile === 'function'`)
  await logout()

  await login('USER'); await header('Dashboard')
  assert.deepEqual(await navigation(), ['Dashboard', 'Assets', 'My Holdings', 'Transactions', 'Watchlist', 'Profile'])
  assert.equal(await evaluate(`Boolean(document.querySelector('.notifications'))`), true)
  await evaluate('window.__releaseProfile()')
  assert.equal(await evaluate(`document.body.innerText.includes('Previous account data')`), false)
  for (const section of ['admin', 'admin-assets', 'admin-users']) { await forceSection(section); await header('Dashboard') }
  await click('Profile'); await wait(`document.body.innerText.includes('USER Profile')`)
  assert.equal(await evaluate(`document.body.innerText.includes('ADMIN Profile')`), false)
  await reload(); await header('Dashboard')
  await click('Assets'); await wait(`Boolean(document.querySelector('button[aria-label="Buy AAPL"]'))`)
  await click('Buy AAPL')
  await evaluate(`(() => { const input = document.querySelector('.trade-panel input'); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, '0.01'); input.dispatchEvent(new Event('input', { bubbles: true })); })()`)
  await click('Confirm buy'); await wait(`document.body.innerText.includes('Purchase completed successfully.')`)
  await wait(`document.querySelector('.notification-count')?.textContent === '1'`)
  await click('Notifications (1)'); await wait(`document.body.innerText.includes('Purchase completed: 0.01 shares of AAPL')`)
  await click('Close notifications'); await click('My Holdings'); await wait(`document.body.innerText.includes('2.01')`)
  await click('Transactions'); await wait(`document.body.innerText.includes('11 total')`)
  await click('Next'); await wait(`document.body.innerText.includes('Page 2 of 2')`)
  await evaluate(`document.querySelector('.sort-button').click()`); await wait(`document.body.innerText.includes('Page 1 of 2')`)
  await click('Watchlist'); await wait(`document.body.innerText.includes('No assets in your watchlist yet.')`)
  await logout(); await login('ADMIN'); await header('Admin Dashboard')
  assert.equal(await evaluate(`Boolean(document.querySelector('.notifications'))`), false)
  assert.equal(await evaluate(`document.body.innerText.includes('Purchase completed:')`), false)

  await evaluate(`window.__fixture({ identityFailure: true })`); await reload()
  await wait(`document.body.innerText.includes('Account access unavailable')`)
  assert.equal(await evaluate(`Boolean(document.querySelector('.sidebar'))`), false)
  assert.ok(await evaluate(`localStorage.getItem('token')`))
  await evaluate(`window.__fixture({ identityFailure: false })`); await click('Retry session check'); await header('Admin Dashboard')
  await evaluate(`(() => { const data = JSON.parse(sessionStorage.__roleBackend); data.identities[localStorage.token] = { emailAddress: 'same-account@example.test' }; window.__fixture(data) })()`)
  await reload(); await wait(`document.body.innerText.includes('We could not confirm your account role')`)
  assert.equal(await evaluate(`Boolean(document.querySelector('.sidebar'))`), false)
  await click('Return to login'); await login('MANAGER')
  await wait(`document.body.innerText.includes('We could not confirm your account role')`)
  assert.equal(await evaluate(`localStorage.getItem('token')`), null)
  await login('USER'); await header('Dashboard')
  await send('Emulation.setDeviceMetricsOverride', { width: 375, height: 812, deviceScaleFactor: 1, mobile: true })
  await new Promise(resolve => setTimeout(resolve, 250))
  await click('Menu'); assert.deepEqual(await navigation(), ['Dashboard', 'Assets', 'My Holdings', 'Transactions', 'Watchlist', 'Profile'])
  await click('Profile'); await wait(`document.body.innerText.includes('USER Profile')`)
  assert.equal(await evaluate('document.documentElement.scrollWidth'), 375)
  assert.deepEqual(exceptions, [])
  console.log('PASS: ADMIN/USER login and refresh, exact menus, cross-role rendering guards, shared profiles, both directions of account switching, late-response isolation, role failure/retry, missing/unsupported roles, admin management, investor trading/pagination/SSE, and mobile navigation. Mock API only.')
} finally { socket.close() }
