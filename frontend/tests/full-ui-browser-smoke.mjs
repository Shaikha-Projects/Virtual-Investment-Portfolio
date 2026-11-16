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
const output = new URL('../../target/final-ui/', import.meta.url)
await mkdir(output, {recursive:true})
const views = [], checks = [], layouts = []
let injection
const input = (name,value) => evaluate(`(() => { const node=document.querySelector('[name="'+${JSON.stringify(name)}+'"]'); const prototype=node.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype; Object.getOwnPropertyDescriptor(prototype,'value').set.call(node,${JSON.stringify(value)}); node.dispatchEvent(new Event('input',{bubbles:true})); })()`)
const submit = selector => evaluate(`document.querySelector(${JSON.stringify(selector)}).requestSubmit()`)
async function route(label,title=label) {
  await click(label); await heading(title)
  await wait(`!document.querySelector('#main-content .loading-spinner')`)
}
async function capture(name) {
  for (const [width,height] of [[1366,768],[1440,900],[1920,1080],[768,1024],[390,844],[360,800]]) {
    await send('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile:width<600})
    await evaluate('document.fonts.ready.then(()=>true)')
    await evaluate(`window.scrollTo(0,0);document.querySelector('#main-content')?.scrollTo(0,0);document.querySelector('.login-card')?.scrollTo(0,0)`)
    await new Promise(resolve=>setTimeout(resolve,120))
    assert.ok(await evaluate('document.documentElement.scrollWidth<=innerWidth'),`${name}: overflow at ${width}`)
    const layout=await evaluate(`(() => {const main=document.querySelector('#main-content'),card=document.querySelector('.login-card');return {documentHeight:document.documentElement.scrollHeight,wallet:Boolean(document.querySelector('.wallet-shell')),mainHeight:main?.clientHeight,contentHeight:main?.scrollHeight,cardHeight:card?.clientHeight,cardContent:card?.scrollHeight,headerTop:document.querySelector('.orbit-header')?.getBoundingClientRect().top,mainOverflow:main&&getComputedStyle(main).overflowY}})()`)
    layouts.push({name,width,height,...layout})
    if(width>1100){
      if(layout.wallet){assert.ok(layout.documentHeight>height,'Wallet preserves natural scrolling');assert.equal(layout.mainOverflow,'visible')}
      else assert.ok(layout.documentHeight<=height+1,`${name}: document overflow at ${width}x${height}: ${JSON.stringify(layout)}`)
      if(layout.headerTop!=null)assert.equal(layout.headerTop,0)
      const reachable=await evaluate(`(() => {const scope=document.querySelector('.login-card')||document.querySelector('#main-content');const buttons=[...scope.querySelectorAll('form button:not(:disabled)')];for(const button of buttons){button.focus();const r=button.getBoundingClientRect();if(r.top<0||r.bottom>innerHeight+1)return false}return true})()`)
      assert.ok(reachable,`${name}: form action must remain reachable`)
      await evaluate(`document.activeElement?.blur();window.scrollTo(0,0);document.querySelector('#main-content')?.scrollTo(0,0);document.querySelector('.login-card')?.scrollTo(0,0)`)
    }
    await evaluate(`(() => {const chat=document.querySelector('.help-chat');if(chat)chat.scrollTo(0,chat.scrollHeight)})()`)
    const screenshot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false})
    await writeFile(new URL(`${name}-${width}x${height}.png`,output),Buffer.from(screenshot.data,'base64'))
  }
  views.push(name)
  await send('Emulation.setDeviceMetricsOverride',{width:1440,height:960,deviceScaleFactor:1,mobile:false})
}
try {
  await send('Page.enable'); await send('Runtime.enable'); await send('Page.setDownloadBehavior',{behavior:'deny'})
  await evaluate(`if(location.origin===${JSON.stringify(new URL(appUrl).origin)}) {sessionStorage.removeItem('__fullPublic');sessionStorage.removeItem('__fullRole')}`)
  injection=await send('Page.addScriptToEvaluateOnNewDocument',{source:fixture})
  await send('Page.navigate',{url:appUrl})
  await wait(`document.querySelector('.orbit-overview')`)
  await evaluate(`sessionStorage.__fullPublic='1';localStorage.removeItem('token')`)
  await send('Page.reload')
  await wait(`document.querySelector('input[name="emailAddress"]')`)
  await capture('login')
  await click('Create an account')
  await capture('registration')
  await input('firstName','Test');await input('lastName','Investor');await input('phoneNumber','12345678');await input('emailAddress','synthetic@example.test');await input('password','synthetic-input')
  await submit('.login-card form')
  await wait(`document.body.innerText.includes('Check your email')`)
  assert.ok(await evaluate(`window.__fullCalls.some(call=>call.path==='/auth/register'&&call.method==='POST')`))
  await click('Back to login');await click('Forgot password?');await capture('forgot-password')
  await input('emailAddress','synthetic@example.test');await submit('.login-card form')
  await wait(`document.body.innerText.includes('If your account is eligible')`)
  await send('Page.navigate',{url:appUrl+'/?token=synthetic-reset'})
  await wait(`document.querySelector('input[name="newPassword"]')`)
  await capture('reset-password')
  await input('newPassword','synthetic-new-input');await submit('.login-card form')
  await wait(`document.body.innerText.includes('Password reset successfully')`)
  assert.ok(await evaluate(`!location.search.includes('token=')`))
  await click('Back to login');await input('emailAddress','design-review@example.test');await input('password','synthetic-input');await click('Login')
  await heading('Dashboard');await wait(`document.querySelectorAll('.folio-charts .price-history-chart').length===2`)
  await capture('dashboard')
  checks.push('Registration/email instructions, login, forgot/reset password requests, reset URL cleanup and authenticated session')
  await route('Assets');await capture('assets')
  await click('View MSFT details');await wait(`document.querySelector('.asset-history-panel .price-history-chart')`)
  await capture('asset-details')
  await click('Buy MSFT');await wait(`document.querySelector('.trade-panel input')`)
  await capture('trade-form')
  await evaluate(`(() => {const node=document.querySelector('.trade-panel input');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(node,'0.5');node.dispatchEvent(new Event('input',{bubbles:true}));})()`)
  await submit('.trade-panel');await wait(`!document.querySelector('.trade-panel')`)
  assert.ok(await evaluate(`window.__fullCalls.some(call=>call.path==='/portfolio/buy'&&call.method==='POST')`))
  await route('My Holdings');await capture('holdings')
  await click('Sell MSFT');await wait(`document.querySelector('.trade-panel input')`)
  await evaluate(`(() => {const node=document.querySelector('.trade-panel input');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(node,'0.5');node.dispatchEvent(new Event('input',{bubbles:true}));})()`)
  await submit('.trade-panel');await wait(`!document.querySelector('.trade-panel')`)
  assert.ok(await evaluate(`window.__fullCalls.some(call=>call.path==='/portfolio/sell'&&call.method==='POST')`))
  await route('Transactions');await capture('transactions')
  await route('My Orders');await capture('orders')
  await click('Place Limit Order');await wait(`document.querySelector('.limit-order-form')`);await capture('order-form');await click('Close form')
  await route('Cash Wallet');await capture('wallet')
  assert.equal(await evaluate('location.pathname'),'/wallet')
  await input('wallet-withdrawals-amount','10.00');await submit('.wallet-form:nth-child(2)')
  await wait(`document.body.innerText.includes('Withdrawal completed.')`)
  assert.ok(await evaluate(`window.__fullCalls.some(call=>call.path==='/portfolio/wallet/withdrawals'&&call.method==='POST')`))
  await click('Export Statement')
  await wait(`window.__fullCalls.some(call=>call.path==='/portfolio/wallet/statement')`)
  await wait(`([...document.querySelectorAll('button')].find(button=>button.textContent.trim()==='Download PDF'))?.disabled===false`)
  await click('Download PDF')
  await wait(`window.__fullCalls.some(call=>call.path.endsWith('/receipt'))`)
  await route('Watchlist');await capture('watchlist')
  await click('Remove MSFT from watchlist');await wait(`document.body.innerText.includes('No assets in your watchlist')`)
  await route('Price Alerts');await capture('price-alerts')
  await evaluate('window.__fullNotify()')
  await wait(`document.querySelector('.notification-toast')`)
  await evaluate(`document.querySelector('.notification-button').click()`)
  await wait(`document.querySelector('#live-notifications')?.textContent.includes('Price alert triggered')`)
  await capture('notifications');await click('Close notifications')
  await route('AI Help');await capture('ai-help')
  await evaluate(`(() => {const node=document.querySelector('.help-form textarea');Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(node,'How do deposits work?');node.dispatchEvent(new Event('input',{bubbles:true}));})()`)
  await submit('.help-form');await wait(`document.querySelector('.help-message-assistant')`)
  assert.ok(await evaluate(`document.querySelector('.help-sources').textContent.includes('Cash Wallet')`))
  await capture('ai-help-conversation')
  await send('Emulation.setDeviceMetricsOverride',{width:1366,height:768,deviceScaleFactor:1,mobile:false})
  await evaluate(`(() => {const chat=document.querySelector('.help-chat'),sample=chat.lastElementChild;for(let i=0;i<12;i++)chat.append(sample.cloneNode(true));chat.focus();chat.scrollTop=chat.scrollHeight;})()`)
  assert.ok(await evaluate(`document.querySelector('.help-chat').scrollTop>0&&document.documentElement.scrollHeight<=innerHeight&&document.activeElement.classList.contains('help-chat')`),'Long Help conversation scrolls independently and accepts keyboard focus')
  await route('Profile');await capture('profile')
  await click('Edit Profile');await wait(`document.querySelector('input[name="firstName"]')`)
  await input('firstName','Updated');await submit('.profile-page .sub-form')
  await wait(`document.querySelector('.profile-details')?.textContent.includes('Updated')`)
  await click('Change Password');await capture('profile-security')
  // Exercise the synthetic password form only; no real authentication service is called.
  await input('currentPassword','synthetic-input');await input('newPassword','synthetic-new-input');await submit('.profile-page .sub-form')
  await wait(`!document.querySelector('input[name="currentPassword"]')`)
  await evaluate(`(() => {const input=document.querySelector('input[type=file]');const data=new DataTransfer();data.items.add(new File(['synthetic-image'], 'test.png', {type:'image/png'}));input.files=data.files;})()`)
  await click('Upload picture')
  await wait(`window.__fullCalls.some(call=>call.path==='/profile/picture'&&call.method==='PUT')`)
  checks.push('Investor pages, asset details/history, fractional buy/sell requests, wallet navigation/withdrawal, watchlist removal, Help sources, profile/password/upload forms, wallet PDF requests, notification SSE display')
  await evaluate(`sessionStorage.__fullRole='ADMIN';sessionStorage.removeItem('__fullPublic')`)
  await send('Page.reload');await heading('Admin Dashboard');await wait(`!document.querySelector('#main-content .loading-spinner')`)
  await capture('admin-dashboard')
  await route('Asset Management');await capture('admin-assets')
  await click('Add asset');await capture('admin-asset-form')
  await input('symbol','TEST');await input('name','Synthetic test asset');await input('currentPrice','99');await submit('.admin-assets-page .sub-form')
  await wait(`document.querySelector('tbody')?.textContent.includes('Synthetic test asset')`)
  await click('Edit');await wait(`document.querySelector('.sub-form')`);await input('name','Edited asset');await submit('.admin-assets-page .sub-form')
  await wait(`document.querySelector('tbody')?.textContent.includes('Edited asset')`)
  await click('Deactivate');await wait(`document.querySelector('tbody')?.textContent.includes('INACTIVE')`)
  await route('User Management');await capture('admin-users')
  assert.ok(await evaluate(`document.querySelector('tbody tr:first-child button').disabled`),'Self-deactivation remains blocked')
  await evaluate(`document.querySelector('tbody tr:nth-child(2) button').click()`)
  await capture('admin-confirmation');await click('Confirm deactivation')
  await wait(`document.querySelector('tbody')?.textContent.includes('INACTIVE')`)
  await route('Audit Logs');await capture('audit-logs')
  assert.ok(await evaluate(`document.querySelector('tbody')?.textContent.includes('Created an asset')`))
  await route('Profile');await capture('admin-profile')
  await click('Logout');await wait(`document.querySelector('.login-card form')`)
  assert.equal(await evaluate(`localStorage.getItem('token')`),null)
  await evaluate(`sessionStorage.removeItem('__fullPublic');sessionStorage.__fullDelayIdentity='1';sessionStorage.__fullIdentityFailure='1'`)
  await send('Page.reload')
  await wait(`typeof window.__releaseFullIdentity==='function'`)
  await capture('session-checking')
  await evaluate(`sessionStorage.removeItem('__fullDelayIdentity');window.__releaseFullIdentity()`)
  await wait(`document.body.innerText.includes('Account access unavailable')`)
  await capture('session-recovery')
  await evaluate(`sessionStorage.removeItem('__fullIdentityFailure')`)
  await click('Retry session check');await heading('Admin Dashboard')
  await click('Logout')
  assert.deepEqual(exceptions,[])
  checks.push('All administrator destinations, synthetic asset create/edit/status, self-deactivation protection, user deactivation, audit log display, logout')
  await writeFile(new URL('verification.json',output),JSON.stringify({views,checks,layouts,exceptions,mode:'Actual React rendering; all API responses synthetic; no backend mutation'},null,2))
  console.log(`PASS full UI browser: ${views.length} views at three desktop and three tablet/mobile sizes; ${checks.join('; ')}; no document overflow or browser exceptions`)
} finally {
  if(injection) await send('Page.removeScriptToEvaluateOnNewDocument',{identifier:injection.identifier})
  socket.close()
}
