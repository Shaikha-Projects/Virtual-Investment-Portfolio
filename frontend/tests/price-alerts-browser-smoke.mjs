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
  window.__alerts = []; window.__posts = 0; window.__gets = 0; window.__streams = 0
  window.fetch = async (input, options = {}) => {
    const url = new URL(input, location.href)
    if (url.origin === location.origin) return original(input, options)
    const reply = (data, status = 200) => new Response(JSON.stringify(data), { status })
    if (url.pathname === '/auth/login') return reply({ token: 'alert-fixture' })
    if (url.pathname === '/auth/me') return reply({ role: 'USER', emailAddress: 'alerts@example.test' })
    if (url.pathname === '/notifications/subscribe') {
      window.__streams++
      return new Response(new ReadableStream({ start(controller) { window.__emit = text => controller.enqueue(new TextEncoder().encode(text)); options.signal.addEventListener('abort', () => controller.close(), { once: true }) } }), { headers: { 'Content-Type': 'text/event-stream' } })
    }
    if (url.pathname === '/assets') return reply([{ assetId: 7, symbol: 'TEST', name: 'Test Asset', currentPrice: '100.00' }])
    if (url.pathname === '/price-alerts') {
      if (options.method === 'POST') {
        window.__posts++; window.__body = JSON.parse(options.body)
        if (window.__delayPost) await new Promise(resolve => { window.__releasePost = resolve })
        if (window.__failPost) return reply({ message: 'Target price must be higher than current price' }, 400)
        window.__alerts.unshift({ id: window.__posts, assetSymbol: 'TEST', assetName: 'Test Asset', ...window.__body, status: 'ACTIVE', createdAt: new Date().toISOString(), triggeredAt: null })
        return new Response(null, { status: 201 })
      }
      window.__gets++
      if (window.__delayList) await new Promise(resolve => { window.__releaseList = resolve })
      if (window.__failList) return reply({}, 500)
      return reply(window.__alerts)
    }
    if (url.pathname.startsWith('/price-alerts/') && url.pathname.endsWith('/cancel')) {
      window.__alerts.find(alert => alert.id === Number(url.pathname.split('/')[2])).status = 'CANCELLED'
      return new Response(null, { status: 204 })
    }
    if (url.pathname === '/portfolio/performance') return reply({ cashBalance: 1000 })
    if (url.pathname === '/portfolio/transactions') return reply({ content: [] })
    return reply([])
  }
})()`

const select = (name, value) => evaluate(`(() => { const element = document.querySelector('select[name="${name}"]'); element.value = '${value}'; element.dispatchEvent(new Event('change', { bubbles: true })); })()`)
try {
  await send('Runtime.enable'); await send('Page.enable')
  await send('Page.addScriptToEvaluateOnNewDocument', { source: fixture })
  await send('Page.navigate', { url: appUrl })
  await wait(`document.readyState === 'complete'`)
  await evaluate('localStorage.clear()'); await send('Page.reload')
  await new Promise(resolve => setTimeout(resolve, 300))
  await wait(`Boolean(document.querySelector('input[name=emailAddress]'))`)
  await field('emailAddress', 'alerts@example.test'); await field('password', 'fixture-input'); await click('Login')
  await wait(`Boolean(document.querySelector('.portfolio-overview'))`)
  await click('Price Alerts'); await wait(`Boolean(document.querySelector('.price-alert-form'))`)
  await wait(`document.body.textContent.includes('No price alerts yet.')`)
  await select('assetId', '7')
  assert.ok(await evaluate(`document.querySelector('.price-alert-form').textContent.includes('$100.00')`))
  await field('targetPrice', '99'); await click('Create Alert')
  await wait(`document.body.textContent.includes('Target price must be higher')`)
  assert.equal(await evaluate('window.__posts'), 0)
  await field('targetPrice', '110.25')
  await evaluate('window.__delayPost = true')
  await click('Create Alert'); await wait(`Boolean(window.__releasePost)`)
  await evaluate(`document.querySelector('.price-alert-form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))`)
  assert.equal(await evaluate('window.__posts'), 1)
  assert.ok(await evaluate(`document.querySelector('.price-alert-form button').disabled`))
  await evaluate('window.__releasePost(); window.__delayPost = false')
  await wait(`document.querySelector('.price-alert-status')?.textContent === 'ACTIVE'`)
  assert.deepEqual(await evaluate('window.__body'), { assetId: 7, targetPrice: 110.25, condition: 'ABOVE' })
  await click('Cancel alert for TEST'); await wait(`document.querySelector('.price-alert-status')?.textContent === 'CANCELLED'`)
  assert.equal(await evaluate(`document.querySelectorAll('button[aria-label="Cancel alert for TEST"]').length`), 0)
  await evaluate('window.__failPost = true'); await field('targetPrice', '120'); await click('Create Alert')
  await wait(`document.querySelector('.alert-error')?.textContent.includes('Target price must be higher')`)
  await evaluate('window.__failPost = false'); await click('Create Alert')
  await wait(`document.querySelector('.price-alert-status')?.textContent === 'ACTIVE'`)
  const gets = await evaluate('window.__gets'), streams = await evaluate('window.__streams')
  await evaluate(`window.__alerts[0].status = 'TRIGGERED'; window.__alerts[0].triggeredAt = new Date().toISOString(); window.__emit(${JSON.stringify('id: alert-1\nevent: investment-notification\ndata: Price alert triggered: TEST reached $120.00 (target: $120.00)\n\n')})`)
  await wait(`document.querySelector('.price-alert-status')?.textContent === 'TRIGGERED'`)
  assert.equal(await evaluate('window.__streams'), streams, 'List refresh must not reconnect SSE')
  assert.equal(await evaluate('window.__gets'), gets + 1)
  await evaluate(`window.__emit(${JSON.stringify('id: alert-1\nevent: investment-notification\ndata: Price alert triggered: TEST reached $120.00 (target: $120.00)\n\n')})`)
  await new Promise(resolve => setTimeout(resolve, 300))
  assert.equal(await evaluate('window.__gets'), gets + 1, 'Duplicate SSE IDs must not refresh again')
  assert.ok(await evaluate(`document.querySelector('.notification-toasts').textContent.includes('Price alert triggered')`))
  await click('Notifications (1)')
  assert.ok(await evaluate(`document.querySelector('#live-notifications').textContent.includes('Price alert triggered')`))
  await click('Close notifications')
  for (const width of [1440, 800, 375]) {
    await send('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: width < 540 })
    assert.ok(await evaluate(`document.documentElement.scrollWidth <= ${width}`))
    assert.equal(await evaluate(`getComputedStyle(document.querySelector('.price-alert-fields')).gridTemplateColumns.split(' ').length`), width > 800 ? 3 : 1)
    assert.ok(await evaluate(`document.querySelector('.table-scroll').clientWidth <= document.querySelector('.price-alerts-page').clientWidth`))
  }
  await evaluate('window.__delayList = true')
  await click('Dashboard'); await wait(`Boolean(document.querySelector('.portfolio-overview'))`)
  await click('Price Alerts'); await wait(`Boolean(window.__releaseList)`)
  assert.ok(await evaluate(`Boolean(document.querySelector('.price-alerts-page .loading-state'))`))
  await evaluate('window.__alerts = []; window.__delayList = false; window.__releaseList()')
  await wait(`document.body.textContent.includes('No price alerts yet.')`)
  await evaluate('window.__failList = true')
  await click('Dashboard'); await wait(`Boolean(document.querySelector('.portfolio-overview'))`)
  await click('Price Alerts'); await wait(`Boolean(document.querySelector('.price-alerts-page .alert-error'))`)
  await evaluate('window.__failList = false'); await click('Try again')
  await wait(`document.body.textContent.includes('No price alerts yet.')`)
  assert.deepEqual(exceptions, [])
  console.log('PASS price alerts browser: validation, empty/loading, create empty 201, duplicate prevention, backend errors, list/statuses, cancellation 204, SSE toast/bell and one refresh without reconnection, desktop/tablet/mobile layout. Mock API only.')
} finally {
  await send('Emulation.clearDeviceMetricsOverride'); socket.close()
}

