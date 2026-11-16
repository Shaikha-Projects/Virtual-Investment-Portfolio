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
async function evaluate(expression, attempt = 0) {
  let result
  try { result = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }) }
  catch (error) {
    if (attempt >= 10 || !/navigated|context.*destroyed|Cannot find context/i.test(error.message)) throw error
    await new Promise(resolve => setTimeout(resolve, 100))
    return evaluate(expression, attempt + 1)
  }
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
const tick = () => evaluate('Promise.all([...window.__fullTimers.values()].map(callback => callback()))')
const heading = text => wait(`document.querySelector('.topbar h1')?.textContent === ${JSON.stringify(text)}`)
const fixture = String.raw`(() => {
  const nativeFetch = window.fetch.bind(window), nativeInterval = window.setInterval.bind(window), nativeClear = window.clearInterval.bind(window)
  window.__fullTimers = new Map(); let timerId = 100000
  window.setInterval = (callback, delay, ...args) => { if (delay !== 10000) return nativeInterval(callback, delay, ...args); const id = ++timerId; window.__fullTimers.set(id, callback); return id }
  window.clearInterval = id => { if (!window.__fullTimers.delete(id)) nativeClear(id) }
  window.__fullCalls = []
  window.__state = {catalogFailure:false, historyFailure:false, emptyHistory:false, emptyAssets:false, noHoldings:false, noPerformance:false, deferId:0, aborted:0}
  if (sessionStorage.__fullPublic) localStorage.removeItem('token'); else localStorage.setItem('token', 'synthetic-full-token')
  const assets = [
    {assetId:1,symbol:'MSFT',name:'Microsoft',currentPrice:430,assetType:'STOCK',assetStatus:'ACTIVE'},
    {assetId:2,symbol:'TSLA',name:'Tesla',currentPrice:305,assetType:'STOCK',assetStatus:'ACTIVE'},
    {assetId:3,symbol:'AAPL',name:'Apple',currentPrice:225,assetType:'STOCK',assetStatus:'ACTIVE'},
    {assetId:4,symbol:'AMZN',name:'Amazon',currentPrice:200,assetType:'STOCK',assetStatus:'ACTIVE'}]
  assets.push({assetId:5,symbol:'TEST',name:'Synthetic fifth holding',currentPrice:100,assetType:'STOCK',assetStatus:'ACTIVE'})
  const reply = (data, status=200) => new Response(JSON.stringify(data), {status,headers:{'Content-Type':'application/json'}})
  const records = id => Array.from({length:125}, (_,index) => ({price:assets[id-1].currentPrice + Math.sin(index*.47)*1.3 -(124-index)*.012, recordedAt:new Date(Date.now()-(124-index)*10000).toISOString()}))
  window.fetch = async (input, options={}) => {
    const url = new URL(input, location.href)
    if (url.origin === location.origin) return nativeFetch(input, options)
    // Fail closed: no request is forwarded to any real service.
    if (!['8083','8084','8085'].includes(url.port)) throw Error('Unexpected external request blocked')
    if (options.signal?.aborted) throw new DOMException('Cancelled','AbortError')
    const path = url.pathname
    window.__fullCalls.push({path,method:options.method||'GET',bearer:new Headers(options.headers).get('Authorization')})
    if (path === '/auth/login') return reply({token:'synthetic-full-token'})
    if (['/auth/register','/auth/forgot-password','/auth/reset-password','/auth/change-password'].includes(path)) return reply({message:'Completed'},path==='/auth/register'?201:200)
    if (path === '/help/ask') return reply({answer:'Open Cash Wallet to use the existing deposit form. Only virtual funds are used.',sources:[{sourceId:'guide-wallet',title:'Cash Wallet'}]})
    if (path === '/portfolio/buy' || path === '/portfolio/sell') return reply({transactionId:10,status:'COMPLETED'})
    if (path === '/watchlist' && options.method==='POST') return reply({message:'Added'})
    if (path.startsWith('/watchlist/') && options.method==='DELETE') {window.__watchRemoved=true; return reply({message:'Removed'})}
    if (path.startsWith('/watchlist/') && options.method==='POST') {window.__watchRemoved=false; return reply({message:'Added'})}
    if (path === '/portfolio/wallet/withdrawals' || path === '/portfolio/wallet/deposits') return reply({reference:crypto.randomUUID(),status:'COMPLETED',amount:JSON.parse(options.body).amount})
    if (path === '/portfolio/wallet/history') return reply({content:[{reference:'123e4567-e89b-42d3-a456-426614174000',operationType:'DEPOSIT',status:'COMPLETED',amount:250,balanceAfter:12480,createdAt:new Date().toISOString()}],totalPages:1,totalElements:1,number:0})
    if (path === '/portfolio/wallet/statement' || path.endsWith('/receipt')) return new Response('%PDF-1.4 fixture',{headers:{'Content-Type':'application/pdf'}})
    if (path === '/profile/picture') return reply({message:'Updated'})
    if (path === '/profile' && options.method==='PATCH') {window.__profile=JSON.parse(options.body);return reply(window.__profile)}
    if (path === '/profile') return reply({firstName:'Design',lastName:'Review',emailAddress:'design-review@example.test',phoneNumber:'12345678',...window.__profile})
    if (path === '/admin/users/2/status') {window.__otherStatus=url.searchParams.get('status');return reply({message:'Updated'})}
    if (path === '/admin/users') return reply({content:[{id:1,firstName:'Design',lastName:'Review',emailAddress:'design-review@example.test',role:'ADMIN',status:'ACTIVE'},{id:2,firstName:'Sample',lastName:'Investor',emailAddress:'sample@example.test',role:'USER',status:window.__otherStatus||'ACTIVE'}],totalPages:1,totalElements:2,number:0})
    if (path === '/admin/audit-logs') return reply({content:[{id:1,userEmail:'sample@example.test',action:'CREATE_ASSET',details:'Created an asset in the synthetic test catalog',createdAt:new Date().toISOString()}],totalPages:1,totalElements:1,number:0})
    if (path === '/admin/assets' && options.method==='POST') {assets.push({...JSON.parse(options.body),assetId:5,assetStatus:'ACTIVE'});return reply(assets.at(-1))}
    if (path.startsWith('/admin/assets/') && options.method==='PUT') {Object.assign(assets.find(asset=>asset.assetId===Number(path.split('/')[3])),JSON.parse(options.body));return reply({message:'Updated'})}
    if (path.endsWith('/status') && path.startsWith('/admin/assets/')) {Object.assign(assets.find(asset=>asset.assetId===Number(path.split('/')[3])),JSON.parse(options.body));return reply({message:'Updated'})}
    if (path === '/auth/me' && sessionStorage.__fullDelayIdentity) await new Promise(resolve=>{window.__releaseFullIdentity=resolve})
    if (path === '/auth/me' && sessionStorage.__fullIdentityFailure) return reply({},503)
    if (path === '/auth/me') return reply({emailAddress:'design-review@example.test',role:sessionStorage.__fullRole || 'USER'})
    if (path === '/notifications/subscribe') return new Response(new ReadableStream({start(controller) { window.__fullNotify=()=>controller.enqueue(new TextEncoder().encode('id: final-ui-alert\nevent: investment-notification\ndata: Price alert triggered: MSFT reached $430.00 (target: $420.00)\n\n')); options.signal?.addEventListener('abort', () => { try {controller.close()} catch {} }, {once:true}) }}),{headers:{'Content-Type':'text/event-stream'}})
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
    if (path === '/watchlist') return reply(window.__watchRemoved ? [] : [{watchlistId:1,assetSymbol:'MSFT',assetName:'Microsoft',currentPrice:430,createdAt:new Date().toISOString()}])
    if (path === '/price-alerts') return reply([])
    if (path === '/orders' || path === '/portfolio/wallet/history' || path === '/admin/audit-logs' || path === '/portfolio/transactions') return reply({content:[],totalPages:0,totalElements:0,number:0})
    throw Error('Unhandled fixture API path: '+path)
  }
})()`
const output = new URL('../../target/workspace-layout/', import.meta.url)
await mkdir(output,{recursive:true})
let injection
const measurements=[]
async function viewport(width,height,name) {
  await send('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile:width<600})
  await evaluate('document.fonts.ready.then(()=>true)')
  await new Promise(resolve=>setTimeout(resolve,180))
  const result=await evaluate(`(() => {
    const main=document.querySelector('#main-content'),header=document.querySelector('.orbit-header');
    const selectors=['.folio-summary','.integrated-history','.asset-performance','.folio-holdings','.folio-side','.folio-help','.folio-disclosure'];
    return {width:innerWidth,height:innerHeight,documentHeight:document.documentElement.scrollHeight,mainHeight:main.clientHeight,contentHeight:main.scrollHeight,headerTop:header.getBoundingClientRect().top,sections:selectors.map(selector=>{const node=document.querySelector(selector),r=node.getBoundingClientRect();return {selector,top:r.top,bottom:r.bottom}}),charts:[...document.querySelectorAll('.folio-charts svg.price-chart-svg')].map(node=>({height:node.getBoundingClientRect().height,viewBoxHeight:node.viewBox.baseVal.height})),navigation:[...document.querySelectorAll('#primary-navigation > button:not(.orbit-menu-close)')].map(n=>{const r=n.getBoundingClientRect();return {label:n.textContent.trim(),left:r.left,right:r.right,top:r.top,bottom:r.bottom,height:r.height}}),brandRight:document.querySelector('.orbit-brand').getBoundingClientRect().right,accountLeft:document.querySelector('.header-account').getBoundingClientRect().left,headerHeight:header.getBoundingClientRect().height,rows:document.querySelectorAll('.folio-holdings tbody tr').length,horizontalOverflow:document.documentElement.scrollWidth>innerWidth};
  })()`)
  measurements.push(result)
  assert.equal(result.horizontalOverflow,false)
  assert.equal(result.rows,5)
  if(width>1200){
    assert.deepEqual(result.navigation.map(link=>link.label),['Dashboard','Assets','Holdings','Transactions','Orders','Wallet','Watchlist','Price Alerts','AI Help'])
    assert.ok(result.navigation.every(link=>link.left>=result.brandRight&&link.right<=result.accountLeft&&link.height>=44),'All investor destinations are visible without overlap')
    assert.ok(result.headerHeight<=68,'Header retains the compact dashboard height')
  }
  if(width>1100 && height>=768) {
    assert.ok(result.documentHeight<=height+1,JSON.stringify(result))
    assert.ok(result.contentHeight<=result.mainHeight+1,JSON.stringify(result))
    assert.ok(result.sections.every(section=>section.top>=0&&section.bottom<=height+1),JSON.stringify(result))
  } else if(width>1100) {assert.ok(result.documentHeight<=height+1);assert.ok(result.contentHeight>result.mainHeight,'Short desktops preserve accessible content scrolling')}
  else assert.ok(result.documentHeight>height,'Tablet/mobile retain natural scrolling')
  assert.equal(result.charts.length,2)
  for(const chart of result.charts){assert.ok(chart.height>=172);assert.ok(Math.abs(chart.height-chart.viewBoxHeight)<1)}
  if(width<=1200){
    await click('Menu');await wait(`document.querySelector('.orbit-navigation.is-open')`)
    assert.ok(await evaluate(`([...document.querySelectorAll('#primary-navigation > button:not(.orbit-menu-close)')]).every(n=>{const r=n.getBoundingClientRect();return r.width>0&&r.right<=innerWidth})`))
    await send('Input.dispatchKeyEvent',{type:'keyDown',key:'Escape',code:'Escape'})
    await wait(`!document.querySelector('.orbit-navigation.is-open')`)
  }
  const capture=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false})
  await writeFile(new URL(name+'.png',output),Buffer.from(capture.data,'base64'))
}
try {
  await send('Page.enable');await send('Runtime.enable')
  injection=await send('Page.addScriptToEvaluateOnNewDocument',{source:fixture})
  await send('Page.navigate',{url:appUrl});await heading('Dashboard')
  await wait(`document.querySelectorAll('.folio-charts .price-chart-svg').length===2`)
  for(const [width,height] of [[1366,768],[1440,900],[1920,1080],[1366,640],[1024,768],[768,1024],[390,844]]) await viewport(width,height,`dashboard-${width}x${height}`)
  await send('Emulation.setDeviceMetricsOverride',{width:1366,height:768,deviceScaleFactor:1,mobile:false})
  await click('Assets');await heading('Assets');await wait(`document.querySelector('tbody tr')`)
  // Add rows in the isolated browser only, to exercise the existing scroll container.
  await evaluate(`(() => {const body=document.querySelector('tbody'),row=body.firstElementChild;for(let n=0;n<25;n++)body.append(row.cloneNode(true));})()`)
  await evaluate(`document.querySelector('.table-scroll').scrollTop=500`)
  assert.ok(await evaluate(`document.querySelector('.table-scroll').scrollTop>0`))
  assert.ok(await evaluate(`document.querySelector('.orbit-header').getBoundingClientRect().top===0&&document.documentElement.scrollHeight<=innerHeight+1`))
  assert.ok(await evaluate(`Math.abs(document.querySelector('thead th').getBoundingClientRect().top-document.querySelector('.table-scroll').getBoundingClientRect().top)<3`))
  await evaluate(`document.querySelector('.orbit-account-menu').open=true`)
  assert.ok(await evaluate(`document.querySelector('.orbit-nav-popover').getBoundingClientRect().bottom<innerHeight`))
  await evaluate(`document.querySelector('.orbit-account-menu').open=false`)
  await click('Profile');await heading('Profile');await wait(`document.querySelector('.profile-details')`)
  await click('Change Password')
  await evaluate(`document.querySelector('#main-content').scrollTop=10000;document.querySelector('input[name="newPassword"]').focus()`)
  assert.ok(await evaluate(`document.activeElement.name==='newPassword'&&document.querySelector('.orbit-header').getBoundingClientRect().top===0`))
  await click('Dashboard');await heading('Dashboard');await wait(`document.querySelector('#main-content').scrollTop===0`)
  await writeFile(new URL('verification.json',output),JSON.stringify({measurements,exceptions,mode:'Synthetic APIs; actual React and Chrome; five holdings; isolated long-table stress test'},null,2))
  assert.deepEqual(exceptions,[])
  console.log('PASS workspace layout:',JSON.stringify(measurements),'sticky header/table, reachable profile form and navigation')
} finally {
  if(injection)await send('Page.removeScriptToEvaluateOnNewDocument',{identifier:injection.identifier})
  socket.close()
}
