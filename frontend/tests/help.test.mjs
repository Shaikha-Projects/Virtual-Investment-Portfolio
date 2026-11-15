import test, { before, after } from 'node:test'
import assert from 'node:assert/strict'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'
import { createApi, ApiError } from '../src/api/client.js'
import { askHelp, helpError, helpQuestion } from '../src/api/help.js'
import { sectionFor, workspaceFor } from '../src/auth/roles.js'

let server, HelpChat, HelpPage
before(async () => {
  server = await createServer({ server: { middlewareMode: true, hmr: false, ws: false, watch: null }, appType: 'custom' })
  const page = await server.ssrLoadModule('/src/pages/HelpPage.jsx')
  HelpChat = page.HelpChat
  HelpPage = page.default
})
after(async () => { await server?.close() })

const sources = [{ sourceId: 'user-guide-deposit', title: 'How do I deposit virtual USD?' }]
const result = { answer: 'Open Cash Wallet and choose Continue to Stripe.', sources }
const view = overrides => renderToStaticMarkup(createElement(HelpChat, {
  messages: [], question: '', pending: false, error: '', onChange() {}, onSubmit() {}, ...overrides,
}))

test('AI Help navigation is investor-only and follows the existing section guard', () => {
  assert.ok(workspaceFor('USER').navigation.some(([section, label]) => section === 'help' && label === 'AI Help'))
  assert.equal(sectionFor('USER', 'help'), 'help')
  assert.equal(sectionFor('ADMIN', 'help'), 'admin')
  assert.ok(!workspaceFor('ADMIN').navigation.some(([section]) => section === 'help'))
})

test('help requests use Investment, retain JWT headers, and send only the typed question', async t => {
  const calls = []
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push({ url, options })
    return new Response(JSON.stringify(result), { headers: { 'Content-Type': 'application/json' } })
  })
  assert.deepEqual(await askHelp(createApi({ token: 'unit-test-token' }), '  How do I deposit?  '), result)
  assert.equal(calls.length, 1)
  assert.equal(calls[0].url, 'http://localhost:8085/help/ask')
  assert.equal(calls[0].options.method, 'POST')
  assert.equal(calls[0].options.headers.get('Authorization'), 'Bearer unit-test-token')
  assert.deepEqual(JSON.parse(calls[0].options.body), { question: 'How do I deposit?' })
})

test('blank, non-text and oversized questions are rejected without making requests', async () => {
  let calls = 0
  const api = async () => { calls++; return result }
  for (const question of [null, undefined, {}, '', '   ', 'x'.repeat(2001)]) {
    await assert.rejects(askHelp(api, question), error => error.status === 400)
  }
  assert.equal(helpQuestion('x'.repeat(2000)).length, 2000)
  assert.equal(calls, 0)
})

test('unexpected provider responses are rejected safely', async () => {
  for (const response of [null, {}, { answer: '', sources: [] }, { answer: 'Answer', sources: null },
    { answer: 'Answer', sources: [{}] }]) {
    await assert.rejects(askHelp(async () => response, 'How do I deposit?'), /unexpected response/)
  }
  assert.deepEqual(await askHelp(async () => ({ answer: 'The application documentation does not explain that yet.', sources: [] }), 'Other question'),
    { answer: 'The application documentation does not explain that yet.', sources: [] })
})

test('HTTP errors have helpful messages and preserve normal session handling', async t => {
  for (const status of [400, 401, 403, 429, 503]) {
    let invalid = 0
    t.mock.method(globalThis, 'fetch', async url => url.endsWith('/auth/me')
      ? new Response('{"role":"USER"}') : new Response('{"message":"private provider details"}', { status }))
    try {
      await askHelp(createApi({ token: 'unit-test-token', onInvalid: () => invalid++ }), 'How do I deposit?')
      assert.fail('The failed request must reject')
    } catch (error) {
      const message = helpError(error)
      assert.doesNotMatch(message, /private provider details/)
      assert.match(message, { 400: /1 to 2,000/, 401: /log in again/, 403: /investor accounts/,
        429: /one minute/, 503: /disabled or temporarily unavailable/ }[status])
    }
    assert.equal(invalid, status === 401 ? 1 : 0)
    t.mock.restoreAll()
  }
})

test('network failures are understandable without exposing exception details', async t => {
  t.mock.method(globalThis, 'fetch', async () => { throw new Error('private network trace') })
  await assert.rejects(askHelp(createApi(), 'How do I deposit?'), error => {
    assert.match(helpError(error), /Unable to connect/)
    assert.doesNotMatch(helpError(error), /private network trace/)
    return true
  })
  assert.equal(helpError(new ApiError('The service took too long to respond. Please try again.')), 'The service took too long to respond. Please try again.')
})

test('cancelled requests cannot return an answer after leaving the page', async t => {
  const controller = new AbortController()
  t.mock.method(globalThis, 'fetch', async () => ({ ok: true, status: 200, json: async () => {
    controller.abort()
    return result
  } }))
  await assert.rejects(askHelp(createApi(), 'How do I deposit?', { signal: controller.signal }), error => error.name === 'AbortError')
})

test('questions, answers and source titles render as text rather than HTML', () => {
  const html = view({ messages: [
    { id: 'question', role: 'user', text: '<script>question</script>' },
    { id: 'answer', role: 'assistant', text: '<img src=x onerror=alert(1)>',
      sources: [{ sourceId: 'source', title: '<strong>Deposit guide</strong>' }] },
  ] })
  assert.match(html, /Documentation sources/)
  assert.match(html, /&lt;script&gt;question&lt;\/script&gt;/)
  assert.match(html, /&lt;img/)
  assert.match(html, /&lt;strong&gt;Deposit guide&lt;\/strong&gt;/)
  assert.doesNotMatch(html, /<script|<img|<strong>Deposit guide/)
})

test('pending requests show loading and disable the form, while errors remain readable', () => {
  const loading = view({ question: 'How do I deposit?', pending: true })
  assert.match(loading, /role="status"/)
  assert.match(loading, /Finding an answer/)
  assert.match(loading, /<button disabled="">Sending/)
  assert.match(loading, /<textarea[^>]*disabled=""/)
  const failed = view({ question: 'How do I deposit?', error: helpError(new ApiError('', 503)) })
  assert.match(failed, /role="alert"/)
  assert.match(failed, /disabled or temporarily unavailable/)
  assert.match(failed, /<button>Send<\/button>/)
  assert.doesNotMatch(failed, /Finding an answer/)
})

test('the page starts with empty local history and displays the guidance notice', () => {
  const html = renderToStaticMarkup(createElement(HelpPage, { api: async () => result }))
  assert.match(html, /Application guidance only, not investment advice/)
  assert.match(html, /Do not share passwords/)
  assert.match(html, /Ask about deposits/)
  assert.match(html, /<button disabled="">Send<\/button>/)
  assert.doesNotMatch(html, /Documentation sources/)
})
