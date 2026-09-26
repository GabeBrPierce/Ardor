const http = require('http')
const fs = require('fs')
const path = require('path')
const { fork } = require('child_process')
const { randomUUID } = require('crypto')
const { BridgeClient } = require('./bridgeClient')
const voices = require('./voices')
const narration = require('./narration')
const piper = require('./piper')

const PORT = Number(process.env.MANAGER_PORT) || 4243
const CONFIG_DIR = path.join(__dirname, 'config')
const INSTANCES_FILE = path.join(CONFIG_DIR, 'instances.json')
const SETTINGS_FILE = path.join(CONFIG_DIR, 'settings.json')
const PUBLIC_DIR = path.join(__dirname, 'public')
const BOT_FILE = path.join(__dirname, 'bot.js')
const CHAT_CACHE_DIR = path.join(__dirname, 'cache', 'narration')
const PREVIEW_CACHE_DIR = path.join(__dirname, 'cache', 'previews')
const CHAT_LOG_LIMIT = 200

const DEFAULT_SETTINGS = {
  defaultHost: 'localhost',
  defaultPort: 19132,
  tickMs: 100,
  walkSpeed: 0.15,
  // Narration: see bedrock-bot/scripts/install-voices.ps1 for what populates piper/ and voices/ by
  // default (both self-contained under bedrock-bot/, nothing installed system-wide), and voices.js
  // for how the voices directory is scanned. Only takes effect for a fresh settings.json -- an
  // existing one from before install-voices.ps1 downloaded the binary keeps whatever was there.
  piperBinaryPath: path.join(__dirname, 'piper', 'piper.exe'),
  voicesDir: path.join(__dirname, 'voices'),
  ardorVoiceId: '',
  defaultVoiceId: ''
}
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' }
const DEFAULT_BRIDGE_PORT = 24747
// Maps BridgeClient's connection-state vocabulary onto the same status strings/CSS classes
// bedrock instances already use, so the UI doesn't need to know kinds apart.
const BRIDGE_STATUS_MAP = { connecting: 'connecting', connected: 'spawned', disconnected: 'stopped', error: 'error' }

fs.mkdirSync(CONFIG_DIR, { recursive: true })
fs.mkdirSync(CHAT_CACHE_DIR, { recursive: true })
fs.mkdirSync(PREVIEW_CACHE_DIR, { recursive: true })
if (!fs.existsSync(INSTANCES_FILE)) fs.writeFileSync(INSTANCES_FILE, '[]')
if (!fs.existsSync(SETTINGS_FILE)) fs.writeFileSync(SETTINGS_FILE, JSON.stringify(DEFAULT_SETTINGS, null, 2))

let settings = { ...DEFAULT_SETTINGS, ...JSON.parse(fs.readFileSync(SETTINGS_FILE, 'utf8')) }
let instances = JSON.parse(fs.readFileSync(INSTANCES_FILE, 'utf8'))
  .map(i => ({ ...i, kind: i.kind || 'bedrock', status: 'stopped', proc: null, bridge: null, log: [], chatLog: [] }))

// Usernames actually seen in chat.message events, for the Username-to-Voice tab's dropdown.
// Runtime-only -- resets on manager restart, same as chatLog (see TODO.md).
const observedUsernames = new Set()

function saveInstances () {
  fs.writeFileSync(INSTANCES_FILE, JSON.stringify(instances.map(publicInstance), null, 2))
}
function saveSettings () {
  fs.writeFileSync(SETTINGS_FILE, JSON.stringify(settings, null, 2))
}
function publicInstance (i) {
  const { proc, bridge, log, chatLog, ...rest } = i
  return rest
}
function publicChatEntry (e) {
  const voice = e.voiceId ? voices.findVoice(settings.voicesDir, e.voiceId) : null
  return {
    id: e.id,
    ts: e.ts,
    kind: e.kind,
    sender: e.sender,
    text: e.text,
    voiceId: e.voiceId,
    voiceName: voice ? voice.name : null,
    muted: e.muted,
    audioStatus: e.audioStatus,
    audioError: e.audioError || null
  }
}

