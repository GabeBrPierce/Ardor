const instanceToggle = document.getElementById('instanceToggle')
const instancePanel = document.getElementById('instancePanel')
const instanceSummary = document.getElementById('instanceSummary')
const instanceList = document.getElementById('instanceList')
const addInstanceForm = document.getElementById('addInstanceForm')

const settingsBtn = document.getElementById('settingsBtn')
const settingsPanel = document.getElementById('settingsPanel')
const closeSettingsBtn = document.getElementById('closeSettingsBtn')
const saveSettingsBtn = document.getElementById('saveSettingsBtn')

const tabBar = document.getElementById('tabBar')
const emptyState = document.getElementById('emptyState')
const mapPanel = document.getElementById('mapPanel')
const view3dPanel = document.getElementById('view3dPanel')
const menuMirrorPanel = document.getElementById('menuMirrorPanel')
const orchestratorPanel = document.getElementById('orchestratorPanel')
const narrationPanel = document.getElementById('narrationPanel')

// Exact order of ArdorConfigScreen's root menu buttons -- see CLAUDE.md's companion-parity rule.
const MENU_LABELS = [
  'Scripts', 'Script Events', 'Keybinds', 'Wheels', 'Lua Scripting Help',
  'Macros', 'Regions', 'Events', 'Task Planner', 'Fetch Items',
  'Resume Interrupted Work', 'Settings'
]

const TABS = [
  { id: 'map', label: 'Map', kind: 'map' },
  { id: '3d', label: '3D View', kind: '3d' },
  { id: 'orchestrator', label: 'Orchestrator', kind: 'orchestrator' },
  { id: 'narration', label: 'Narration', kind: 'narration' },
  ...MENU_LABELS.map(label => ({ id: 'menu:' + label, label, kind: 'menu', menuLabel: label }))
]

let latestInstances = []
let activeTabId = TABS[0].id
let lastTargetId = null

function faceUrl (username) {
  return `https://minotar.net/avatar/${encodeURIComponent(username)}/22.png`
}

function wait (ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

function topmostJavaInstance (instances) {
  return instances.find(i => i.kind === 'java' && i.selected) || null
}

function blockColor (id) {
  const name = (id || '').replace('minecraft:', '')
  if (name.includes('water')) return '#3d6fd6'
  if (name.includes('lava')) return '#d65a2d'
  if (name.includes('grass') || name.includes('leaves') || name.includes('foliage')) return '#4a8f3c'
  if (name.includes('sand')) return '#d8c877'
  if (name.includes('snow') || name.includes('ice')) return '#e8f0f5'
  if (name.includes('log') || name.includes('plank') || name.includes('dirt') || name.includes('wood')) return '#8a5a3c'
  if (name.includes('stone') || name.includes('cobble') || name.includes('ore') || name.includes('deepslate')) return '#8a8a8f'
  return '#6b6d78'
}

// -- Bridge proxy (browser -> manager -> Java client's BridgeServer) --

async function bridgeQuery (instanceId, what, args = {}) {
  const res = await fetch(`/api/instances/${instanceId}/bridge/query`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ what, ...args })
  })
  const body = await res.json()
  if (!res.ok) throw new Error(body.error || 'bridge query failed')
  return body.result
}

async function bridgeCommand (instanceId, command, args = {}) {
  const res = await fetch(`/api/instances/${instanceId}/bridge/command`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ command, ...args })
  })
  const body = await res.json()
  if (!res.ok) throw new Error(body.error || 'bridge command failed')
  return body
}

// -- Instance dropdown (Bedrock child processes + Java bridge connections) --

async function fetchInstances () {
  const res = await fetch('/api/instances')
  return res.json()
}

