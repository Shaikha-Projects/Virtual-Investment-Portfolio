import { useEffect, useId, useRef, useState } from 'react'
import { Alert } from '../components/UI'
import Icon from '../components/Icon'
import { askHelp, helpError, helpQuestion } from '../api/help'

export function HelpChat({ messages, question, pending, error, onChange, onSubmit }) {
  const questionId = useId()
  const chat = useRef(null)
  useEffect(() => {
    if (chat.current) chat.current.scrollTop = chat.current.scrollHeight
  }, [messages, pending])
  return <section className="card help-page">
    <div className="page-heading"><div><span className="eyebrow">Application guidance</span><h2>AI Help</h2><p className="muted">Ask how to use your virtual investment workspace.</p></div><span className="icon-tile"><Icon name="info" /></span></div>
    <p className="muted help-notice" id={`${questionId}-notice`}>Application guidance only, not investment advice. Do not share passwords, tokens, payment details, or private account information.</p>
    <div className="help-chat" role="log" aria-label="Help conversation" aria-live="polite" aria-relevant="additions" ref={chat}>
      {!messages.length && <p className="empty muted">Ask about deposits, limit orders, or another application feature.</p>}
      {messages.map(message => <article className={`help-message help-message-${message.role}`} key={message.id}>
        <strong>{message.role === 'user' ? 'You' : 'AI Help'}</strong><p>{message.text}</p>
        {message.sources?.length > 0 && <div className="help-sources"><strong>Documentation sources</strong><ul>{message.sources.map(source => <li key={source.sourceId}>{source.title}</li>)}</ul></div>}
      </article>)}
      {pending && <p className="help-loading muted" role="status"><span className="loading-spinner" aria-hidden="true" />Finding an answer in the user guide...</p>}
    </div>
    <Alert>{error}</Alert>
    <form className="help-form form-stack" onSubmit={onSubmit} aria-busy={pending}>
      <label className="field" htmlFor={questionId}><span>Your question</span><textarea id={questionId} rows={3} maxLength={2000} required value={question} disabled={pending} onChange={event => onChange(event.target.value)} aria-describedby={`${questionId}-notice`} /></label>
      <button disabled={pending || !question.trim()}>{pending ? 'Sending...' : 'Send'}</button>
    </form>
  </section>
}

export default function HelpPage({ api }) {
  const [messages, setMessages] = useState([])
  const [question, setQuestion] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const request = useRef(null)
  useEffect(() => () => request.current?.abort(), [])

  const send = async event => {
    event.preventDefault()
    if (request.current) return
    let text
    try { text = helpQuestion(question) }
    catch (failure) { setError(helpError(failure)); return }
    const controller = new AbortController()
    request.current = controller
    setPending(true); setError(''); setQuestion('')
    setMessages(previous => [...previous, { id: crypto.randomUUID(), role: 'user', text }])
    try {
      const result = await askHelp(api, text, { signal: controller.signal })
      if (!controller.signal.aborted) setMessages(previous => [...previous,
        { id: crypto.randomUUID(), role: 'assistant', text: result.answer, sources: result.sources }])
    } catch (failure) {
      if (!controller.signal.aborted && failure.name !== 'AbortError') {
        setError(helpError(failure)); setQuestion(text)
      }
    } finally {
      if (!controller.signal.aborted) setPending(false)
      if (request.current === controller) request.current = null
    }
  }

  return <HelpChat messages={messages} question={question} pending={pending} error={error} onChange={setQuestion} onSubmit={send} />
}
