package com.ardor.audio;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AudioPlayer {

    // A Clip's only other reference after play() returns is inside its own
    // LineListener lambda -- a closed, self-contained cycle with no external
    // anchor, which a tracing GC is free to collect regardless of the cycle.
    // Confirmed live: a valid TTS WAV was generated and playback started with
    // no exception, but was never audible -- the textbook symptom. Keeping a
    // strong reference here until STOP fires is the standard fix.
    private static final Set<Clip> ACTIVE_CLIPS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private AudioPlayer() {}

    public static void play(Path wavFile) {
        try {
            AudioInputStream stream = AudioSystem.getAudioInputStream(wavFile.toFile());
            Clip clip = AudioSystem.getClip();
            clip.open(stream);
            ACTIVE_CLIPS.add(clip);
            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP) {
                    clip.close();
                    ACTIVE_CLIPS.remove(clip);
                }
            });
            clip.start();
        } catch (UnsupportedAudioFileException | IOException | LineUnavailableException e) {
            throw new RuntimeException("Failed to play " + wavFile, e);
        }
    }
}
