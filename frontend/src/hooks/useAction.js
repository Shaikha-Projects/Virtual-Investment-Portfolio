import { useRef, useState } from 'react'
import { friendlyError } from '../api/client'

export function useAction() {
  const lock = useRef(false)
  const [pending, setPending] = useState(false)
  const [notice, setNotice] = useState(null)
  const run = async (operation, success) => {
    if (lock.current) return false
    lock.current = true; setPending(true); setNotice(null)
    try {
      await operation()
      if (success) setNotice({ kind: 'success', text: success })
      return true
    } catch (error) {
      if (error.name !== 'AbortError') setNotice({ kind: 'error', text: friendlyError(error) })
      return false
    } finally { lock.current = false; setPending(false) }
  }
  return { run, pending, notice, clear: () => setNotice(null), warn: text => setNotice({ kind: 'warning', text }) }
}
