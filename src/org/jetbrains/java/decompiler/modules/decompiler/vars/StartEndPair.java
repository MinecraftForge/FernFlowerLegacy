package org.jetbrains.java.decompiler.modules.decompiler.vars;

public class StartEndPair {
    public final int start;
    public final int end;
    public StartEndPair(int start, int end) {
        this.start = start;
        this.end = end;
    }
    @Override
    public boolean equals(Object obj) {
        return ((StartEndPair)obj).start == start && ((StartEndPair)obj).end == end;
    }
    @Override
    public int hashCode() {
        return start * 31 + end;
    }
}
