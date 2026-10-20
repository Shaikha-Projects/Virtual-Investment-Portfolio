import { useEffect, useState } from 'react'
import { friendlyError } from '../api/client'

export function useResource(api, path, revision = 0) {
  const [state, setState] = useState({ data: null, path: '', revision: -1, loading: true, error: '' })
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    api(path, { signal: controller.signal }).then(data => {
      if (!controller.signal.aborted) setState({ data, path, revision, loading: false, error: '' })
    }).catch(error => {
      if (!controller.signal.aborted && error.name !== 'AbortError') setState({ data: null, path, revision, loading: false, error: friendlyError(error) })
    })
    return () => controller.abort()
  }, [api, path, revision, attempt])
  const loading = state.path !== path || state.revision !== revision || state.loading
  return { ...state, loading, error: loading ? '' : state.error, reload: () => { setState(previous => ({ ...previous, loading: true, error: '' })); setAttempt(value => value + 1) } }
}
