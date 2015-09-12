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
    return ((LVTVariable) obj).index == index && ((LVTVariable) obj).end == end;
  }

  @Override
  public int hashCode() {
    return index * 31 + end;
  }

  public void addTo(Map<Integer, Set<LVTVariable>> endpoints) {
    Set<LVTVariable> ends = endpoints.get(this.end);
    if (ends == null) {
      ends = new HashSet<LVTVariable>();
      endpoints.put(this.end, ends);
    }
    ends.add(this);
  }

  @Override
  public int compareTo(LVTVariable o) {
    if (o.end > end) return -1;
    if (o.end < end) return 1;
    if (o.index > index) return -1;
    if (o.index < index) return 1;
    return 0;
  }
  @Override
  public String toString() {
    return "\'("+index+","+end+")"+desc+(sig!=null ? "<"+sig+"> ":" ")+name+"\'";
  }

  public String getDesc() {
    return desc;
  }

  public String getSig() {
    return sig;
  }
}