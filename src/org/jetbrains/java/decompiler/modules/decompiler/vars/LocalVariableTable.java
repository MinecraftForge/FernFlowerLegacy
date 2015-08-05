package org.jetbrains.java.decompiler.modules.decompiler.vars;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LocalVariableTable {
    private Map<Integer, Set<LVTVariable>> startpoints;
    private ArrayList<LVTVariable> allLVT;

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
            } else {
                LVTVariable mine = allLVT.get(idx);
                mine.merge(other);
            }
        }
    }

    public LVTVariable find(Integer index, List<Integer> offsets) {
        for (Integer offset : offsets) {
            Set<LVTVariable> lvs = startpoints.get(offset);
            if (lvs == null || lvs.isEmpty()) continue;
            int idx = index.intValue();

            for (LVTVariable lv : lvs) {
                if (lv.index == idx) return lv;
            }
        }
        return null;
    }
}
