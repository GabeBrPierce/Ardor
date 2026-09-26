const bedrock = require('bedrock-protocol')

const HOST = process.env.HOST || 'localhost'
const PORT = Number(process.env.PORT) || 19132
const USERNAME = process.env.USERNAME || 'ArdorBot'
const OFFLINE = process.env.OFFLINE !== 'false'
const TICK_MS = Number(process.env.TICK_MS) || 100
const WALK_SPEED = Number(process.env.WALK_SPEED) || 0.15 // blocks per tick

function report (status) {
  if (process.send) process.send({ type: 'status', status })
}

const client = bedrock.createClient({ host: HOST, port: PORT, username: USERNAME, offline: OFFLINE })

// Client-authoritative movement state, reported to the server every player_auth_input packet.
const state = { x: 0, y: 0, z: 0, pitch: 0, yaw: 0, tick: 0, walking: false }

client.on('connect', () => { console.log('[bot] connected to', HOST + ':' + PORT); report('connecting') })
client.on('login', () => { console.log('[bot] authenticated'); report('authenticated') })
client.on('join', () => { console.log('[bot] handshake complete, waiting to spawn'); report('joining') })
client.on('kick', (reason) => { console.log('[bot] kicked:', reason); report('kicked') })
client.on('close', () => { console.log('[bot] connection closed'); clearInterval(inputLoop); report('stopped') })
client.on('error', (err) => { console.error('[bot] error:', err); report('error') })

client.on('start_game', (packet) => {
  state.x = packet.player_position.x
  state.y = packet.player_position.y
  state.z = packet.player_position.z
  state.pitch = packet.rotation.x
  state.yaw = packet.rotation.z
  console.log('[bot] start_game, spawning at', state.x.toFixed(1), state.y.toFixed(1), state.z.toFixed(1))
})

client.on('text', (packet) => {
  if (packet.source_name === USERNAME) return
  console.log('[chat]', packet.source_name + ':', packet.message)
})

let inputLoop
client.on('spawn', () => {
  // 1.26.40+ rewrote player_auth_input's optional fields (transaction/item_stack_request/etc) to a
  // presence-bool + option pair instead of switching on input_data flags. That newer encoding is
  // rejected by the real server as malformed (see TODO.md) even though it round-trips locally, so
  // movement is only attempted against versions before the rewrite.
  if (client.versionGreaterThanOrEqualTo('1.26.40')) {
    console.log('[bot] spawned, but protocol', client.options.version, 'uses the broken player_auth_input encoding (see TODO.md) - not sending movement')
    report('spawned-no-movement')
    return
  }
  console.log('[bot] spawned, walking forward')
  state.walking = true
  report('spawned')
  inputLoop = setInterval(sendInput, TICK_MS)
})

function sendInput () {
  const rad = state.yaw * Math.PI / 180
  const dx = state.walking ? -Math.sin(rad) * WALK_SPEED : 0
  const dz = state.walking ? Math.cos(rad) * WALK_SPEED : 0
  state.x += dx
  state.z += dz
  state.tick++

  client.queue('player_auth_input', {
    pitch: state.pitch,
    yaw: state.yaw,
    position: { x: state.x, y: state.y, z: state.z },
    move_vector: { x: 0, z: state.walking ? 1 : 0 },
    head_yaw: state.yaw,
    input_data: { up: state.walking },
    input_mode: 'mouse',
    play_mode: 'normal',
    interaction_model: 'crosshair',
    interact_rotation: { x: 0, z: 0 },
    tick: state.tick,
    delta: { x: dx, y: 0, z: dz },
    analogue_move_vector: { x: 0, z: state.walking ? 1 : 0 },
    camera_orientation: { x: 0, y: 0, z: 0 },
    raw_move_vector: { x: 0, z: state.walking ? 1 : 0 }
  })

  if (state.tick % 20 === 0) {
    console.log('[bot] pos', state.x.toFixed(1), state.y.toFixed(1), state.z.toFixed(1))
  }
}

process.on('SIGINT', () => {
  clearInterval(inputLoop)
  client.close()
  process.exit(0)
})

// Orchestrator command surface (see bedrock-bot's manager.js /bot/command route). Small deliberate
// subset of the Java mod's ActionDispatcher ascii grammar, NOT a port of it -- only say/walk/stop.
process.on('message', (msg) => {
  if (msg && msg.type === 'command') handleCommand(msg.text)
})

function handleCommand (text) {
  const trimmed = (text || '').trim()
  const verb = trimmed.split(/\s+/)[0]
  const arg = trimmed.slice(verb.length).trim()
  if (verb === 'say') {
    // Field shape confirmed against bedrock-protocol's own README/API.md client examples, not guessed.
    client.queue('text', {
      type: 'chat',
      needs_translation: false,
      source_name: USERNAME,
      xuid: '',
      platform_chat_id: '',
      filtered_message: '',
      message: arg
    })
  } else if (verb === 'walk') {
    state.walking = true
  } else if (verb === 'stop') {
    state.walking = false
  } else {
    console.log('[bot] unknown orchestrator command:', trimmed)
  }
}
