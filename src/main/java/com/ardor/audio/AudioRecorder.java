package com.ardor.audio;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Captures mic audio between start()/stop() as mono 16-bit PCM, written as a
 * WAV file. 16kHz is preferred (whisper.cpp's native rate, smaller file,
 * no resampling needed) but not assumed available -- a real crash confirmed
 * that demanding it outright throws IllegalArgumentException when the
 * hardware doesn't expose that rate directly (common: consumer mics/drivers
 * usually offer 44.1kHz/48kHz natively, not 16kHz), and that exception
 * propagating out of a Fabric tick callback took the whole client down. Now
 * tries a small list of common rates via AudioSystem.isLineSupported(), no
 * exception in the common case. Whichever rate lands, whisper.cpp resamples
 * on read (confirmed empirically: it correctly transcribed a Piper-generated
 * WAV at Piper's own non-16kHz output rate during setup), so this doesn't
 * need to match 16kHz exactly for correctness -- just avoid crashing.
 */
public final class AudioRecorder {

    private static final int[] CANDIDATE_SAMPLE_RATES = {16000, 48000, 44100, 22050};

    private AudioFormat format;
    private TargetDataLine line;
    private Thread captureThread;
    private ByteArrayOutputStream buffer;
    private volatile boolean recording;
    private double lastDurationSeconds;

    public void start() {
        if (recording) return;
        format = null;
        line = null;
        for (int rate : CANDIDATE_SAMPLE_RATES) {
            AudioFormat candidate = new AudioFormat(rate, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, candidate);
            if (!AudioSystem.isLineSupported(info)) continue;
            try {
                TargetDataLine candidateLine = (TargetDataLine) AudioSystem.getLine(info);
                candidateLine.open(candidate);
                line = candidateLine;
                format = candidate;
                break;
            } catch (LineUnavailableException ignored) {
                // in use by another app -- try the next candidate rate/line
            }
        }
        if (line == null) {
            throw new IllegalStateException(
                    "No usable microphone line found (tried " + CANDIDATE_SAMPLE_RATES.length + " common sample rates)");
        }

        line.start();
        buffer = new ByteArrayOutputStream();
        recording = true;
        captureThread = new Thread(this::captureLoop, "ardor-audio-capture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    private void captureLoop() {
        byte[] chunk = new byte[4096];
        while (recording) {
            int read = line.read(chunk, 0, chunk.length);
            if (read > 0) buffer.write(chunk, 0, read);
        }
    }

    public Path stop(Path outFile) {
        if (!recording) throw new IllegalStateException("not recording");
        recording = false;
        // line.stop() MUST come before the join -- captureLoop is blocked inside
        // line.read(), which only returns once the line is stopped. Calling join()
        // first (the original bug: confirmed live, froze the whole client on the
        // render thread) waits forever for a thread nothing will ever wake up.
        line.stop();
        try {
            captureThread.join(2000); // bounded -- never hang the caller (render thread) indefinitely
            if (captureThread.isAlive()) {
                System.err.println("[ardor] audio capture thread did not stop within timeout, proceeding anyway");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        line.close();

        byte[] audio = buffer.toByteArray();
        int frames = audio.length / format.getFrameSize();
        lastDurationSeconds = frames / format.getFrameRate();
        AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(audio), format, frames);
        try {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, outFile.toFile());
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + outFile, e);
        }
        return outFile;
    }

    /** Duration of the most recently stopped recording, in seconds. Use to skip transcribing near-empty taps. */
    public double lastDurationSeconds() {
        return lastDurationSeconds;
    }
}
