package org.jetbrains.java.decompiler.modules.decompiler.vars;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class LVTVariable implements Comparable<LVTVariable> {
  public static final Comparator<LVTVariable> INDEX_SORTER = new Comparator<LVTVariable>() {
    @Override
    public int compare(LVTVariable o1, LVTVariable o2) {
      if (o1.index != o2.index) return o1.index - o2.index;
      if (o1.start != o2.start) return o1.start - o2.start;
      return o1.end - o2.end;
    }
  };

  public final String name;
  public final int start;
  public final int end;
  public final int index;
  private String desc;
  private String sig;
  private boolean isLVTT;

  public LVTVariable(String name, String desc, int start, int end, int index, boolean isLVTT) {
    this.name = name;
    this.desc = desc;
    this.start = start;
    this.end = end;
    this.index = index;
    this.isLVTT = isLVTT;
  }

  void merge(LVTVariable other) {
    if (other.isLVTT && this.sig == null) {
      this.sig = other.desc;
    }
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof LVTVariable))
      return false;
    return ((LVTVariable) obj).index == index && ((LVTVariable) obj).start == start;
  }

  @Override
  public int hashCode() {
    return index * 31 + start;
  }

  public void addTo(Map<Integer, Set<LVTVariable>> startpoints) {
    Set<LVTVariable> starts = startpoints.get(this.start);
    if (starts == null) {
      starts = new HashSet<LVTVariable>();
      startpoints.put(this.start, starts);
    }
    starts.add(this);
  }

  @Override
  public int compareTo(LVTVariable o) {
    if (o.start > start) return -1;
    if (o.start < start) return 1;
    if (o.index > index) return -1;
    if (o.index < index) return 1;
    return 0;
  }
}