// -- Narration: turns a chat.message/ardor.message bridge event into a logged, (maybe) narrated
// chat entry. ardor.message always uses settings.ardorVoiceId and skips pattern/override/gender
// resolution entirely, per the ask. chat.message resolution can hit the network (narration.js's
// skin-model fallback tier), so the entry is logged immediately with audioStatus 'skipped' and
// patched in place once resolution finishes -- narration never delays the chat log itself.
function addChatEntry (inst, { kind, sender, text }) {
  if (kind === 'chat' && sender) observedUsernames.add(sender)

  const entry = {
    id: randomUUID(),
    ts: Date.now(),
    kind,
    sender,
    text,
    voiceId: null,
    muted: false,
    audioStatus: 'skipped',
    audioPath: null,
    audioError: null
  }

  inst.chatLog.push(entry)
  if (inst.chatLog.length > CHAT_LOG_LIMIT) inst.chatLog.shift()

  if (kind === 'ardor') {
    entry.voiceId = settings.ardorVoiceId || null
    maybeSynthesize(inst, entry)
    return entry
  }

  narration.resolveVoice(sender, settings, inst.kind).then((resolution) => {
    entry.voiceId = resolution.voiceId
    entry.muted = resolution.muted
    maybeSynthesize(inst, entry)
  }).catch((err) => {
    entry.audioStatus = 'error'
    entry.audioError = err.message
  })

  return entry
}

function maybeSynthesize (inst, entry) {
  if (entry.muted || !entry.voiceId) return
  entry.audioStatus = 'pending'
  synthesizeEntry(inst, entry).catch(err => {
    entry.audioStatus = 'error'
    entry.audioError = err.message
  })
}

async function synthesizeEntry (inst, entry) {
  const voice = voices.findVoice(settings.voicesDir, entry.voiceId)
  if (!voice) throw new Error('voice not found: ' + entry.voiceId)
  const outPath = path.join(CHAT_CACHE_DIR, `${inst.id}-${entry.id}.wav`)
  await piper.synthesize({ binaryPath: settings.piperBinaryPath, modelPath: voice.onnxPath, text: entry.text, outWav: outPath })
  entry.audioPath = outPath
  entry.audioStatus = 'ready'
}

function handleInstanceEvent (inst, eventName, payload) {
  if (eventName === 'chat.message') {
    addChatEntry(inst, { kind: 'chat', sender: payload.sender, text: payload.text })
  } else if (eventName === 'ardor.message') {
    addChatEntry(inst, { kind: 'ardor', sender: 'Ardor', text: payload.text })
  }
}

function startInstance (inst) {
  if (inst.kind === 'java') {
    if (inst.bridge) return
    inst.status = 'connecting'
    inst.bridge = new BridgeClient(inst.host, inst.port, (status) => {
      inst.status = BRIDGE_STATUS_MAP[status] || status
      // A dead connection must clear inst.bridge too, or a later Start silently no-ops forever
      // (the truthy-bridge guard above never lets it reconnect) -- found live: a real disconnect
      // (bridge unreachable, then reachable again) left Start doing nothing on retry.
      if (status === 'disconnected' || status === 'error') inst.bridge = null
    }, (eventName, payload) => handleInstanceEvent(inst, eventName, payload))
    inst.bridge.connect()
    return
  }

  if (inst.proc) return
  inst.status = 'connecting'
  const child = fork(BOT_FILE, [], {
    env: {
      ...process.env,
      HOST: inst.host,
      PORT: String(inst.port),
      USERNAME: inst.username,
      OFFLINE: String(inst.offline !== false),
      TICK_MS: String(settings.tickMs),
      WALK_SPEED: String(settings.walkSpeed)
    },
    stdio: ['ignore', 'pipe', 'pipe', 'ipc']
  })
  inst.proc = child
  inst.log = []
  const pushLog = (line) => {
    inst.log.push(line.toString())
    if (inst.log.length > 200) inst.log.shift()
  }
  child.stdout.on('data', pushLog)
  child.stderr.on('data', pushLog)
  child.on('message', (msg) => {
    if (msg && msg.type === 'status') inst.status = msg.status
  })
  child.on('exit', () => {
    inst.status = 'stopped'
    inst.proc = null
  })
}

function stopInstance (inst) {
  if (inst.kind === 'java') {
    if (inst.bridge) inst.bridge.close()
    inst.bridge = null
    return
  }

  if (!inst.proc) return
  inst.proc.kill('SIGINT')
}

function send (res, code, body) {
  res.writeHead(code, { 'Content-Type': 'application/json' })
  res.end(JSON.stringify(body))
}

function readBody (req) {
  return new Promise((resolve, reject) => {
    let data = ''
    req.on('data', c => { data += c })
    // JSON.parse throwing here is NOT the same as this promise rejecting on its own -- by the time
    // 'end' fires, the executor above has already returned, so a throw inside this listener is a
    // genuine uncaught exception (crashes the whole process), not an automatic promise rejection.
    // Confirmed live: a single malformed request body took down the entire manager, disconnecting
    // every instance's bridge with it. Must reject explicitly.
    req.on('end', () => {
      try {
        resolve(data ? JSON.parse(data) : {})
      } catch (err) {
        reject(new Error('invalid JSON body: ' + err.message))
      }
    })
    req.on('error', reject)
  })
}

