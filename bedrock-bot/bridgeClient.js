const WebSocket = require('ws')
const { randomUUID } = require('crypto')

const QUERY_TIMEOUT_MS = 8000

class BridgeClient {
  constructor (host, port, onStatus, onEvent) {
    this.host = host
    this.port = port
    this.onStatus = onStatus || (() => {})
    this.onEvent = onEvent || (() => {})
    this.ws = null
    this.pending = new Map()
  }

  connect () {
    if (this.ws) return
    this.onStatus('connecting')
    const ws = new WebSocket(`ws://${this.host}:${this.port}/bridge`)
    this.ws = ws

    ws.on('open', () => this.onStatus('connected'))
    ws.on('close', () => {
      if (this.ws === ws) this.ws = null
      this._rejectAll(new Error('bridge connection closed'))
      this.onStatus('disconnected')
    })
    ws.on('error', (err) => {
      this.onStatus('error')
      console.error('[bridge]', this.host + ':' + this.port, err.message)
    })
    ws.on('message', (data) => this._handleMessage(data))
  }

  close () {
    if (!this.ws) return
    this.ws.close()
    this.ws = null
    this._rejectAll(new Error('bridge connection closed'))
  }

  query (what, args) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error('not connected'))
    }
    const reqId = randomUUID()
    const msg = { type: 'query', what, reqId, ...args }
    const promise = new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(reqId)
        reject(new Error('bridge query timed out: ' + what))
      }, QUERY_TIMEOUT_MS)
      this.pending.set(reqId, { resolve, reject, timer })
    })
    this.ws.send(JSON.stringify(msg))
    return promise
  }

  command (command, args) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      throw new Error('not connected')
    }
    this.ws.send(JSON.stringify({ type: 'command', command, ...args }))
  }

  _handleMessage (data) {
    let msg
    try {
      msg = JSON.parse(data.toString())
    } catch {
      return
    }
    if (msg.type === 'event') {
      const { type, event, ...extra } = msg
      this.onEvent(event, extra)
      return
    }
    if (msg.type !== 'queryResult' || !msg.reqId) return
    const entry = this.pending.get(msg.reqId)
    if (!entry) return
    this.pending.delete(msg.reqId)
    clearTimeout(entry.timer)
    entry.resolve(msg.result)
  }

  _rejectAll (err) {
    for (const { reject, timer } of this.pending.values()) {
      clearTimeout(timer)
      reject(err)
    }
    this.pending.clear()
  }
}

module.exports = { BridgeClient }
