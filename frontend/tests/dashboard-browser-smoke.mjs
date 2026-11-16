// Real React + Chrome HTTP rendering, isolated fetch fixtures and controlled polling.
// Start Vite on 5191 and a disposable Chrome with remote debugging on 9237.
import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'

const appUrl = process.env.SMOKE_APP_URL || 'http://127.0.0.1:5191'
const debugUrl = process.env.SMOKE_DEBUG_URL || 'http://127.0.0.1:9237'
const target = (await (await fetch(`${debugUrl}/json`)).json()).find(item => item.type === 'page')
assert.ok(target, 'Start a disposable Chrome instance first')
const socket = new WebSocket(target.webSocketDebuggerUrl)
await new Promise(resolve => socket.addEventListener('open', resolve, { once: true }))
let sequence = 0
const pending = new Map(), exceptions = []
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
  for (let attempt = 0; attempt < 150; attempt++) {
    if (await evaluate(`Boolean(${expression})`)) return
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  throw new Error(`Timed out: ${expression}`)
}
const click = label => evaluate(`(() => { const button = [...document.querySelectorAll('button')].find(button => (button.getAttribute('aria-label') || button.textContent.trim()) === ${JSON.stringify(label)}); if (!button) throw Error('Missing button: '+${JSON.stringify(label)}); button.click(); })()`)
const select = id => evaluate(`(() => { const input = document.querySelector('.asset-selector select'); input.value = ${JSON.stringify(String(id))}; input.dispatchEvent(new Event('change', {bubbles:true})); })()`)
const tick = () => evaluate('Promise.all([...window.__dashboardTimers.values()].map(callback => callback()))')
const heading = text => wait(`document.querySelector('.topbar h1')?.textContent === ${JSON.stringify(text)}`)
const fixture = String.raw`(() => {
  const nativeFetch = window.fetch.bind(window), nativeInterval = window.setInterval.bind(window), nativeClear = window.clearInterval.bind(window)
  window.__dashboardTimers = new Map(); let timerId = 100000
  window.setInterval = (callback, delay, ...args) => { if (delay !== 10000) return nativeInterval(callback, delay, ...args); const id = ++timerId; window.__dashboardTimers.set(id, callback); return id }
  window.clearInterval = id => { if (!window.__dashboardTimers.delete(id)) nativeClear(id) }
  window.__dashboardCalls = []
  window.__state = {catalogFailure:false, historyFailure:false, emptyHistory:false, emptyAssets:false, noHoldings:false, noPerformance:false, deferId:0, aborted:0}
  localStorage.setItem('token', 'synthetic-dashboard-token')
  const assets = [
    {assetId:1,symbol:'MSFT',name:'Microsoft',currentPrice:430,assetType:'STOCK',assetStatus:'ACTIVE'},
    {assetId:2,symbol:'TSLA',name:'Tesla',currentPrice:305,assetType:'STOCK',assetStatus:'ACTIVE'},
    {assetId:3,symbol:'AAPL',name:'Apple',currentPrice:225,assetType:'STOCK',assetStatus:'ACTIVE'},
    {assetId:4,symbol:'AMZN',name:'Amazon',currentPrice:200,assetType:'STOCK',assetStatus:'ACTIVE'}]
  const reply = (data, status=200) => new Response(JSON.stringify(data), {status,headers:{'Content-Type':'application/json'}})
  const records = id => Array.from({length:125}, (_,index) => ({price:assets[id-1].currentPrice + Math.sin(index*.47)*1.3 -(124-index)*.012, recordedAt:new Date(Date.now()-(124-index)*10000).toISOString()}))
  window.fetch = async (input, options={}) => {
    const url = new URL(input, location.href)
    if (url.origin === location.origin) return nativeFetch(input, options)
    // Fail closed: no request is forwarded to any real service.
    if (!['8083','8084','8085'].includes(url.port)) throw Error('Unexpected external request blocked')
    if (options.signal?.aborted) throw new DOMException('Cancelled','AbortError')
    const path = url.pathname
    window.__dashboardCalls.push({path,method:options.method||'GET',bearer:new Headers(options.headers).get('Authorization')})
    if (path === '/auth/me') return reply({emailAddress:'design-review@example.test',role:sessionStorage.__dashboardRole || 'USER'})
    if (path === '/notifications/subscribe') return new Response(new ReadableStream({start(controller) { options.signal?.addEventListener('abort', () => { try {controller.close()} catch {} }, {once:true}) }}),{headers:{'Content-Type':'text/event-stream'}})
    if (path === '/portfolio/performance') return window.__state.noPerformance ? reply({},503) : reply({totalPortfolioValue:128480,cashBalance:12480,totalCostBasis:100000,holdingsValue:116000,unrealizedGainLoss:16000,realizedGainLoss:0,totalGainLoss:16000,totalReturnPercentage:14.22})
    if (path === '/portfolio/holdings') return reply(window.__state.noHoldings ? [] : assets.map((asset,index) => ({holdingId:index+1,assetId:asset.assetId,assetSymbol:asset.symbol,assetName:asset.name,quantity:100,currentPrice:asset.currentPrice,currentValue:100*asset.currentPrice,averageBuyPrice:asset.currentPrice*.85})))
    if (path === '/portfolio/history') return reply(Array.from({length:45},(_,index)=>({totalPortfolioValue:112480+index*(16000/44)+Math.sin(index*.7)*500,recordedAt:new Date(Date.now()-(44-index)*86400000).toISOString()})))
    if (path === '/assets') return window.__state.catalogFailure ? reply({},503) : reply(window.__state.emptyAssets ? [] : assets)
    if (/^\/assets\/\d+\/price-history$/.test(path)) {
      const id = Number(path.split('/')[2])
      if (window.__state.deferId === id) return new Promise((resolve,reject) => {
        window.__releaseDashboardHistory = () => resolve(reply(records(id)))
        options.signal?.addEventListener('abort',()=>{window.__state.aborted++; reject(new DOMException('Cancelled','AbortError'))},{once:true})
      })
      return window.__state.historyFailure ? reply({},503) : reply(window.__state.emptyHistory ? [] : records(id))
    }
    if (path === '/portfolio/wallet/balance') return reply({cashBalance:12480,reservedCash:1000,availableCash:11480})
    if (path === '/admin/assets') return reply(assets)
    if (path === '/admin/users') return reply({content:[],totalElements:2,totalPages:1,number:0})
    if (path === '/profile') return reply({firstName:'Design',lastName:'Review',emailAddress:'design-review@example.test',phoneNumber:'12345678'})
    if (path === '/watchlist' || path === '/price-alerts') return reply([])
    if (path === '/orders' || path === '/portfolio/wallet/history' || path === '/admin/audit-logs' || path === '/portfolio/transactions') return reply({content:[],totalPages:0,totalElements:0,number:0})
    throw Error('Unhandled fixture API path: '+path)
  }
})()`
const output = new URL('../../target/stage-one/', import.meta.url)
await mkdir(output, { recursive: true })
let injection
const checks = []
async function screenshot(name, width, height) {
  await send('Emulation.setDeviceMetricsOverride', {width,height,deviceScaleFactor:1,mobile:width<600})
  await evaluate('document.fonts.ready.then(() => true)')
  await evaluate('window.scrollTo(0, 0)')
  await new Promise(resolve => setTimeout(resolve, 250))
  assert.ok(await evaluate('document.documentElement.scrollWidth <= innerWidth'), `${name}: document overflow`)
  const result = await send('Page.captureScreenshot', {format:'png',captureBeyondViewport:false})
  await writeFile(new URL(name,output), Buffer.from(result.data,'base64'))
}
async function fullScreenshot(name) {
  const { cssContentSize } = await send('Page.getLayoutMetrics')
  const result = await send('Page.captureScreenshot', {format:'png',captureBeyondViewport:true,clip:{x:0,y:0,width:cssContentSize.width,height:cssContentSize.height,scale:1}})
  await writeFile(new URL(name,output), Buffer.from(result.data,'base64'))
}
try {
  await send('Page.enable'); await send('Runtime.enable')
  injection = await send('Page.addScriptToEvaluateOnNewDocument',{source:fixture})
  await evaluate(`if (location.origin === ${JSON.stringify(new URL(appUrl).origin)}) sessionStorage.removeItem('__dashboardRole')`)
  await send('Emulation.setDeviceMetricsOverride',{width:1440,height:960,deviceScaleFactor:1,mobile:false})
  await send('Page.navigate',{url:appUrl})
  await wait(`document.querySelectorAll('.folio-charts .price-history-chart').length === 2`)
  await evaluate('document.fonts.ready.then(() => true)')
  assert.equal(await evaluate(`document.querySelector('[data-testid="total-portfolio"]').textContent`),'$128,480.00')
  assert.equal(await evaluate(`document.querySelectorAll('.folio-holdings tbody tr').length`),4)
  assert.ok(await evaluate(`document.querySelector('.folio-wallet').textContent.includes('Cash Balance')`))
  assert.ok(await evaluate(`document.querySelector('.chart-footnote').textContent.includes('100 / 100')`))
  assert.ok(await evaluate(`!window.__dashboardCalls.some(call=>call.path === '/portfolio/transactions')`))
  assert.equal(await evaluate('window.__dashboardTimers.size'),2)
  checks.push('Real fixture values, latest 100 prices, four holdings, no recent-transaction request')
  await screenshot('dashboard-desktop.png',1440,960)
  await fullScreenshot('dashboard-desktop-full.png')
  const chartBounds = await evaluate(`Array.from(document.querySelectorAll('.folio-charts article'),node=>({top:node.getBoundingClientRect().top,bottom:node.getBoundingClientRect().bottom,left:node.getBoundingClientRect().left}))`)
  assert.equal(chartBounds[0].top,chartBounds[1].top)
  assert.ok(chartBounds.every(bounds=>bounds.bottom<800),'Both desktop charts should fit near the top')
  await evaluate('window.__dashboardCalls=[]'); await tick()
  assert.deepEqual(await evaluate('window.__dashboardCalls.map(call=>call.path).sort()'),['/assets','/assets/1/price-history'])
  await select(2); await wait(`document.querySelector('.asset-chart-meta strong')?.textContent.includes('TSLA') && document.querySelector('.asset-performance .price-history-chart')`)
  assert.equal(await evaluate('window.__dashboardTimers.size'),2)
  await evaluate('window.__state.historyFailure=true'); await tick()
  await wait(`document.querySelector('.asset-performance').textContent.includes('Prices delayed')`)
  assert.ok(await evaluate(`Boolean(document.querySelector('.asset-performance .price-history-chart'))`))
  await evaluate('window.__state.historyFailure=false'); await tick()
  await wait(`!document.querySelector('.asset-performance').textContent.includes('Prices delayed')`)
  await evaluate('window.__state.catalogFailure=true'); await tick()
  await wait(`document.querySelector('.asset-performance').textContent.includes('Asset prices delayed')`)
  assert.ok(await evaluate(`Boolean(document.querySelector('.asset-performance .price-history-chart'))`))
  await evaluate('window.__state.catalogFailure=false'); await tick()
  await wait(`!document.querySelector('.asset-performance').textContent.includes('Asset prices delayed')`)
  await evaluate('window.__state.historyFailure=true'); await select(4)
  await wait(`document.querySelector('.asset-performance').textContent.includes('Try again')`)
  assert.equal(await evaluate(`Boolean(document.querySelector('.asset-performance .price-history-chart'))`),false)
  await evaluate('window.__state.historyFailure=false'); await click('Try again')
  await wait(`document.querySelector('.asset-performance .price-history-chart')`)
  await evaluate('window.__state.deferId=3'); await select(3)
  await wait(`typeof window.__releaseDashboardHistory === 'function'`)
  const deferredCalls = await evaluate(`window.__dashboardCalls.filter(call=>call.path==='/assets/3/price-history').length`)
  await tick()
  assert.equal(await evaluate(`window.__dashboardCalls.filter(call=>call.path==='/assets/3/price-history').length`),deferredCalls,'Pending history cannot overlap the next polling tick')
  await select(4); await wait(`document.querySelector('.asset-chart-meta strong')?.textContent.includes('AMZN') && document.querySelector('.asset-performance .price-history-chart')`)
  assert.ok(await evaluate('window.__state.aborted>0'))
  await evaluate('window.__releaseDashboardHistory(); window.__state.deferId=0')
  assert.ok(await evaluate(`document.querySelector('.asset-chart-meta strong').textContent.includes('AMZN')`))
  checks.push('Exactly two polling streams, simulated 10-second ticks, selection cancellation and stale-history recovery')
  await evaluate(`document.querySelector('.asset-performance .price-history-chart').focus()`)
  await send('Input.dispatchKeyEvent',{type:'keyDown',key:'Home',code:'Home'})
  await wait(`document.querySelector('.asset-performance .price-chart-readout strong')`)
  await send('Input.dispatchKeyEvent',{type:'keyUp',key:'Home',code:'Home'})
  assert.ok(await evaluate(`document.querySelector('.asset-performance .price-chart-readout').textContent.includes('$')`))
  await select(1)
  await screenshot('dashboard-tablet.png',1024,900)
  await screenshot('dashboard-mobile.png',390,844)
  await fullScreenshot('dashboard-mobile-full.png')
  assert.ok(await evaluate(`(() => { const table=document.querySelector('.folio-holdings .table-scroll'); table.scrollLeft=table.scrollWidth; const scrolled=table.scrollLeft>0; table.scrollLeft=0; return scrolled })()`),'Mobile holding values must remain reachable by horizontal table scrolling')
  await click('Menu'); await wait(`document.querySelector('.orbit-navigation.is-open')`)
  await send('Input.dispatchKeyEvent',{type:'keyDown',key:'Tab',code:'Tab',modifiers:8})
  assert.ok(await evaluate(`document.querySelector('#primary-navigation').contains(document.activeElement)`))
  await send('Input.dispatchKeyEvent',{type:'keyDown',key:'Escape',code:'Escape'})
  await wait(`!document.querySelector('.orbit-navigation.is-open')`)
  await screenshot('dashboard-narrow.png',360,800)
  checks.push('Desktop, tablet, 390px and 360px screenshots without document overflow; keyboard chart and mobile menu')
  await send('Emulation.setDeviceMetricsOverride',{width:1440,height:960,deviceScaleFactor:1,mobile:false})
  await click('Deposit'); await heading('Cash Wallet')
  await wait(`document.querySelector('.wallet-forms')`)
  assert.equal(await evaluate('location.pathname'),'/wallet')
  assert.equal(await evaluate('window.__dashboardTimers.size'),0)
  await click('Dashboard'); await wait(`document.querySelectorAll('.folio-charts .price-history-chart').length === 2`)
  await click('Withdraw'); await heading('Cash Wallet')
  assert.equal(await evaluate('location.pathname'),'/wallet')
  await click('Dashboard'); await wait(`document.querySelector('.folio-help')`)
  await click('View all holdings'); await heading('My Holdings')
  await click('Dashboard'); await wait(`document.querySelector('.folio-help')`)
  await evaluate(`document.querySelector('.folio-help').click()`); await heading('AI Help')
  for (const [label,title] of [['Transactions','Transactions'],['Watchlist','Watchlist'],['Price Alerts','Price Alerts']]) {
    await click(label); await heading(title)
  }
  for (const [label,title] of [['Assets','Assets'],['My Orders','My Orders']]) { await click(label); await heading(title) }
  await evaluate(`document.querySelector('.orbit-account-menu').open=true`)
  await click('Profile'); await heading('Profile')
  assert.ok(await evaluate(`window.__dashboardCalls.every(call=>call.bearer==='Bearer synthetic-dashboard-token')`))
  checks.push('Wallet entry points and /wallet behavior, timer cleanup, holdings/help links, direct investor destinations and bearer headers')
  await click('Dashboard'); await wait(`document.querySelector('.asset-chart-meta')`)
  await evaluate('window.__state.emptyAssets=true'); await tick()
  await wait(`document.querySelector('.asset-performance').textContent.includes('No active assets')`)
  assert.equal(await evaluate('window.__dashboardTimers.size'),1)
  await evaluate('window.__state.emptyAssets=false; window.__state.emptyHistory=true'); await tick()
  await wait(`document.querySelector('.asset-performance').textContent.includes('No recorded prices')`)
  await evaluate('window.__state.emptyHistory=false'); await tick()
  await wait(`document.querySelector('.asset-performance .price-history-chart')`)
  checks.push('Empty catalog, empty history and recovery preserve genuine empty states')
  await evaluate(`sessionStorage.__dashboardRole='ADMIN'`)
  await send('Page.reload'); await heading('Admin Dashboard')
  assert.equal(await evaluate('window.__dashboardTimers.size'),0)
  const adminLinks = await evaluate(`Array.from(document.querySelectorAll('nav[aria-label="Main navigation"] button'),node=>node.getAttribute('aria-label')).filter(Boolean)`)
  assert.deepEqual(adminLinks.filter(label=>label!=='Close navigation'),['Admin Dashboard','Asset Management','User Management','Audit Logs'])
  assert.equal(await evaluate(`document.querySelector('.notifications')`),null)
  for (const [label,title] of [['Asset Management','Asset Management'],['User Management','User Management'],['Audit Logs','Audit Logs'],['Admin Dashboard','Admin Dashboard']]) { await click(label); await heading(title) }
  await screenshot('admin-header-desktop.png',1440,960)
  assert.deepEqual(exceptions,[])
  checks.push('Admin navigation retains role restrictions; admin has no investor chart polls or notifications; no browser exceptions')
  await writeFile(new URL('verification.json',output),JSON.stringify({checks,chartBounds,exceptions,mode:'Real React, mocked APIs, controlled timers; no live backend requests'},null,2))
  console.log('PASS dashboard browser smoke:',checks.join('; '))
} finally {
  if (injection) await send('Page.removeScriptToEvaluateOnNewDocument',{identifier:injection.identifier})
  socket.close()
}