async function handleRequest (req, res) {
  const { pathname } = new URL(req.url, 'http://localhost')

  if (pathname === '/api/instances' && req.method === 'GET') {
    return send(res, 200, instances.map(publicInstance))
  }

  if (pathname === '/api/instances' && req.method === 'POST') {
    const body = await readBody(req)
    const kind = body.kind === 'java' ? 'java' : 'bedrock'
    const inst = {
      id: Date.now().toString(36),
      kind,
      username: body.username,
      host: body.host || settings.defaultHost,
      port: Number(body.port) || (kind === 'java' ? DEFAULT_BRIDGE_PORT : settings.defaultPort),
      offline: body.offline !== false,
      selected: false,
      status: 'stopped',
      proc: null,
      bridge: null,
      log: [],
      chatLog: []
    }
    instances.push(inst)
    saveInstances()
    return send(res, 201, publicInstance(inst))
  }

  const bridgeMatch = pathname.match(/^\/api\/instances\/([^/]+)\/bridge\/(query|command)$/)
  if (bridgeMatch && req.method === 'POST') {
    const inst = instances.find(i => i.id === bridgeMatch[1])
    if (!inst) return send(res, 404, { error: 'not found' })
    if (inst.kind !== 'java' || !inst.bridge) return send(res, 409, { error: 'instance not connected' })
    const body = await readBody(req)
    try {
      if (bridgeMatch[2] === 'query') {
        const { what, ...args } = body
        const result = await inst.bridge.query(what, args)
        return send(res, 200, { result })
      }
      const { command, ...args } = body
      inst.bridge.command(command, args)
      return send(res, 200, { ok: true })
    } catch (err) {
      return send(res, 502, { error: err.message })
    }
  }

  const botCommandMatch = pathname.match(/^\/api\/instances\/([^/]+)\/bot\/command$/)
  if (botCommandMatch && req.method === 'POST') {
    const inst = instances.find(i => i.id === botCommandMatch[1])
    if (!inst) return send(res, 404, { error: 'not found' })
    if (inst.kind !== 'bedrock' || !inst.proc) return send(res, 409, { error: 'instance not running' })
    const body = await readBody(req)
    inst.proc.send({ type: 'command', text: body.text })
    return send(res, 200, { ok: true })
  }

  const chatMatch = pathname.match(/^\/api\/instances\/([^/]+)\/chat$/)
  if (chatMatch && req.method === 'GET') {
    const inst = instances.find(i => i.id === chatMatch[1])
    if (!inst) return send(res, 404, { error: 'not found' })
    return send(res, 200, (inst.chatLog || []).map(publicChatEntry))
  }

  const chatAudioMatch = pathname.match(/^\/api\/instances\/([^/]+)\/chat\/([^/]+)\/audio$/)
  if (chatAudioMatch && req.method === 'GET') {
    const inst = instances.find(i => i.id === chatAudioMatch[1])
    if (!inst) return send(res, 404, { error: 'not found' })
    const entry = (inst.chatLog || []).find(e => e.id === chatAudioMatch[2])
    if (!entry || entry.audioStatus !== 'ready' || !entry.audioPath) return send(res, 404, { error: 'audio not available' })
    return fs.readFile(entry.audioPath, (err, data) => {
      if (err) return send(res, 404, { error: 'not found' })
      res.writeHead(200, { 'Content-Type': 'audio/wav' })
      res.end(data)
    })
  }

  const instMatch = pathname.match(/^\/api\/instances\/([^/]+)(\/(start|stop))?$/)
  if (instMatch) {
    const inst = instances.find(i => i.id === instMatch[1])
    if (!inst) return send(res, 404, { error: 'not found' })
    const action = instMatch[3]

    if (action === 'start' && req.method === 'POST') {
      startInstance(inst)
      saveInstances()
      return send(res, 200, publicInstance(inst))
    }
    if (action === 'stop' && req.method === 'POST') {
      stopInstance(inst)
      return send(res, 200, publicInstance(inst))
    }
    if (!action && req.method === 'PATCH') {
      const body = await readBody(req)
      if (typeof body.selected === 'boolean') inst.selected = body.selected
      saveInstances()
      return send(res, 200, publicInstance(inst))
    }
    if (!action && req.method === 'DELETE') {
      stopInstance(inst)
      instances = instances.filter(i => i.id !== inst.id)
      saveInstances()
      return send(res, 204, {})
    }
  }

  if (pathname === '/api/settings' && req.method === 'GET') return send(res, 200, settings)
  if (pathname === '/api/settings' && req.method === 'PUT') {
    const body = await readBody(req)
    settings = { ...settings, ...body }
    saveSettings()
    return send(res, 200, settings)
  }

  if (pathname === '/api/narration/voices' && req.method === 'GET') {
    return send(res, 200, voices.listVoices(settings.voicesDir))
  }

  const previewMatch = pathname.match(/^\/api\/narration\/voices\/([^/]+)\/preview$/)
  if (previewMatch && req.method === 'POST') {
    const voice = voices.findVoice(settings.voicesDir, previewMatch[1])
    if (!voice) return send(res, 404, { error: 'voice not found' })
    const outPath = path.join(PREVIEW_CACHE_DIR, previewMatch[1] + '.wav')
    try {
      if (!fs.existsSync(outPath)) {
        await piper.synthesize({
          binaryPath: settings.piperBinaryPath,
          modelPath: voice.onnxPath,
          text: 'Hello, this is a preview of my voice.',
          outWav: outPath
        })
      }
      const data = fs.readFileSync(outPath)
      res.writeHead(200, { 'Content-Type': 'audio/wav' })
      return res.end(data)
    } catch (err) {
      return send(res, 502, { error: err.message })
    }
  }

  if (pathname === '/api/narration/patterns' && req.method === 'GET') {
    return send(res, 200, narration.listPatterns())
  }
  if (pathname === '/api/narration/patterns' && req.method === 'POST') {
    const body = await readBody(req)
    return send(res, 201, narration.addPattern({ voiceId: body.voiceId, pattern: body.pattern }))
  }
  const patternMatch = pathname.match(/^\/api\/narration\/patterns\/([^/]+)$/)
  if (patternMatch && req.method === 'PUT') {
    const body = await readBody(req)
    const row = narration.updatePattern(patternMatch[1], { voiceId: body.voiceId, pattern: body.pattern })
    if (!row) return send(res, 404, { error: 'not found' })
    return send(res, 200, row)
  }
  if (patternMatch && req.method === 'DELETE') {
    narration.deletePattern(patternMatch[1])
    return send(res, 204, {})
  }

  if (pathname === '/api/narration/overrides' && req.method === 'GET') {
    return send(res, 200, narration.listOverrides())
  }
  const overrideMatch = pathname.match(/^\/api\/narration\/overrides\/([^/]+)$/)
  if (overrideMatch && req.method === 'PUT') {
    const body = await readBody(req)
    const row = narration.setOverride(decodeURIComponent(overrideMatch[1]), { voiceId: body.voiceId, muted: body.muted })
    return send(res, 200, row)
  }
  if (overrideMatch && req.method === 'DELETE') {
    narration.deleteOverride(decodeURIComponent(overrideMatch[1]))
    return send(res, 204, {})
  }

  if (pathname === '/api/narration/usernames' && req.method === 'GET') {
    return send(res, 200, [...observedUsernames])
  }

  let filePath = path.join(PUBLIC_DIR, pathname === '/' ? '/index.html' : pathname)
  if (!filePath.startsWith(PUBLIC_DIR)) return send(res, 403, { error: 'forbidden' })
  fs.readFile(filePath, (err, data) => {
    if (err) return send(res, 404, { error: 'not found' })
    res.writeHead(200, { 'Content-Type': MIME[path.extname(filePath)] || 'application/octet-stream' })
    res.end(data)
  })
}

// Real bug found while wiring up narration settings by hand: readBody's JSON.parse threw on a
// malformed body (a stray single backslash in a Windows path from a hand-typed curl call), and
// since that throw happened inside an 'end' event callback -- not synchronously inside any
// caller's own try/catch -- it was a genuinely uncaught exception that crashed the entire
// manager process, taking down every connected instance's bridge with it over one bad request.
// Every route handler above assumes readBody/JSON.parse might throw; this is the one place that
// actually has to catch it.
const server = http.createServer((req, res) => {
  handleRequest(req, res).catch(err => {
    console.error('[manager] request handler error:', err)
    if (!res.headersSent) send(res, 400, { error: err.message || 'bad request' })
  })
})

server.listen(PORT, () => console.log('[manager] listening on http://localhost:' + PORT))
