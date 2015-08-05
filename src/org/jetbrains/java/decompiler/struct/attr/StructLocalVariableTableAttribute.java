/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package org.jetbrains.java.decompiler.struct.attr;

import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionPair;
import org.jetbrains.java.decompiler.struct.consts.ConstantPool;
import org.jetbrains.java.decompiler.util.DataInputFullStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/*
  u2 local_variable_table_length;
  local_variable {
    u2 start_pc;
    u2 length;
    u2 name_index;
    u2 descriptor_index;
    u2 index;
  }
*/
public class StructLocalVariableTableAttribute extends StructGeneralAttribute {

  public static class LVTVariable implements Comparable<LVTVariable> {
      public final String name;
      public final int start;
      public final int end;
      public final int index;
      private String desc;
      private String sig;
      private boolean isLVTT;
      LVTVariable(String name, String desc, int start, int end, int index, boolean isLVTT) {
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
  }

  private static Comparator<LVTVariable> comp = new Comparator<LVTVariable>() {
    @Override
    public int compare(LVTVariable o1, LVTVariable o2)
    {
      if (o1.index != o2.index) return o1.index - o2.index;
      if (o1.start != o2.start) return o1.start - o2.start;
      return o1.end - o2.end;
    }
  };
  private Map<VarVersionPair, String> mapVarNames = Collections.emptyMap();

  private Map<Integer, Set<LVTVariable>> endpoints = Collections.emptyMap();
  private ArrayList<LVTVariable> allLVT;

  @Override
  public void initContent(ConstantPool pool) throws IOException {
    DataInputFullStream data = stream();

    int len = data.readUnsignedShort();
    boolean isLVTT = this.getName().equals(ATTRIBUTE_LOCAL_VARIABLE_TYPE_TABLE);
    if (len > 0) {
      mapVarNames = new HashMap<VarVersionPair, String>(len);
      endpoints = new HashMap<Integer,Set<LVTVariable>>(len);
      allLVT = new ArrayList<LVTVariable>(len);
      for (int i = 0; i < len; i++) {
        int start = data.readUnsignedShort();
        int vlen = data.readUnsignedShort();
        int nameIndex = data.readUnsignedShort();
        int descIndex = data.readUnsignedShort(); // either descriptor or signature
        int varIndex = data.readUnsignedShort();
        LVTVariable v = new LVTVariable(pool.getPrimitiveConstant(nameIndex).getString(), pool.getPrimitiveConstant(descIndex).getString(),start,start+vlen,varIndex,isLVTT);
        allLVT.add(v);
        v.addTo(endpoints);
      }
      Collections.sort(allLVT, comp);
      buildNameMap();
    }
    else {
      mapVarNames = Collections.emptyMap();
    }
  }

  public void addLocalVariableTable(StructLocalVariableTableAttribute attr) {
    mapVarNames.putAll(attr.getMapVarNames());
    for (LVTVariable other : attr.allLVT) {
      int idx = allLVT.indexOf(other);
      if (idx < 0) {
        allLVT.add(other);
      }
      else {
        LVTVariable mine = allLVT.get(idx);
        mine.merge(other);
      }
    }
    Collections.sort(allLVT, comp);
  }

  private void buildNameMap() {
    Map<Integer, Integer> versions = new HashMap<Integer, Integer>();
    mapVarNames.clear();
    for (LVTVariable lvt : allLVT) {
      Integer idx = versions.get(lvt.index);
      if (idx == null)
        idx = 0;
      else
        idx++;
      versions.put(lvt.index, idx);
      mapVarNames.put(new VarVersionPair(lvt.index, idx.intValue()), lvt.name);
    }
  }

  public Map<VarVersionPair, String> getMapVarNames() {
    return mapVarNames;
  }
}
