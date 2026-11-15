import { ApiError, friendlyError } from './client.js'

export function helpQuestion(value) {
  if (typeof value !== 'string' || !value.trim() || value.length > 2000) {
    throw new ApiError('Enter a question using 1 to 2,000 characters.', 400)
  }
  return value.trim()
}

export async function askHelp(api, question, { signal } = {}) {
  const result = await api('/help/ask', { method: 'POST', body: { question: helpQuestion(question) }, signal })
  if (!result || typeof result.answer !== 'string' || !result.answer.trim() || !Array.isArray(result.sources)
    || result.sources.some(source => !source || typeof source.sourceId !== 'string' || !source.sourceId.trim()
      || typeof source.title !== 'string' || !source.title.trim())) {
    throw new ApiError('AI Help returned an unexpected response. Please try again later.')
  }
  return { answer: result.answer, sources: result.sources.map(({ sourceId, title }) => ({ sourceId, title })) }
}

export function helpError(error) {
  const messages = {
    400: 'Enter a question using 1 to 2,000 characters.',
    401: 'Your session has expired. Please log in again.',
    403: 'AI Help is available to investor accounts only.',
    429: 'You have reached the AI Help limit. Please wait one minute before trying again.',
    503: 'AI Help is disabled or temporarily unavailable. Please try again later.',
  }
  return error instanceof ApiError && messages[error.status] ? messages[error.status] : friendlyError(error)
}
