package org.jetbrains.java.decompiler.modules.decompiler.vars;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LocalVariableTable {
  private Map<Integer, Set<LVTVariable>> startpoints;
  private ArrayList<LVTVariable> allLVT;
  private Map<Integer, List<LVTVariable>> mapLVT;

  public LocalVariableTable(int len) {
    startpoints = new HashMap<Integer,Set<LVTVariable>>(len);
    allLVT = new ArrayList<LVTVariable>(len);
  }

  public void addVariable(LVTVariable v) {
    allLVT.add(v);
    v.addTo(startpoints);
  }

  public void mergeLVTs(LocalVariableTable otherLVT) {
   for (LVTVariable other : otherLVT.allLVT) {
      int idx = allLVT.indexOf(other);
      if (idx < 0) {
        allLVT.add(other);
      }
      else {
        LVTVariable mine = allLVT.get(idx);
        mine.merge(other);
      }
    }
    mapLVT = null; // Invalidate the cache and rebuild it.
  }

  public LVTVariable find(int index, List<Integer> offsets) {
    for (Integer offset : offsets) {
      Set<LVTVariable> lvs = startpoints.get(offset);
      if (lvs == null || lvs.isEmpty())
        continue;

      for (LVTVariable lv : lvs) {
        if (lv.index == index)
          return lv;
      }
    }
    return null;
  }

  public Map<Integer, List<LVTVariable>> getMapVarNames() {
    if (mapLVT == null)
      buildNameMap();
    return mapLVT;
  }

  private void buildNameMap() {
    Map<Integer, Integer> versions = new HashMap<Integer, Integer>();
    mapLVT = new HashMap<Integer,List<LVTVariable>>();
    for (LVTVariable lvt : allLVT) {
      Integer idx = versions.get(lvt.index);
      if (idx == null)
        idx = 1;
      else
        idx++;
      versions.put(lvt.index, idx);
      List<LVTVariable> lvtList = mapLVT.get(lvt.index);
      if (lvtList == null) {
          lvtList = new ArrayList<LVTVariable>();
          mapLVT.put(lvt.index, lvtList);
      }
      lvtList.add(lvt);
    }
  }

  public List<LVTVariable> getCandidates(int index) {
    return mapLVT.get(index);
  }
}