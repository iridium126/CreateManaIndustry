package com.iridium126.createmanaindustry.client.greattrick;

import com.iridium126.createmanaindustry.compat.trickster.GreatPattern;
import java.util.List;

/** Supports Trickster's click/move/click gesture and press/drag/release without panning the soup. */
public final class GreatDrawingGesture {
    private List<Integer> path;
    private boolean pressed;
    private boolean finishOnRelease;
    private boolean dragged;

    public boolean drawing() { return path != null; }
    public List<Integer> path() { return path; }

    public boolean press(int dot) {
        if (drawing()) {
            pressed = true;
            finishOnRelease = true;
            return true;
        }
        if (dot < 0 || dot >= GreatPattern.DOT_COUNT) return false;
        path = List.of(dot);
        pressed = true;
        finishOnRelease = false;
        dragged = false;
        return true;
    }

    public void move(int dot) {
        if (!drawing() || dot < 0 || dot >= GreatPattern.DOT_COUNT) return;
        path = GreatPattern.move(path, dot);
        if (pressed && path.size() > 1) dragged = true;
    }

    /** Null means the initial click leaves the stroke active; a drag or a second click completes it. */
    public GreatPattern release() {
        if (!pressed) return null;
        pressed = false;
        return finishOnRelease || dragged ? finish() : null;
    }

    public GreatPattern finish() {
        GreatPattern pattern = drawing() ? GreatPattern.fromPath(path) : null;
        cancel();
        return pattern;
    }

    public void cancel() {
        path = null;
        pressed = false;
        finishOnRelease = false;
        dragged = false;
    }
}
