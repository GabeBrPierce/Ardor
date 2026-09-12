package com.ardor.planner;

import java.util.List;

/** One step of a decomposed plan: a human-readable description plus the ascii-grammar commands that carry it out. */
public record PlannedTask(String description, List<String> commands) {}