function renderInstances (instances) {
  const selectedCount = instances.filter(i => i.selected).length
  instanceSummary.textContent = instances.length === 0
    ? 'No instances'
    : `${selectedCount}/${instances.length} selected`

  instanceList.innerHTML = ''
  for (const inst of instances) {
    const row = document.createElement('div')
    row.className = 'instance-row'

    const checkbox = document.createElement('input')
    checkbox.type = 'checkbox'
    checkbox.checked = !!inst.selected
    checkbox.addEventListener('change', () => toggleSelected(inst.id, checkbox.checked))

    const img = document.createElement('img')
    img.src = faceUrl(inst.username)
    img.alt = inst.username

    const kindTag = document.createElement('span')
    kindTag.className = 'kind-tag'
    kindTag.textContent = inst.kind === 'java' ? 'JE' : 'BE'

    const dot = document.createElement('span')
    dot.className = 'status-dot ' + inst.status

    const name = document.createElement('span')
    name.className = 'name'
    name.textContent = inst.username

    const actionBtn = document.createElement('button')
    actionBtn.className = 'row-btn'
    const running = inst.status !== 'stopped'
    actionBtn.textContent = running ? 'Stop' : 'Start'
    actionBtn.addEventListener('click', () => (running ? stopInstance(inst.id) : startInstance(inst.id)))

    row.append(checkbox, img, kindTag, dot, name, actionBtn)
    instanceList.appendChild(row)
  }
}

async function refresh () {
  latestInstances = await fetchInstances()
  renderInstances(latestInstances)
  if (TABS.find(t => t.id === activeTabId).kind === 'orchestrator') renderOrchestratorCards()
  const target = topmostJavaInstance(latestInstances)
  const targetId = target ? target.id : null
  if (targetId !== lastTargetId) {
    lastTargetId = targetId
    renderActiveTab()
  }
}

