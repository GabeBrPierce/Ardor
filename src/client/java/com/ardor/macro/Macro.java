package com.ardor.macro;

import java.util.ArrayList;
import java.util.List;

/** A named, recorded sequence of per-tick input samples -- see MacroRecorder/MacroPlayer. */
public final class Macro {
    public String name;
    public List<Sample> samples = new ArrayList<>();

    public static final class Sample {
        public boolean forward, backward, left, right, jump, shift, sprint;
        public float yaw, pitch;
    }
}
