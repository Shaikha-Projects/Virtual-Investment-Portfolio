import { useEffect, useRef, useState } from 'react'
import { friendlyError } from '../api/client'

export function usePollingResource(api, path, revision = 0, interval = 10000) {
  const [state, setState] = useState({ path: null, data: null, previousData: null, error: '', updatedAt: null })
  const [attempt, setAttempt] = useState(0)
  const inFlight = useRef(null)
  useEffect(() => {
    let disposed = false
    let request = null
    const poll = async () => {
      if (disposed || inFlight.current) return
      request = new AbortController()
      const controller = request
      inFlight.current = controller
      try {
        const data = await api(path, { signal: controller.signal })
        if (!disposed && !controller.signal.aborted) {
          setState(previous => ({ path, data, previousData: previous.path === path ? previous.data : null, error: '', updatedAt: Date.now() }))
        }
      } catch (error) {
        if (!disposed && !controller.signal.aborted && error.name !== 'AbortError') {
          setState(previous => ({ path, data: previous.path === path ? previous.data : null, previousData: previous.path === path ? previous.previousData : null, error: friendlyError(error), updatedAt: previous.path === path ? previous.updatedAt : null }))
        }
      } finally {
        if (inFlight.current === controller) inFlight.current = null
      }
    }
    poll()
    const timer = setInterval(poll, interval)
    return () => {
      disposed = true
      clearInterval(timer)
      request?.abort()
      if (inFlight.current === request) inFlight.current = null
    }
  }, [api, path, revision, interval, attempt])
  const current = state.path === path
  return {
    data: current ? state.data : null,
    previousData: current ? state.previousData : null,
    error: current ? state.error : '',
    updatedAt: current ? state.updatedAt : null,
    loading: !current || (state.data === null && !state.error),
    // Background failures retain the last successful snapshot instead of hiding
    // prices, resetting forms, or flashing the table on every poll.
    reload: () => { if (!inFlight.current) setAttempt(value => value + 1) },
  }
}