async function toggleSelected (id, selected) {
  await fetch(`/api/instances/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ selected })
  })
  refresh()
}

async function startInstance (id) {
  await fetch(`/api/instances/${id}/start`, { method: 'POST' })
  refresh()
}

async function stopInstance (id) {
  await fetch(`/api/instances/${id}/stop`, { method: 'POST' })
  refresh()
}

instanceToggle.addEventListener('click', () => instancePanel.classList.toggle('hidden'))

document.addEventListener('click', (e) => {
  if (!instancePanel.contains(e.target) && e.target !== instanceToggle) {
    instancePanel.classList.add('hidden')
  }
})

addInstanceForm.addEventListener('submit', async (e) => {
  e.preventDefault()
  const form = new FormData(addInstanceForm)
  await fetch('/api/instances', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      kind: form.get('kind'),
      username: form.get('username'),
      host: form.get('host') || undefined,
      port: form.get('port') || undefined
    })
  })
  addInstanceForm.reset()
  refresh()
})

// -- App settings (Bedrock-only) --

async function openSettings () {
  const res = await fetch('/api/settings')
  const settings = await res.json()
  document.getElementById('setDefaultHost').value = settings.defaultHost
  document.getElementById('setDefaultPort').value = settings.defaultPort
  document.getElementById('setTickMs').value = settings.tickMs
  document.getElementById('setWalkSpeed').value = settings.walkSpeed
  document.getElementById('setPiperBinaryPath').value = settings.piperBinaryPath || ''
  document.getElementById('setVoicesDir').value = settings.voicesDir || ''
  settingsPanel.classList.remove('hidden')
}

settingsBtn.addEventListener('click', openSettings)
closeSettingsBtn.addEventListener('click', () => settingsPanel.classList.add('hidden'))

saveSettingsBtn.addEventListener('click', async () => {
  await fetch('/api/settings', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      defaultHost: document.getElementById('setDefaultHost').value,
      defaultPort: Number(document.getElementById('setDefaultPort').value),
      tickMs: Number(document.getElementById('setTickMs').value),
      walkSpeed: Number(document.getElementById('setWalkSpeed').value),
      piperBinaryPath: document.getElementById('setPiperBinaryPath').value,
      voicesDir: document.getElementById('setVoicesDir').value
    })
  })
  settingsPanel.classList.add('hidden')
})

// -- Tabs --

for (const tab of TABS) {
  const btn = document.createElement('button')
  btn.className = 'tab-btn'
  btn.textContent = tab.label
  btn.dataset.tabId = tab.id
  btn.addEventListener('click', () => setActiveTab(tab.id))
  tabBar.appendChild(btn)
}

function setActiveTab (id) {
  activeTabId = id
  for (const btn of tabBar.children) btn.classList.toggle('active', btn.dataset.tabId === id)
  stopMapLoop()
  stop3dLoop()
  stopNarrationChatLoop()
  renderActiveTab()
}

function showPanel (panel) {
  emptyState.classList.add('hidden')
  mapPanel.classList.add('hidden')
  view3dPanel.classList.add('hidden')
  menuMirrorPanel.classList.add('hidden')
  orchestratorPanel.classList.add('hidden')
  narrationPanel.classList.add('hidden')
  panel.classList.remove('hidden')
}

function renderActiveTab () {
  const tab = TABS.find(t => t.id === activeTabId)

  stopMapLoop()
  stop3dLoop()
  stopNarrationChatLoop()

  if (tab.kind === 'orchestrator') {
    showPanel(orchestratorPanel)
    renderOrchestratorCards()
    return
  }

  if (tab.kind === 'narration') {
    showPanel(narrationPanel)
    renderNarrationTab()
    return
  }

  const target = topmostJavaInstance(latestInstances)
  if (!target) {
    showPanel(emptyState)
    return
  }
  if (tab.kind === 'map') {
    showPanel(mapPanel)
    startMapLoop(target)
  } else if (tab.kind === '3d') {
    showPanel(view3dPanel)
    start3dLoop(target)
  } else {
    showPanel(menuMirrorPanel)
    startMenuMirror(target, tab.menuLabel)
  }
}

// -- Map tab: our own top-down render from world.chunkHeightmap/player.state, not Xaero's cache --

let mapTimer = null
let mapChunkCache = new Map()

function stopMapLoop () {
  clearInterval(mapTimer)
  mapTimer = null
  mapChunkCache = new Map()
}

function startMapLoop (target) {
  const tick = async () => {
    try {
      await drawMap(target)
    } catch (err) {
      drawMapError(err.message)
    }
  }
  tick()
  mapTimer = setInterval(tick, 3000)
}

async function drawMap (target) {
  const others = latestInstances.filter(i => i.kind === 'java' && i.selected && i.id !== target.id)
  const state = await bridgeQuery(target.id, 'player.state')
  const otherStates = await Promise.all(others.map(async (i) => {
    try {
      return { username: i.username, state: await bridgeQuery(i.id, 'player.state') }
    } catch {
      return null
    }
  }))

  const centerChunkX = Math.floor(state.x) >> 4
  const centerChunkZ = Math.floor(state.z) >> 4
  const columns = []
  for (let dx = -1; dx <= 1; dx++) {
    for (let dz = -1; dz <= 1; dz++) {
      const key = (centerChunkX + dx) + ',' + (centerChunkZ + dz)
      let data = mapChunkCache.get(key)
      if (!data) {
        data = await bridgeQuery(target.id, 'world.chunkHeightmap', { x: centerChunkX + dx, z: centerChunkZ + dz })
        mapChunkCache.set(key, data)
      }
      columns.push(...data.columns)
    }
  }

  const canvas = document.getElementById('mapCanvas')
  const ctx = canvas.getContext('2d')
  ctx.fillStyle = '#101114'
  ctx.fillRect(0, 0, canvas.width, canvas.height)

  const scale = 6
  const originX = state.x - canvas.width / (2 * scale)
  const originZ = state.z - canvas.height / (2 * scale)

  for (const col of columns) {
    const px = (col.x - originX) * scale
    const pz = (col.z - originZ) * scale
    if (px < -scale || pz < -scale || px >= canvas.width || pz >= canvas.height) continue
    ctx.fillStyle = blockColor(col.block)
    ctx.fillRect(px, pz, scale, scale)
  }

  drawPlayerDot(ctx, state.x, state.z, originX, originZ, scale, target.username, '#ffffff')
  for (const o of otherStates) {
    if (!o) continue
    drawPlayerDot(ctx, o.state.x, o.state.z, originX, originZ, scale, o.username, '#ffd23f')
  }
}

function drawPlayerDot (ctx, x, z, originX, originZ, scale, label, color) {
  const px = (x - originX) * scale
  const pz = (z - originZ) * scale
  ctx.fillStyle = color
  ctx.beginPath()
  ctx.arc(px, pz, 4, 0, Math.PI * 2)
  ctx.fill()
  ctx.fillStyle = '#e6e6e6'
  ctx.font = '11px sans-serif'
  ctx.fillText(label, px + 6, pz - 6)
}

function drawMapError (message) {
  const canvas = document.getElementById('mapCanvas')
  const ctx = canvas.getContext('2d')
  ctx.fillStyle = '#101114'
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.fillStyle = '#d64545'
  ctx.font = '13px sans-serif'
  ctx.fillText(message, 10, 20)
}

// -- 3D View tab: flat-colored voxel cube of world.blockRegion around the player --

let renderer3d = null
let scene3d = null
let camera3d = null
let controls3d = null
let mesh3d = null
let animId3d = null
let view3dTimer = null

function stop3dLoop () {
  clearInterval(view3dTimer)
  view3dTimer = null
  if (animId3d) cancelAnimationFrame(animId3d)
  animId3d = null
  document.getElementById('view3dMount').innerHTML = ''
  renderer3d = null
}

function ensure3dScene () {
  if (renderer3d) return
  const mount = document.getElementById('view3dMount')
  const width = mount.clientWidth || 640
  const height = 480

  scene3d = new THREE.Scene()
  scene3d.background = new THREE.Color(0x1e1f24)
  camera3d = new THREE.PerspectiveCamera(60, width / height, 0.1, 200)
  camera3d.position.set(20, 20, 20)

  renderer3d = new THREE.WebGLRenderer({ antialias: true })
  renderer3d.setSize(width, height)
  mount.appendChild(renderer3d.domElement)

  controls3d = new THREE.OrbitControls(camera3d, renderer3d.domElement)
  scene3d.add(new THREE.AmbientLight(0xffffff, 0.9))
  const dl = new THREE.DirectionalLight(0xffffff, 0.4)
  dl.position.set(1, 1, 0.5)
  scene3d.add(dl)

  const animate = () => {
    animId3d = requestAnimationFrame(animate)
    controls3d.update()
    renderer3d.render(scene3d, camera3d)
  }
  animate()
}

function start3dLoop (target) {
  ensure3dScene()
  const tick = async () => {
    try {
      await refresh3dBlocks(target)
    } catch (err) {
      console.error('[3d view]', err.message)
    }
  }
  tick()
  view3dTimer = setInterval(tick, 5000)
}

async function refresh3dBlocks (target) {
  const state = await bridgeQuery(target.id, 'player.state')
  const cx = Math.floor(state.x)
  const cy = Math.floor(state.y)
  const cz = Math.floor(state.z)
  const { blocks } = await bridgeQuery(target.id, 'world.blockRegion', {
    min: { x: cx - 8, y: cy - 8, z: cz - 8 },
    max: { x: cx + 7, y: cy + 7, z: cz + 7 }
  })

  if (mesh3d) {
    scene3d.remove(mesh3d)
    mesh3d.geometry.dispose()
    mesh3d.material.dispose()
    mesh3d = null
  }

  const solid = blocks.filter(b => !b.air)
  const geometry = new THREE.BoxGeometry(1, 1, 1)
  const material = new THREE.MeshLambertMaterial()
  mesh3d = new THREE.InstancedMesh(geometry, material, Math.max(solid.length, 1))
  const dummy = new THREE.Object3D()
  const color = new THREE.Color()
  solid.forEach((b, i) => {
    dummy.position.set(b.x - cx, b.y - cy, b.z - cz)
    dummy.updateMatrix()
    mesh3d.setMatrixAt(i, dummy.matrix)
    color.set(blockColor(b.id))
    mesh3d.setColorAt(i, color)
  })
  mesh3d.count = solid.length
  scene3d.add(mesh3d)
}

// -- Menu-mirror tabs: one generic renderer drives all 14 ArdorConfigScreen root screens via the
// existing ui.openMenu/ui.list/ui.select bridge reflection, instead of recreating each screen --

async function startMenuMirror (target, rootLabel) {
  const titleEl = document.getElementById('menuMirrorTitle')
  const optionsEl = document.getElementById('menuMirrorOptions')
  titleEl.textContent = 'Opening ' + rootLabel + '...'
  optionsEl.innerHTML = ''
  try {
    await bridgeCommand(target.id, 'ui.openMenu')
    await wait(200)
    let list = await bridgeQuery(target.id, 'ui.list')
    const rootOption = (list.options || []).find(o => o.label === rootLabel)
    if (rootOption) {
      await bridgeCommand(target.id, 'ui.select', { index: rootOption.index })
      await wait(200)
      list = await bridgeQuery(target.id, 'ui.list')
    }
    renderMenuList(target, list)
  } catch (err) {
    titleEl.textContent = rootLabel
    optionsEl.textContent = 'Error: ' + err.message
  }
}

function renderMenuList (target, list) {
  const titleEl = document.getElementById('menuMirrorTitle')
  const optionsEl = document.getElementById('menuMirrorOptions')
  titleEl.textContent = list.open ? list.title : '(no screen open)'
  optionsEl.innerHTML = ''
  for (const opt of list.options || []) {
    const btn = document.createElement('button')
    btn.className = 'menu-mirror-btn'
    btn.textContent = opt.label || '(unlabeled)'
    btn.addEventListener('click', async () => {
      try {
        await bridgeCommand(target.id, 'ui.select', { index: opt.index })
        await wait(250)
        renderMenuList(target, await bridgeQuery(target.id, 'ui.list'))
      } catch (err) {
        optionsEl.textContent = 'Error: ' + err.message
      }
    })
    optionsEl.appendChild(btn)
  }
}

// -- Orchestrator tab: companion side of the in-game freecam orchestrator overlay (see TODO.md's
// 2026-09-24 entry). Click cards to multi-select, Send fans one command out to all of them in
// parallel -- Java instances via the bridge's action.execute, Bedrock instances via bot.js's own
// small say/walk/stop vocabulary. No Task Picker here yet (real gap, see TODO.md), free text only.

const orchestratorSelected = new Set()

function renderOrchestratorCards () {
  const cardsEl = document.getElementById('orchestratorCards')
  const ids = new Set(latestInstances.map(i => i.id))
  for (const id of [...orchestratorSelected]) if (!ids.has(id)) orchestratorSelected.delete(id)

  cardsEl.innerHTML = ''
  for (const inst of latestInstances) {
    const card = document.createElement('div')
    card.className = 'orchestrator-card' + (orchestratorSelected.has(inst.id) ? ' selected' : '')

    const dot = document.createElement('span')
    dot.className = 'status-dot ' + inst.status

    const kindTag = document.createElement('span')
    kindTag.className = 'kind-tag'
    kindTag.textContent = inst.kind === 'java' ? 'JE' : 'BE'

    const name = document.createElement('span')
    name.className = 'name'
    name.textContent = inst.username

    card.append(dot, kindTag, name)
    card.addEventListener('click', () => {
      if (orchestratorSelected.has(inst.id)) orchestratorSelected.delete(inst.id)
      else orchestratorSelected.add(inst.id)
      renderOrchestratorCards()
    })
    cardsEl.appendChild(card)
  }
}

async function sendOrchestratorCommand (text) {
  const resultEl = document.getElementById('orchestratorResult')
  const targets = latestInstances.filter(i => orchestratorSelected.has(i.id))
  if (targets.length === 0) {
    resultEl.textContent = 'Select at least one instance.'
    return
  }

  resultEl.textContent = 'Sending...'
  const results = await Promise.all(targets.map(async (inst) => {
    try {
      const res = inst.kind === 'java'
        ? await fetch(`/api/instances/${inst.id}/bridge/command`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ command: 'action.execute', text })
        })
        : await fetch(`/api/instances/${inst.id}/bot/command`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ text })
        })
      if (!res.ok) {
        const body = await res.json().catch(() => ({}))
        return { username: inst.username, ok: false, error: body.error || res.status }
      }
      return { username: inst.username, ok: true }
    } catch (err) {
      return { username: inst.username, ok: false, error: err.message }
    }
  }))

  const failed = results.filter(r => !r.ok)
  resultEl.textContent = failed.length === 0
    ? `Sent to ${results.length}/${results.length}.`
    : `Sent to ${results.length - failed.length}/${results.length}. Failed: ` +
      failed.map(f => f.username + ' (' + f.error + ')').join(', ')
}

document.getElementById('orchestratorForm').addEventListener('submit', (e) => {
  e.preventDefault()
  const input = document.getElementById('orchestratorInput')
  const text = input.value.trim()
  if (!text) return
  sendOrchestratorCommand(text)
})

// -- Narration tab: four sub-tabs backed by manager.js's /api/narration/* and
// /api/instances/:id/chat* routes. No websocket to the browser (same polling convention as the
// Map/3D/Orchestrator tabs above) -- the Chat sub-tab just re-fetches the target instance's chat
// log on a timer and lets audioStatus catch up from 'pending' to 'ready' across polls.

let narrationSubtab = 'patterns'
let narrationVoicesCache = []
let narrationChatTimer = null

function stopNarrationChatLoop () {
  clearInterval(narrationChatTimer)
  narrationChatTimer = null
}

for (const btn of document.querySelectorAll('#narrationSubTabs .subtab-btn')) {
  btn.addEventListener('click', () => setNarrationSubtab(btn.dataset.sub))
}

function setNarrationSubtab (sub) {
  narrationSubtab = sub
  for (const btn of document.querySelectorAll('#narrationSubTabs .subtab-btn')) {
    btn.classList.toggle('active', btn.dataset.sub === sub)
  }
  document.getElementById('narrationPatternsSub').classList.toggle('hidden', sub !== 'patterns')
  document.getElementById('narrationOverridesSub').classList.toggle('hidden', sub !== 'overrides')
  document.getElementById('narrationVoicesSub').classList.toggle('hidden', sub !== 'voices')
  document.getElementById('narrationChatSub').classList.toggle('hidden', sub !== 'chat')

  stopNarrationChatLoop()
  if (sub === 'patterns') renderPatternsTab()
  else if (sub === 'overrides') renderOverridesTab()
  else if (sub === 'voices') renderVoiceManagerTab()
  else if (sub === 'chat') startNarrationChatLoop()
}

async function renderNarrationTab () {
  narrationVoicesCache = await fetch('/api/narration/voices').then(r => r.json())
  setNarrationSubtab(narrationSubtab)
}

function voiceOptionsHtml (selectedId) {
  if (narrationVoicesCache.length === 0) return '<option value="">(no voices installed)</option>'
  return narrationVoicesCache.map(v =>
    `<option value="${v.id}"${v.id === selectedId ? ' selected' : ''}>${v.name}</option>`
  ).join('')
}

// -- Pattern to Voice --

async function renderPatternsTab () {
  const patterns = await fetch('/api/narration/patterns').then(r => r.json())
  const body = document.getElementById('patternsTableBody')
  body.innerHTML = ''
  for (const p of patterns) {
    const tr = document.createElement('tr')

    const voiceTd = document.createElement('td')
    const voiceSel = document.createElement('select')
    voiceSel.innerHTML = voiceOptionsHtml(p.voiceId)
    voiceTd.appendChild(voiceSel)

    const patternTd = document.createElement('td')
    const patternInput = document.createElement('input')
    patternInput.type = 'text'
    patternInput.value = p.pattern
    patternInput.placeholder = 'regex, e.g. ^Steve.*'
    patternTd.appendChild(patternInput)

    const save = () => fetch(`/api/narration/patterns/${p.id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ voiceId: voiceSel.value, pattern: patternInput.value })
    })
    voiceSel.addEventListener('change', save)
    patternInput.addEventListener('change', save)

    const actionTd = document.createElement('td')
    const delBtn = document.createElement('button')
    delBtn.className = 'row-btn'
    delBtn.type = 'button'
    delBtn.textContent = 'Remove'
    delBtn.addEventListener('click', async () => {
      await fetch(`/api/narration/patterns/${p.id}`, { method: 'DELETE' })
      renderPatternsTab()
    })
    actionTd.appendChild(delBtn)

    tr.append(voiceTd, patternTd, actionTd)
    body.appendChild(tr)
  }
}

