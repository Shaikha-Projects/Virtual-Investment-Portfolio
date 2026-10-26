// Optional browser smoke test. Requires a running Vite server and a disposable
// Chrome instance with remote debugging. All backend responses are fixtures.
import assert from 'node:assert/strict'

const appUrl = process.env.SMOKE_APP_URL || 'http://localhost:5189'
const debugUrl = process.env.SMOKE_DEBUG_URL || 'http://localhost:9239'
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
const header = label => wait(`document.querySelector('.topbar h1')?.textContent === ${JSON.stringify(label)}`)
const setState = update => evaluate(`window.__auditSet(${JSON.stringify(update)})`)
const fixture = `(() => {
  const originalFetch = window.fetch.bind(window)
  const read = () => JSON.parse(sessionStorage.__auditFixture || '{"role":"ADMIN","mode":"loading","calls":[]}')
  const write = data => { sessionStorage.__auditFixture = JSON.stringify(data) }
  window.__auditSet = patch => write({ ...read(), ...patch })
  const json = (data, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
  const logs = [
    { id: 15, userEmail: 'investor@example.test', action: 'PLACE_LIMIT_ORDER', details: 'Placed BUY limit order #10 for 5 shares of AAPL', createdAt: '2026-10-26T10:30:00' },
    { id: 14, userEmail: 'older@example.test', action: 'CANCEL_LIMIT_ORDER', details: 'Cancelled limit order #9 for 2 shares of MSFT', createdAt: '2026-10-25T09:00:00' }
  ]
  window.fetch = async (url, options = {}) => {
    const target = new URL(url, location.href)
    if (target.origin === location.origin) return originalFetch(url, options)
    const data = read(), path = target.pathname
    data.calls.push({ path, method: options.method || 'GET', bearer: new Headers(options.headers).get('Authorization') }); write(data)
    if (path === '/auth/login') return json({ token: 'synthetic-audit-' + data.role })
    if (path === '/auth/me') return json({ emailAddress: 'admin@example.test', role: data.role })
    if (path === '/admin/audit-logs') {
      if (data.mode === 'loading') return new Promise(resolve => { window.__auditRelease = () => resolve(json(logs)) })
      if (data.mode === 'error') return json({ message: 'Internal database details must stay hidden' }, 500)
      return json(data.mode === 'empty' ? [] : logs)
    }
    if (path === '/admin/assets') return json([{ assetId: 1, symbol: 'AAPL', name: 'Apple', assetType: 'STOCK', currentPrice: 100, assetStatus: 'ACTIVE' }])
    if (path === '/admin/users') return json({ content: [{ id: 1, firstName: 'Current', lastName: 'Admin', emailAddress: 'admin@example.test', role: 'ADMIN', status: 'ACTIVE' }], totalElements: 1, totalPages: 1 })
    if (path === '/notifications/subscribe') return new Response(new ReadableStream({ start(controller) {
      controller.enqueue(new TextEncoder().encode(': connected\\n\\n'))
      options.signal.addEventListener('abort', () => { try { controller.close() } catch {} }, { once: true })
    } }), { headers: { 'Content-Type': 'text/event-stream' } })
    if (path === '/portfolio/performance') return json({ cashBalance: 1000, totalPortfolioValue: 1000, totalHoldingsValue: 0, totalInvested: 0, totalGainLoss: 0, totalGainLossPercent: 0 })
    if (path === '/portfolio/holdings' || path === '/assets') return json([])
    return json({})
  }
})()`

