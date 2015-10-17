/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.java.decompiler.modules.decompiler.vars;

import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.collectors.VarNamesCollector;
import org.jetbrains.java.decompiler.modules.decompiler.exps.VarExprent;
import org.jetbrains.java.decompiler.modules.decompiler.stats.RootStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.struct.gen.VarType;

import java.util.*;
import java.util.Map.Entry;

public class VarProcessor {

  private Map<VarVersionPair, String> mapVarNames = new HashMap<VarVersionPair, String>();
  private Map<VarVersionPair, LVTVariable> mapVarLVTs = new HashMap<VarVersionPair, LVTVariable>();
  private VarVersionsProcessor varVersions;
  private final Map<VarVersionPair, String> thisVars = new HashMap<VarVersionPair, String>();
  private final Set<VarVersionPair> externalVars = new HashSet<VarVersionPair>();
  private LocalVariableTable lvt;

  public void setVarVersions(RootStatement root) {
    Map<Integer, VarVersionPair> mapOriginalVarIndices = null;
    if (varVersions != null) {
        mapOriginalVarIndices = varVersions.getMapOriginalVarIndices();
    }
    varVersions = new VarVersionsProcessor();
    varVersions.setVarVersions(root);
    if (mapOriginalVarIndices != null) {
        varVersions.getMapOriginalVarIndices().putAll(mapOriginalVarIndices);
    }
  }

  public void setVarDefinitions(Statement root) {
    mapVarNames = new HashMap<VarVersionPair, String>();

    StructMethod mt = (StructMethod)DecompilerContext.getProperty(DecompilerContext.CURRENT_METHOD);
    new VarDefinitionHelper(root, mt, this).setVarDefinitions();
  }

  public void setDebugVarNames(Map<Integer, List<LVTVariable>> mapDebugVarNames) {
    if (varVersions == null) {
      return;
    }

    Map<Integer, VarVersionPair> mapOriginalVarIndices = varVersions.getMapOriginalVarIndices();

    List<VarVersionPair> listVars = new ArrayList<VarVersionPair>(mapVarNames.keySet());
    Collections.sort(listVars, new Comparator<VarVersionPair>() {
      @Override
      public int compare(VarVersionPair o1, VarVersionPair o2) {
        return o1.var - o2.var;
      }
    });

    Map<String, Integer> mapNames = new HashMap<String, Integer>();
    Map<Integer,SortedSet<VarVersionPair>> indexedPairs = new HashMap<Integer,SortedSet<VarVersionPair>>();
    Comparator<VarVersionPair> vvpVersionComparator = new Comparator<VarVersionPair>() {
        @Override
        public int compare(VarVersionPair o1, VarVersionPair o2) {
            return o1.version - o2.version;
        }
    };
    for (Entry<Integer, VarVersionPair> vvp : mapOriginalVarIndices.entrySet()) {
        SortedSet<VarVersionPair> set = indexedPairs.get(vvp.getValue().var);
        if (set == null) {
            set = new TreeSet<VarVersionPair>(vvpVersionComparator);
            indexedPairs.put(vvp.getValue().var, set);
        }
        set.add(vvp.getValue());
    }
    for (VarVersionPair pair : listVars) {
      String name = mapVarNames.get(pair);

      VarVersionPair key = mapOriginalVarIndices.get(pair.var);

      boolean lvtName = false;
      if (key != null) {
        if (indexedPairs.containsKey(key.var)) {
          int veridx = indexedPairs.get(key.var).headSet(key).size();
          List<LVTVariable> list = mapDebugVarNames.get(key.var);
          if (list != null && list.size()>veridx) {
              name = list.get(veridx).name;
              lvtName = true;
          } else if (list == null) {
              // we're an exception type, probably. let's just fall through
          }
        }
      }

      Integer counter = mapNames.get(name);
      mapNames.put(name, counter == null ? counter = new Integer(0) : ++counter);

      if (counter > 0 && !lvtName) {
        name += String.valueOf(counter);
      }

      mapVarNames.put(pair, name);
    }
  }

  public void refreshVarNames(VarNamesCollector vc) {
    Map<VarVersionPair, String> tempVarNames = new HashMap<VarVersionPair, String>(mapVarNames);
    for (Entry<VarVersionPair, String> ent : tempVarNames.entrySet()) {
      mapVarNames.put(ent.getKey(), vc.getFreeName(ent.getValue()));
    }
  }

  public VarType getVarType(VarVersionPair pair) {
    return varVersions == null ? null : varVersions.getVarType(pair);
  }

  public void setVarType(VarVersionPair pair, VarType type) {
    if (varVersions != null) {
      varVersions.setVarType(pair, type);
    }
  }

  public String getVarName(VarVersionPair pair) {
    return mapVarNames == null ? null : mapVarNames.get(pair);
  }

  public void setVarName(VarVersionPair pair, String name) {
    mapVarNames.put(pair, name);
  }

  public int getVarFinal(VarVersionPair pair) {
    return varVersions == null ? VarTypeProcessor.VAR_FINAL : varVersions.getVarFinal(pair);
  }

  public void setVarFinal(VarVersionPair pair, int finalType) {
    varVersions.setVarFinal(pair, finalType);
  }

  public Map<VarVersionPair, String> getThisVars() {
    return thisVars;
  }

  public Set<VarVersionPair> getExternalVars() {
    return externalVars;
  }

  public void setLVT(LocalVariableTable lvt) {
    this.lvt = lvt;
  }

  public LocalVariableTable getLVT() {
    return this.lvt;
  }

  public void findLVT(VarExprent varExprent, int bytecodeOffset) {
    LVTVariable var = this.lvt == null ? null : lvt.find(varExprent.getIndex(), bytecodeOffset);
    if (var != null) {
      varExprent.setLVT(var);
    }
  }

  public int getRemapped(int index) {
    VarVersionPair res = varVersions.getMapOriginalVarIndices().get(index);
    if (res == null) return index;
    return res.var;
  }

  public void copyVarInfo(VarVersionPair from, VarVersionPair to) {
    setVarName(to, getVarName(from));
    setVarFinal(to, getVarFinal(from));
    setVarType(to, getVarType(from));
    varVersions.getMapOriginalVarIndices().put(to.var, varVersions.getMapOriginalVarIndices().get(from.var));
  }

  public VarVersionsProcessor getVarVersions() {
    return varVersions;
  }

  public void setVarLVT(VarVersionPair var, LVTVariable lvt) {
    mapVarLVTs.put(var, lvt);
  }

  public LVTVariable getVarLVT(VarVersionPair var) {
    return mapVarLVTs.get(var);
  }
}