document.getElementById('addPatternBtn').addEventListener('click', async () => {
  const voiceId = narrationVoicesCache[0] ? narrationVoicesCache[0].id : ''
  await fetch('/api/narration/patterns', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ voiceId, pattern: '' })
  })
  renderPatternsTab()
})

// -- Username to Voice --

async function renderOverridesTab () {
  const overrides = await fetch('/api/narration/overrides').then(r => r.json())
  const body = document.getElementById('overridesTableBody')
  body.innerHTML = ''
  for (const o of overrides) {
    const tr = document.createElement('tr')

    const userTd = document.createElement('td')
    userTd.textContent = o.username

    const voiceTd = document.createElement('td')
    const voiceSel = document.createElement('select')
    voiceSel.innerHTML = voiceOptionsHtml(o.voiceId)
    voiceSel.disabled = !!o.muted
    voiceSel.addEventListener('change', () => saveOverride(o.username, { voiceId: voiceSel.value, muted: o.muted }))
    voiceTd.appendChild(voiceSel)

    const muteTd = document.createElement('td')
    const muteBtn = document.createElement('button')
    muteBtn.className = 'row-btn'
    muteBtn.type = 'button'
    muteBtn.textContent = o.muted ? 'Unmute' : 'Mute'
    muteBtn.addEventListener('click', () => saveOverride(o.username, { voiceId: o.voiceId, muted: !o.muted }))
    muteTd.appendChild(muteBtn)

    const actionTd = document.createElement('td')
    const delBtn = document.createElement('button')
    delBtn.className = 'row-btn'
    delBtn.type = 'button'
    delBtn.textContent = 'Remove'
    delBtn.addEventListener('click', async () => {
      await fetch(`/api/narration/overrides/${encodeURIComponent(o.username)}`, { method: 'DELETE' })
      renderOverridesTab()
    })
    actionTd.appendChild(delBtn)

    tr.append(userTd, voiceTd, muteTd, actionTd)
    body.appendChild(tr)
  }
}

