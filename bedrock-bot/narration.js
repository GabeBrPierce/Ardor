// Voice-resolution rules for chat narration: pattern-to-voice regex rules and per-username
// overrides (with mute), both persisted as plain JSON under config/ -- same convention as
// instances.json/settings.json. ardor.message events never go through this; the caller
// (manager.js) always uses settings.ardorVoiceId for those.
const fs = require('fs')
const path = require('path')
const { randomUUID } = require('crypto')
const voices = require('./voices')

const CONFIG_DIR = path.join(__dirname, 'config')
const PATTERNS_FILE = path.join(CONFIG_DIR, 'narration-patterns.json')
const OVERRIDES_FILE = path.join(CONFIG_DIR, 'narration-overrides.json')

fs.mkdirSync(CONFIG_DIR, { recursive: true })
if (!fs.existsSync(PATTERNS_FILE)) fs.writeFileSync(PATTERNS_FILE, '[]')
if (!fs.existsSync(OVERRIDES_FILE)) fs.writeFileSync(OVERRIDES_FILE, '[]')

let patterns = JSON.parse(fs.readFileSync(PATTERNS_FILE, 'utf8'))
let overrides = JSON.parse(fs.readFileSync(OVERRIDES_FILE, 'utf8'))

function savePatterns () {
  fs.writeFileSync(PATTERNS_FILE, JSON.stringify(patterns, null, 2))
}
function saveOverrides () {
  fs.writeFileSync(OVERRIDES_FILE, JSON.stringify(overrides, null, 2))
}

// Deterministic string hash (same input always yields the same output) -- used to pick a
// consistent voice when a username matches more than one pattern rule, instead of picking
// randomly on every message.
function hashString (str) {
  let h = 0
  for (let i = 0; i < str.length; i++) {
    h = (h * 31 + str.charCodeAt(i)) | 0
  }
  return Math.abs(h)
}

// -- Gender-inference fallback (tier 4, below) --
//
// Only runs when the username matched no user-defined pattern rule at all, so an unmapped
// player still gets a plausibly-gendered voice instead of always landing on one arbitrary
// default. This is a crude suffix heuristic, not remotely a real name-gender classifier -- it
// will misfire on plenty of real usernames, and tier 1 is deliberately not exhaustive (endings
// like e/h/l/n/r/t/u/y/z match neither list), so plenty of usernames fall through it with no
// opinion at all. That's expected, not a bug.
//
// Algorithm: strip digits, split on camelCase boundaries, lowercase each piece, and also keep
// the whole sanitized string as one more candidate (e.g. "LostSinceSpawn123" ->
// ["lost", "since", "spawn", "lostsincespawn"]). Check every candidate's suffix against the
// length-4 regex tier first; only drop to length-3 if nothing at length-4 matched any
// candidate, then length-2, then length-1.
const GENDER_TIERS = [
  {
    female: /(?:mary|anna|icia|elle|beth|inda|ndra|ifer|rine|bara|anne|anda|line|aret|leen|erly|ella|ette|anie|issa|usan|lisa|arah|tina|sica|elen|aren|elyn|ancy|etty|iana|onna|ison|rley|oria|lene|ther|hley|arol|tine|girl)$/,
    male: /(?:bert|ames|john|hael|liam|avid|nald|hard|seph|rles|omas|niel|pher|than|ward|thew|stin|rian|orge|arry|rick|hony|paul|mark|evin|drew|even|neth|ryan|ason|shua|frey|olas|ndon|rank|gary|mond|eric|phen|acob)$/
  },
  {
    female: /(?:ine|nda|nna|nie|ara|lyn|ina|lle|cia|ica|rie|nne|rah|ana|ice|ssa|dra|ria|ela|tha|fer|een|tte|lla|rly|ret|lia|isa|san|tty|sie|ita|hia|ncy|nia|ann|ily|sha|ise|hel)$/,
    male: /(?:ert|mes|ohn|ard|ael|iam|vid|ald|rry|iel|eph|vin|les|mas|tin|han|ter|ony|las|ton|hew|ick|rge|aul|ell|ven|ark|don|yan|rew|uel|hua|den|arl|ory|lan|ric|hen|ank|ond|boy)$/
  },
  {
    female: /(?:ie|ne|na|ia|ra|la|da|sa|ly|yn|ca|ha|ta|et|te|cy|ma|ty|ee|ri|ya|ol|ea|va|di|ka|sy|mi|ys|gy|ae|ki|ti|ye|jo|za|ci|he|ja|xa)$/,
    male: /(?:el|es|in|rt|rd|am|hn|as|ld|id|ph|ew|ck|ge|tt|ul|rk|nd|ua|ic|rl|us|oy|ob|nk|nt|io|do|ar|ip|ur|ad|st|os|ro|to|yd|ex|ig|eb)$/
  },
  {
    female: /(?:a|i)$/,
    male: /(?:s|d|m|k|o|w|c|b|p|g|x|f|v|j|q)$/
  }
]

function candidatesFor (username) {
  const noDigits = username.replace(/[0-9]/g, '')
  const parts = noDigits.split(/(?=[A-Z])/).filter(Boolean).map(p => p.toLowerCase())
  return [...parts, noDigits.toLowerCase()]
}

