// SSE messages contain no transaction ID. Match each in-flight operation once,
// in request order, by its exact expected backend message.
export function createTransactionFeedback(showFallback, clearFallback) {
  const operations = []
  const forget = operation => {
    operation.active = false
    clearTimeout(operation.timer)
    const index = operations.indexOf(operation)
    if (index !== -1) operations.splice(index, 1)
  }
  return {
    async run(request, expectedMessage, fallback) {
      const operation = { id: Symbol(), expectedMessage, active: true, received: false }
      operations.push(operation)
      // Bound unmatched history when a stream stays unavailable for a long time.
      if (operations.length > 100) forget(operations[0])
      try {
        const response = await request()
        operation.completed = true
        if (operation.active && !operation.received) {
          operation.timer = setTimeout(() => {
            if (operation.active && !operation.received) showFallback({ id: operation.id, text: fallback })
          }, 1500)
        } else forget(operation)
        return response
      } catch (error) { forget(operation); throw error }
    },
    received(message) {
      const operation = operations.find(item => item.active && !item.received && item.expectedMessage === message)
      if (!operation) return
      operation.received = true
      clearTimeout(operation.timer)
      clearFallback(operation.id)
      if (operation.completed) forget(operation)
    },
    dispose() { for (const operation of [...operations]) forget(operation) },
  }
}