async function saveOverride (username, data) {
  await fetch(`/api/narration/overrides/${encodeURIComponent(username)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data)
  })
  renderOverridesTab()
}

document.getElementById('addOverrideBtn').addEventListener('click', async () => {
  const [usernames, overrides] = await Promise.all([
    fetch('/api/narration/usernames').then(r => r.json()),
    fetch('/api/narration/overrides').then(r => r.json())
  ])
  const existing = new Set(overrides.map(o => o.username))
  const available = usernames.filter(u => !existing.has(u))

  const userSel = document.getElementById('addOverrideUsername')
  userSel.innerHTML = available.length
    ? available.map(u => `<option value="${u}">${u}</option>`).join('')
    : '<option value="">(no usernames observed yet)</option>'
  document.getElementById('addOverrideVoice').innerHTML = voiceOptionsHtml()
  document.getElementById('addOverrideRow').classList.remove('hidden')
})

document.getElementById('cancelAddOverrideBtn').addEventListener('click', () => {
  document.getElementById('addOverrideRow').classList.add('hidden')
})

document.getElementById('confirmAddOverrideBtn').addEventListener('click', async () => {
  const username = document.getElementById('addOverrideUsername').value
  const voiceId = document.getElementById('addOverrideVoice').value
  if (!username) return
  await saveOverride(username, { voiceId, muted: false })
  document.getElementById('addOverrideRow').classList.add('hidden')
})

// -- Voice manager --

async function renderVoiceManagerTab () {
  const settingsData = await fetch('/api/settings').then(r => r.json())

  const ardorSel = document.getElementById('ardorVoiceSelect')
  ardorSel.innerHTML = voiceOptionsHtml(settingsData.ardorVoiceId)
  ardorSel.onchange = () => fetch('/api/settings', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ardorVoiceId: ardorSel.value })
  }).then(renderVoiceManagerTab)

  const defaultSel = document.getElementById('defaultVoiceSelect')
  defaultSel.innerHTML = '<option value="">(don\'t narrate)</option>' + voiceOptionsHtml(settingsData.defaultVoiceId)
  defaultSel.onchange = () => fetch('/api/settings', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ defaultVoiceId: defaultSel.value })
  }).then(renderVoiceManagerTab)

  const list = document.getElementById('voiceManagerList')
  list.innerHTML = ''
  if (narrationVoicesCache.length === 0) {
    list.textContent = 'No voices installed. Run bedrock-bot/scripts/install-voices.ps1, or drop .onnx + .onnx.json pairs into voices/custom/.'
  }
  for (const v of narrationVoicesCache) {
    const row = document.createElement('div')
    row.className = 'voice-manager-row'

    const name = document.createElement('span')
    name.className = 'voice-name'
    name.textContent = v.name

    const tag = document.createElement('span')
    tag.className = 'voice-tag' + (v.id === settingsData.ardorVoiceId ? ' ardor' : '')
    tag.textContent = v.id === settingsData.ardorVoiceId ? 'Ardor' : 'Player voice'

    const genderTag = document.createElement('span')
    genderTag.className = 'voice-tag'
    genderTag.textContent = v.gender

    const playBtn = document.createElement('button')
    playBtn.className = 'row-btn'
    playBtn.type = 'button'
    playBtn.textContent = 'Preview'
    playBtn.addEventListener('click', async () => {
      playBtn.disabled = true
      playBtn.textContent = 'Synthesizing...'
      try {
        const res = await fetch(`/api/narration/voices/${v.id}/preview`, { method: 'POST' })
        if (!res.ok) throw new Error((await res.json().catch(() => ({}))).error || 'preview failed')
        const blob = await res.blob()
        new Audio(URL.createObjectURL(blob)).play()
      } catch (err) {
        alert('Preview failed: ' + err.message)
      } finally {
        playBtn.disabled = false
        playBtn.textContent = 'Preview'
      }
    })

    row.append(name, tag, genderTag, playBtn)
    list.appendChild(row)
  }
}

// -- Chat --

function startNarrationChatLoop () {
  const tick = async () => {
    const logEl = document.getElementById('narrationChatLog')
    const target = topmostJavaInstance(latestInstances)
    if (!target) {
      logEl.textContent = 'Select a Java instance in the dropdown above.'
      return
    }
    try {
      const entries = await fetch(`/api/instances/${target.id}/chat`).then(r => r.json())
      renderNarrationChat(entries, target.id)
    } catch (err) {
      logEl.textContent = 'Error: ' + err.message
    }
  }
  tick()
  narrationChatTimer = setInterval(tick, 2000)
}

function renderNarrationChat (entries, instanceId) {
  const logEl = document.getElementById('narrationChatLog')
  logEl.innerHTML = ''
  if (entries.length === 0) {
    logEl.textContent = 'No chat seen yet.'
    return
  }
  for (const e of entries.slice().reverse()) {
    const row = document.createElement('div')
    row.className = 'narration-chat-row' + (e.kind === 'ardor' ? ' ardor' : '')

    const sender = document.createElement('span')
    sender.className = 'chat-sender'
    sender.textContent = e.sender

    const text = document.createElement('span')
    text.className = 'chat-text'
    text.textContent = e.text

    const voice = document.createElement('span')
    voice.className = 'chat-voice'
    voice.textContent = e.muted
      ? '(muted)'
      : e.voiceName || (e.audioStatus === 'error' ? '(error: ' + e.audioError + ')' : '(not narrated)')

    const playBtn = document.createElement('button')
    playBtn.className = 'row-btn'
    playBtn.type = 'button'
    playBtn.textContent = e.audioStatus === 'pending' ? 'Synthesizing...' : 'Play'
    playBtn.disabled = e.audioStatus !== 'ready'
    playBtn.addEventListener('click', () => {
      const player = document.getElementById('narrationAudioPlayer')
      player.src = `/api/instances/${instanceId}/chat/${e.id}/audio`
      player.play()
    })

    row.append(sender, text, voice, playBtn)
    logEl.appendChild(row)
  }
}

setActiveTab(TABS[0].id)
refresh()
setInterval(refresh, 2000)
