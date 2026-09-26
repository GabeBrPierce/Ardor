// Node equivalent of src/main/java/com/ardor/tts/PiperSpeaker.java -- shells out to the same
// piper binary the same way: `piper --model <voice.onnx> --output_file <out.wav>` with the
// text written to stdin.
const { spawn } = require('child_process')
const fs = require('fs')

function synthesize ({ binaryPath, modelPath, text, outWav }) {
  return new Promise((resolve, reject) => {
    if (!binaryPath) return reject(new Error('piper binary path not configured (see Settings)'))
    if (!fs.existsSync(modelPath)) return reject(new Error('voice model not found: ' + modelPath))

    let proc
    try {
      proc = spawn(binaryPath, ['--model', modelPath, '--output_file', outWav])
    } catch (err) {
      return reject(err)
    }

    let stderr = ''
    proc.stderr.on('data', (d) => { stderr += d.toString() })
    proc.on('error', (err) => reject(new Error('failed to run piper: ' + err.message)))
    proc.on('close', (code) => {
      if (code !== 0) return reject(new Error('piper exited ' + code + (stderr ? ': ' + stderr.trim().slice(-300) : '')))
      resolve(outWav)
    })

    proc.stdin.write(text, 'utf8')
    proc.stdin.end()
  })
}

module.exports = { synthesize }
