// Scans a Piper voices directory (recursively, so voicesDir/custom/ works too) for
// <name>.onnx + <name>.onnx.json pairs -- both required by Piper, same convention as
// PiperSpeaker.java on the Java side.
const fs = require('fs')
const path = require('path')

function baseName (id) {
  return id
    .replace(/^en_(us|gb)-/i, '')
    .replace(/-(high|medium|low)$/i, '')
    .toLowerCase()
}

function humanize (id) {
  return baseName(id)
    .replace(/[-_]+/g, ' ')
    .trim()
    .replace(/\b\w/g, c => c.toUpperCase())
}

// Piper's own voice manifests don't reliably carry a gender field, so this is a curated lookup
// for the names this project's own installer (scripts/install-voices.ps1) actually ships, plus a
// crude name-substring fallback for anything else dropped into voices/custom/. Used by
// narration.js's gender-inference fallback tier (see its own comment) to pick a voice pool.
const GENDER_BY_NAME = {
  lessac: 'female',
  ryan: 'male',
  glados: 'female',
  amy: 'female',
  danny: 'male',
  kristin: 'female',
  hfc_female: 'female',
  hfc_male: 'male',
  joe: 'male',
  kusal: 'male',
  john: 'male',
  norman: 'male',
  bryce: 'male',
  mike: 'male',
  kathleen: 'female',
  alan: 'male',
  alba: 'female',
  jenny_dioco: 'female',
  northern_english_male: 'male',
  southern_english_female: 'female',
  ljspeech: 'female',
  reza_ibrahim: 'male'
}

function inferGender (id) {
  const name = baseName(id)
  if (GENDER_BY_NAME[name]) return GENDER_BY_NAME[name]
  if (name.includes('female')) return 'female'
  if (name.includes('male')) return 'male'
  return 'neutral'
}

function listVoices (voicesDir) {
  if (!voicesDir || !fs.existsSync(voicesDir)) return []
  const results = []
  const walk = (dir) => {
    let entries
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true })
    } catch {
      return
    }
    for (const entry of entries) {
      const full = path.join(dir, entry.name)
      if (entry.isDirectory()) {
        walk(full)
      } else if (entry.isFile() && entry.name.toLowerCase().endsWith('.onnx')) {
        const configPath = full + '.json'
        if (!fs.existsSync(configPath)) continue
        const id = entry.name.slice(0, -'.onnx'.length)
        results.push({
          id,
          name: humanize(id),
          gender: inferGender(id),
          onnxPath: full,
          configPath,
          dir: path.relative(voicesDir, dir) || '.'
        })
      }
    }
  }
  walk(voicesDir)
  results.sort((a, b) => a.name.localeCompare(b.name))
  return results
}

function findVoice (voicesDir, id) {
  if (!id) return null
  return listVoices(voicesDir).find(v => v.id === id) || null
}

module.exports = { listVoices, findVoice }
