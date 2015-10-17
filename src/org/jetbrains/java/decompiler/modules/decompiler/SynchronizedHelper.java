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
package org.jetbrains.java.decompiler.modules.decompiler;

import org.jetbrains.java.decompiler.modules.decompiler.exps.AssignmentExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.Exprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.MonitorExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.VarExprent;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.SynchronizedStatement;
import org.jetbrains.java.decompiler.util.ExprentUtil;

public class SynchronizedHelper {
  public static void cleanSynchronizedVar(Statement stat) {
    for (Statement st : stat.getStats()) {
      cleanSynchronizedVar(st);
    }

    if (stat.type == Statement.TYPE_SYNCRONIZED) {
      SynchronizedStatement sync = (SynchronizedStatement)stat;
      if (sync.getHeadexprent().type == Exprent.EXPRENT_MONITOR) {
        MonitorExprent mon = (MonitorExprent)sync.getHeadexprent();
        for (Exprent e : sync.getFirst().getExprents()) {
          if (e.type == Exprent.EXPRENT_ASSIGNMENT) {
            AssignmentExprent ass = (AssignmentExprent)e;
            if (ass.getLeft().type == Exprent.EXPRENT_VAR) {
              VarExprent var = (VarExprent)ass.getLeft();
              if (ass.getRight().equals(mon.getValue()) && !ExprentUtil.isVarReferenced(var, stat.getParent())) {
                sync.getFirst().getExprents().remove(e);
                break;
              }
            }
          }
        }
      }
    }
  }
}
