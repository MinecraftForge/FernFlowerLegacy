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
package org.jetbrains.java.decompiler.util;

import java.util.List;

import org.jetbrains.java.decompiler.modules.decompiler.exps.AssignmentExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.Exprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.VarExprent;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionPair;

public class ExprentUtil {
  public static boolean isVarReferenced(VarExprent var, Statement stat, VarExprent... whitelist) {
    if (stat.getExprents() == null) {
      for (Object obj : stat.getSequentialObjects()) {
        if (obj instanceof Statement) {
          if (isVarReferenced(var, (Statement)obj, whitelist)) {
            return true;
          }
        }
        else if (obj instanceof Exprent) {
          if (isVarReferenced(var, (Exprent)obj, whitelist)) {
            return true;
          }
        }
      }
    }
    else {
      for (Exprent exp : stat.getExprents()) {
        if (isVarReferenced(var, exp, whitelist)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean isVarReferenced(VarExprent target, Exprent exp, VarExprent... whitelist) {
    List<Exprent> lst = exp.getAllExprents(true);
    lst.add(exp);
    for (Exprent ex : lst) {
      if (ex != target && ex.type == Exprent.EXPRENT_VAR) {
        VarExprent var = (VarExprent)ex;
        if (var.getIndex() == target.getIndex() && var.getVersion() == target.getVersion()) {
          boolean allowed = false;
          for (VarExprent white : whitelist) {
            if (var == white) {
              allowed = true;
            }
          }
          if (!allowed) {
            return true;
          }
        }
      }
    }
    return false;
  }

  public static boolean isVarReadFirst(VarVersionPair var, Statement stat, int index, VarExprent... whitelist) {
    if (stat.getExprents() == null) {
      List<Object> objs = stat.getSequentialObjects();
      for (int x = index; x < objs.size(); x++) {
        Object obj = objs.get(x);
        if (obj instanceof Statement) {
          if (isVarReadFirst(var, (Statement)obj, 0, whitelist)) {
            return true;
          }
        }
        else if (obj instanceof Exprent) {
          if (isVarReadFirst(var, (Exprent)obj, whitelist)) {
            return true;
          }
        }
      }
    }
    else {
      for (int x = index; x < stat.getExprents().size(); x++) {
        if (isVarReadFirst(var, stat.getExprents().get(x), whitelist)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean isVarReadFirst(VarVersionPair target, Exprent exp, VarExprent... whitelist) {
    AssignmentExprent ass = exp.type == Exprent.EXPRENT_ASSIGNMENT ? (AssignmentExprent)exp : null;
    List<Exprent> lst = exp.getAllExprents(true);
    lst.add(exp);
    for (Exprent ex : lst) {
      if (ex.type == Exprent.EXPRENT_VAR) {
        VarExprent var = (VarExprent)ex;
        if (var.getIndex() == target.var && var.getVersion() == target.version) {
          boolean allowed = false;
          if (ass != null) {
            if (var == ass.getLeft()) {
              allowed = true;
            }
          }
          for (VarExprent white : whitelist) {
            if (var == white) {
              allowed = true;
            }
          }
          if (!allowed) {
            return true;
          }
        }
      }
    }
    return false;
  }
}