let fixtureId
try {
  await send('Runtime.enable'); await send('Page.enable')
  fixtureId = (await send('Page.addScriptToEvaluateOnNewDocument', { source: fixture })).identifier
  await send('Page.navigate', { url: appUrl })
  await wait(`document.readyState === 'complete' && typeof window.__auditSet === 'function'`)
  await evaluate(`localStorage.clear(); sessionStorage.clear()`)
  await send('Page.reload'); await wait(`document.body.innerText.includes('Welcome back')`)
  await field('emailAddress', 'admin@example.test'); await field('password', 'synthetic-test-input'); await click('Login')
  await header('Admin Dashboard')
  await click('Audit Logs'); await header('Audit Logs')
  await wait(`Boolean(document.querySelector('#main-content .loading-spinner')) && typeof window.__auditRelease === 'function'`)
  assert.equal(await evaluate(`Boolean(document.querySelector('#main-content table'))`), false)
  await evaluate('window.__auditRelease()')
  await wait(`document.querySelectorAll('tbody tr').length === 2`)
  assert.deepEqual(await evaluate(`Array.from(document.querySelectorAll('thead th'), element => element.textContent)`), ['Date & Time', 'User Email', 'Action', 'Details'])
  assert.deepEqual(await evaluate(`Array.from(document.querySelectorAll('tbody tr'), row => row.children[1].textContent)`), ['investor@example.test', 'older@example.test'])
  assert.equal(await evaluate(`document.querySelector('time').textContent`), await evaluate(`new Date('2026-10-26T10:30:00').toLocaleString()`))
  assert.equal(await evaluate(`document.querySelector('time').getAttribute('datetime')`), '2026-10-26T10:30:00')
  assert.equal(await evaluate(`document.querySelectorAll('tbody button, tbody input, tbody select').length`), 0)
  const auditCalls = await evaluate(`JSON.parse(sessionStorage.__auditFixture).calls.filter(call => call.path === '/admin/audit-logs')`)
  assert.ok(auditCalls.length > 0)
  assert.ok(auditCalls.every(call => call.method === 'GET' && call.bearer === 'Bearer synthetic-audit-ADMIN'))

  await click('Asset Management'); await header('Asset Management'); await wait(`document.body.innerText.includes('Apple')`)
  await setState({ mode: 'empty' }); await click('Audit Logs'); await wait(`document.body.innerText.includes('No audit logs yet.')`)
  assert.equal(await evaluate(`Boolean(document.querySelector('#main-content table'))`), false)
  await click('User Management'); await header('User Management'); await wait(`document.body.innerText.includes('Current Admin')`)
  await setState({ mode: 'error' }); await click('Audit Logs'); await wait(`Boolean(document.querySelector('#main-content [role=alert]'))`)
  assert.match(await evaluate(`document.querySelector('#main-content [role=alert]').textContent`), /temporarily unavailable/)
  assert.equal(await evaluate(`document.body.innerText.includes('Internal database details')`), false)
  await setState({ mode: 'success' }); await click('Try again'); await wait(`document.querySelectorAll('tbody tr').length === 2`)

  // Shared responsive wrapper keeps the table scrollable inside the existing card.
  await send('Emulation.setDeviceMetricsOverride', { width: 375, height: 812, deviceScaleFactor: 1, mobile: true })
  assert.ok(await evaluate(`(() => { const table = document.querySelector('.table-scroll'); return table.scrollWidth > table.clientWidth && getComputedStyle(table).overflowX === 'auto' })()`))
  assert.equal(await evaluate('document.documentElement.scrollWidth'), 375)
  assert.equal(await evaluate(`getComputedStyle(document.querySelector('#main-content section')).backgroundColor`), 'rgb(21, 31, 48)')
  await click('Menu'); await click('Asset Management'); await header('Asset Management')
  await click('Menu'); await click('Audit Logs'); await header('Audit Logs'); await wait(`document.querySelectorAll('tbody tr').length === 2`)
  await send('Emulation.clearDeviceMetricsOverride')

  await click('Logout'); await wait(`document.body.innerText.includes('Welcome back')`)
  await setState({ role: 'USER', calls: [] })
  await field('emailAddress', 'admin@example.test'); await field('password', 'synthetic-test-input'); await click('Login'); await header('Dashboard')
  assert.equal(await evaluate(`Boolean(document.querySelector('nav button[aria-label="Audit Logs"]'))`), false)
  assert.equal(await evaluate(`JSON.parse(sessionStorage.__auditFixture).calls.some(call => call.path === '/admin/audit-logs')`), false)
  assert.deepEqual(exceptions, [])
  console.log('PASS: Audit Logs uses JWT GET requests, preserves backend order, formats dates, remains read-only, handles loading/error/retry/empty states, matches shared card/table styles, supports mobile navigation and scrolling, and is absent for investors. Existing asset/user sections render. Mock API only.')
} finally {
  if (fixtureId) await send('Page.removeScriptToEvaluateOnNewDocument', { identifier: fixtureId })
  await send('Emulation.clearDeviceMetricsOverride')
  socket.close()
}
