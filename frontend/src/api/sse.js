// Incremental parser: supports CRLF, multiline data and frames split across chunks.
export function createSseParser(onEvent) {
  let buffer = ''
  return chunk => {
    buffer += chunk
    let boundary
    while ((boundary = /\r?\n\r?\n/.exec(buffer))) {
      const frame = buffer.slice(0, boundary.index)
      buffer = buffer.slice(boundary.index + boundary[0].length)
      let event = 'message', id = '', data = []
      for (const line of frame.split(/\r?\n/)) {
        if (line.startsWith(':')) continue
        const colon = line.indexOf(':')
        const key = colon < 0 ? line : line.slice(0, colon)
        const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
        if (key === 'event') event = value
        if (key === 'id') id = value
        if (key === 'data') data.push(value)
      }
      if (data.length) onEvent({ event, id, data: data.join('\n') })
    }
    if (buffer.length > 65536) throw new Error('Invalid event stream')
  }
}