function inferGenderFromUsername (username) {
  const candidates = candidatesFor(username)
  for (const tier of GENDER_TIERS) {
    for (const candidate of candidates) {
      if (tier.female.test(candidate)) return 'female'
      if (tier.male.test(candidate)) return 'male'
    }
  }
  return null
}

// -- Skin-model fallback (tier 5) --
//
// Java-only: Mojang's session-server profile carries a "slim" (Alex) vs default/absent
// ("classic", Steve) model in its textures property. Read as slim=female, classic=male, per the
// ask -- a real signal, but still just a costume choice, not a claim about the player. Bedrock
// player profiles have no equivalent model field, so bedrock instances skip straight past this
// tier (see resolveVoice below) rather than faking a lookup.
//
// Network dependency: two calls to Mojang's public APIs (api.mojang.com, sessionserver.mojang.com)
// per never-before-seen username. Cached in-memory per username after the first lookup. Offline/
// cracked usernames (this project's own default bedrock-bot test instance is offline-mode) won't
// resolve on Mojang's API at all -- that's expected, not an error -- and just fall through to
// whatever default voice is configured.
const skinGenderCache = new Map()

async function lookupSkinGender (username) {
  if (skinGenderCache.has(username)) return skinGenderCache.get(username)
  let gender = null
  try {
    const profileRes = await fetch(`https://api.mojang.com/users/profiles/minecraft/${encodeURIComponent(username)}`)
    if (profileRes.ok) {
      const profile = await profileRes.json()
      if (profile && profile.id) {
        const sessionRes = await fetch(`https://sessionserver.mojang.com/session/minecraft/profile/${profile.id}`)
        if (sessionRes.ok) {
          const session = await sessionRes.json()
          const texturesProp = (session.properties || []).find(p => p.name === 'textures')
          if (texturesProp) {
            const decoded = JSON.parse(Buffer.from(texturesProp.value, 'base64').toString('utf8'))
            const model = decoded && decoded.textures && decoded.textures.SKIN && decoded.textures.SKIN.metadata && decoded.textures.SKIN.metadata.model
            gender = model === 'slim' ? 'female' : 'male'
          } else {
            gender = 'male' // no textures property at all -- default skin, classic (Steve) model
          }
        }
      }
    }
  } catch {
    gender = null
  }
  skinGenderCache.set(username, gender)
  return gender
}

function pickFromPool (voicesDir, gender, username) {
  const pool = voices.listVoices(voicesDir).filter(v => v.gender === gender)
  if (pool.length === 0) return null
  if (pool.length === 1) return pool[0].id
  return pool[hashString(username) % pool.length].id
}

// Resolution order: muted override > voiced override > user pattern rule(s) > gender-inference
// cascade (username heuristic, then -- Java only -- actual skin model) > configured default.
async function resolveVoice (username, settings, instanceKind) {
  const override = overrides.find(o => o.username === username)
  if (override) {
    if (override.muted) return { voiceId: null, muted: true }
    return { voiceId: override.voiceId || null, muted: false }
  }

  const matches = patterns.filter(p => {
    if (!p.pattern) return false
    try {
      return new RegExp(p.pattern).test(username)
    } catch {
      return false
    }
  })

  if (matches.length === 1) return { voiceId: matches[0].voiceId || null, muted: false }
  if (matches.length > 1) {
    const idx = hashString(username) % matches.length
    return { voiceId: matches[idx].voiceId || null, muted: false }
  }

  const voicesDir = settings && settings.voicesDir
  let gender = inferGenderFromUsername(username)
  if (!gender && instanceKind === 'java') gender = await lookupSkinGender(username)
  if (gender) {
    const picked = pickFromPool(voicesDir, gender, username)
    if (picked) return { voiceId: picked, muted: false }
  }

  return { voiceId: (settings && settings.defaultVoiceId) || null, muted: false }
}

module.exports = {
  listPatterns: () => patterns,
  addPattern: ({ voiceId, pattern }) => {
    const row = { id: randomUUID(), voiceId: voiceId || '', pattern: pattern || '' }
    patterns.push(row)
    savePatterns()
    return row
  },
  updatePattern: (id, { voiceId, pattern }) => {
    const row = patterns.find(p => p.id === id)
    if (!row) return null
    if (voiceId !== undefined) row.voiceId = voiceId
    if (pattern !== undefined) row.pattern = pattern
    savePatterns()
    return row
  },
  deletePattern: (id) => {
    const before = patterns.length
    patterns = patterns.filter(p => p.id !== id)
    if (patterns.length !== before) savePatterns()
  },

  listOverrides: () => overrides,
  setOverride: (username, { voiceId, muted }) => {
    let row = overrides.find(o => o.username === username)
    if (!row) {
      row = { username, voiceId: voiceId || '', muted: !!muted }
      overrides.push(row)
    } else {
      if (voiceId !== undefined) row.voiceId = voiceId
      if (muted !== undefined) row.muted = !!muted
    }
    saveOverrides()
    return row
  },
  deleteOverride: (username) => {
    const before = overrides.length
    overrides = overrides.filter(o => o.username !== username)
    if (overrides.length !== before) saveOverrides()
  },

  resolveVoice
}
