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

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.collectors.VarNamesCollector;
import org.jetbrains.java.decompiler.modules.decompiler.exps.AssignmentExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.Exprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.VarExprent;
import org.jetbrains.java.decompiler.modules.decompiler.stats.CatchAllStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.CatchStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.DoStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.SequenceStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;

import java.util.*;
import java.util.Map.Entry;
import java.util.AbstractMap.SimpleImmutableEntry;

public class VarDefinitionHelper {

  private final HashMap<Integer, Statement> mapVarDefStatements;

  // statement.id, defined vars
  private final HashMap<Integer, HashSet<Integer>> mapStatementVars;

  private final HashSet<Integer> implDefVars;

  private final VarProcessor varproc;

  public VarDefinitionHelper(Statement root, StructMethod mt, VarProcessor varproc) {

    mapVarDefStatements = new HashMap<Integer, Statement>();
    mapStatementVars = new HashMap<Integer, HashSet<Integer>>();
    implDefVars = new HashSet<Integer>();
    this.varproc = varproc;

    VarNamesCollector vc = DecompilerContext.getVarNamesCollector();

    boolean thisvar = !mt.hasModifier(CodeConstants.ACC_STATIC);

    MethodDescriptor md = MethodDescriptor.parseDescriptor(mt.getDescriptor());

    int paramcount = 0;
    if (thisvar) {
      paramcount = 1;
    }
    paramcount += md.params.length;


    // method parameters are implicitly defined
    int varindex = 0;
    for (int i = 0; i < paramcount; i++) {
      implDefVars.add(varindex);
      VarVersionPair vvp = new VarVersionPair(varindex, 0);
      varproc.setVarName(vvp, vc.getFreeName(varindex));
      if (thisvar) {
        if (i == 0) {
          varindex++;
        }
        else {
          varindex += md.params[i - 1].stackSize;
        }
      }
      else {
        varindex += md.params[i].stackSize;
      }
    }

    if (thisvar) {
      StructClass current_class = (StructClass)DecompilerContext.getProperty(DecompilerContext.CURRENT_CLASS);

      varproc.getThisVars().put(new VarVersionPair(0, 0), current_class.qualifiedName);
      varproc.setVarName(new VarVersionPair(0, 0), "this");
      vc.addName("this");
    }

    mergeVars(root);

    // catch variables are implicitly defined
    LinkedList<Statement> stack = new LinkedList<Statement>();
    stack.add(root);

    while (!stack.isEmpty()) {
      Statement st = stack.removeFirst();

      List<VarExprent> lstVars = null;
      if (st.type == Statement.TYPE_CATCHALL) {
        lstVars = ((CatchAllStatement)st).getVars();
      }
      else if (st.type == Statement.TYPE_TRYCATCH) {
        lstVars = ((CatchStatement)st).getVars();
      }

      if (lstVars != null) {
        for (VarExprent var : lstVars) {
          implDefVars.add(var.getIndex());
          VarVersionPair pair = new VarVersionPair(var);
          varproc.setVarName(pair, vc.getFreeName(var.getIndex()));
          var.setDefinition(true);
        }
      }

      stack.addAll(st.getStats());
    }

    initStatement(root);
  }

  public void setVarDefinitions() {
    VarNamesCollector vc = DecompilerContext.getVarNamesCollector();

    Map<SequenceStatement,Map<Integer,VarExprent>> trackingMap = new HashMap<SequenceStatement,Map<Integer,VarExprent>>();
    for (Entry<Integer, Statement> en : mapVarDefStatements.entrySet()) {
      Statement stat = en.getValue();
      if (!trackingMap.containsKey(stat.getParentSequenceStat())) {
          trackingMap.put(stat.getParentSequenceStat(), new HashMap<Integer,VarExprent>());
      }
      Map<Integer, VarExprent> scopedMap = trackingMap.get(stat.getParentSequenceStat());
      Integer index = en.getKey();
      int newindex = varproc.getRemapped(index);
      setupLVTs(stat);

      if (implDefVars.contains(index)) {
        // already implicitly defined
        continue;
      }

      varproc.setVarName(new VarVersionPair(index.intValue(), 0), vc.getFreeName(index));

      // special case for
      if (stat.type == Statement.TYPE_DO) {
        DoStatement dstat = (DoStatement)stat;
        if (dstat.getLooptype() == DoStatement.LOOP_FOR) {

          if (dstat.getInitExprent() != null && setDefinition(dstat.getInitExprent(), index, scopedMap)) {
            continue;
          }
          else {
            List<Exprent> lstSpecial = Arrays.asList(dstat.getConditionExprent(), dstat.getIncExprent());
            for (VarExprent var : getAllVars(lstSpecial)) {
              if (var.getIndex() == index.intValue()) {
                stat = stat.getParent();
                break;
              }
            }
          }
        }
        else if (dstat.getLooptype() == DoStatement.LOOP_FOREACH) {
          if (dstat.getInitExprent() != null && dstat.getInitExprent().type == Exprent.EXPRENT_VAR) {
            VarExprent var = (VarExprent)dstat.getInitExprent();
            if (var.getIndex() == index.intValue()) {
              var.setDefinition(true);
              continue;
            }
          }
        }
      }

      Statement first = findFirstBlock(stat, index);

      List<Exprent> lst;
      if (first == null) {
        lst = stat.getVarDefinitions();
      }
      else if (first.getExprents() == null) {
        lst = first.getVarDefinitions();
      }
      else {
        lst = first.getExprents();
      }

      boolean defset = false;

      // search for the first assignement to var [index]
      int addindex = 0;
      for (Exprent expr : lst) {

        if (scopedMap.containsKey(newindex)) {
            defset = true;
            break;
        }
        if (setDefinition(expr, index, scopedMap)) {
          defset = true;
          break;
        }
        else {
          boolean foundvar = false;
          for (Exprent exp : expr.getAllExprents(true)) {
            if (exp.type == Exprent.EXPRENT_VAR && ((VarExprent)exp).getIndex() == index) {
              foundvar = true;
              break;
            }
          }
          if (foundvar) {
            break;
          }
        }
        addindex++;
      }

      if (!defset) {
        VarExprent var = new VarExprent(index.intValue(), varproc.getVarType(new VarVersionPair(index.intValue(), 0)), varproc);
        var.setDefinition(true);

        if (varproc.getLVT() != null) {
          Map<Integer, LVTVariable> vars = varproc.getLVT().getVars(stat.getParentSequenceStat());
          if (vars.containsKey(var.getIndex())) {
              var.setLVT(vars.get(var.getIndex()));
          }
        }

        lst.add(addindex, var);
      }
    }
  }


  // *****************************************************************************
  // private methods
  // *****************************************************************************

  private Statement findFirstBlock(Statement stat, Integer varindex) {

    LinkedList<Statement> stack = new LinkedList<Statement>();
    stack.add(stat);

    while (!stack.isEmpty()) {
      Statement st = stack.remove(0);

      if (stack.isEmpty() || mapStatementVars.get(st.id).contains(varindex)) {

        if (st.isLabeled() && !stack.isEmpty()) {
          return st;
        }

        if (st.getExprents() != null) {
          return st;
        }
        else {
          stack.clear();

          switch (st.type) {
            case Statement.TYPE_SEQUENCE:
              stack.addAll(0, st.getStats());
              break;
            case Statement.TYPE_IF:
            case Statement.TYPE_ROOT:
            case Statement.TYPE_SWITCH:
            case Statement.TYPE_SYNCRONIZED:
              stack.add(st.getFirst());
              break;
            default:
              return st;
          }
        }
      }
    }

    return null;
  }

  private Set<Integer> initStatement(Statement stat) {

    HashMap<Integer, Integer> mapCount = new HashMap<Integer, Integer>();

    List<VarExprent> condlst;

    if (stat.getExprents() == null) {

      // recurse on children statements
      List<Integer> childVars = new ArrayList<Integer>();
      List<Exprent> currVars = new ArrayList<Exprent>();

      for (Object obj : stat.getSequentialObjects()) {
        if (obj instanceof Statement) {
          Statement st = (Statement)obj;
          childVars.addAll(initStatement(st));

          if (st.type == DoStatement.TYPE_DO) {
            DoStatement dost = (DoStatement)st;
            if (dost.getLooptype() != DoStatement.LOOP_FOR &&
                dost.getLooptype() != DoStatement.LOOP_FOREACH &&
                dost.getLooptype() != DoStatement.LOOP_DO) {
              currVars.add(dost.getConditionExprent());
            }
          }
          else if (st.type == DoStatement.TYPE_CATCHALL) {
            CatchAllStatement fin = (CatchAllStatement)st;
            if (fin.isFinally() && fin.getMonitor() != null) {
              currVars.add(fin.getMonitor());
            }
          }
        }
        else if (obj instanceof Exprent) {
          currVars.add((Exprent)obj);
        }
      }

      // children statements
      for (Integer index : childVars) {
        Integer count = mapCount.get(index);
        if (count == null) {
          count = new Integer(0);
        }
        mapCount.put(index, new Integer(count.intValue() + 1));
      }

      condlst = getAllVars(currVars);
    }
    else {
      condlst = getAllVars(stat.getExprents());
    }

    // this statement
    for (VarExprent var : condlst) {
      mapCount.put(new Integer(var.getIndex()), new Integer(2));
    }


    HashSet<Integer> set = new HashSet<Integer>(mapCount.keySet());

    // put all variables defined in this statement into the set
    for (Entry<Integer, Integer> en : mapCount.entrySet()) {
      if (en.getValue().intValue() > 1) {
        mapVarDefStatements.put(en.getKey(), stat);
      }
    }

    mapStatementVars.put(stat.id, set);

    return set;
  }

  private static List<VarExprent> getAllVars(List<Exprent> lst) {

    List<VarExprent> res = new ArrayList<VarExprent>();
    List<Exprent> listTemp = new ArrayList<Exprent>();

    for (Exprent expr : lst) {
      listTemp.addAll(expr.getAllExprents(true));
      listTemp.add(expr);
    }

    for (Exprent exprent : listTemp) {
      if (exprent.type == Exprent.EXPRENT_VAR) {
        res.add((VarExprent)exprent);
      }
    }

    return res;
  }

  private static boolean setDefinition(Exprent expr, Integer index, Map<Integer,VarExprent> stats) {
    if (expr.type == Exprent.EXPRENT_ASSIGNMENT) {
      Exprent left = ((AssignmentExprent)expr).getLeft();
      if (left.type == Exprent.EXPRENT_VAR) {
        VarExprent var = (VarExprent)left;
        if (var.getIndex() == index.intValue()) {
          var.setDefinition(true);
          stats.put(index, var);
          return true;
        }
      }
    }
    return false;
  }

  private void setupLVTs(Statement stat) {
    if (stat == null || varproc.getLVT() == null) {
      return;
    }

    Map<Integer, LVTVariable> vars = varproc.getLVT().getVars(stat.getParentSequenceStat());

    if (stat.getExprents() == null) {
      for (Object obj : stat.getSequentialObjects()) {
        if (obj instanceof Statement) {
          setupLVTs((Statement)obj);
        }
        else if (obj instanceof Exprent) {
          setupLVTs((Exprent)obj, vars);
        }
      }
    }
    else {
      for (Exprent exp : stat.getExprents()) {
        setupLVTs(exp, vars);
      }
    }
  }

  private void setupLVTs(Exprent exprent, Map<Integer, LVTVariable> lvts) {
    if (exprent == null) {
      return;
    }
    List<Exprent> lst = exprent.getAllExprents(true);
    lst.add(exprent);

    for (Exprent expr : lst) {
      if (expr.type == Exprent.EXPRENT_VAR) {
        VarExprent var = (VarExprent)expr;
        int index = varproc.getRemapped(var.getIndex());
        LVTVariable lvt = lvts.get(index);
        if (lvt != null) {
          var.setLVT(lvt);
        }
      }
    }
  }

  private void mergeVars(Statement stat) {
    Map<VarVersionPair, VarVersionPair> remaps = new HashMap<VarVersionPair, VarVersionPair>();
    mergeVars(stat, remaps);
    if (!remaps.isEmpty()) {
      for (Entry<VarVersionPair, VarVersionPair> entry : remaps.entrySet()) {
        VarVersionPair end = entry.getKey();
        while (remaps.containsKey(end)) {
          end = remaps.get(end);
        }
        remaps.put(entry.getKey(), end);
      }
      remapVar(remaps, stat);
    }
  }
  private void mergeVars(Statement stat, Map<VarVersionPair, VarVersionPair> remaps) {
    if (stat.getExprents() == null) {
      for (Object obj : stat.getSequentialObjects()) {
        if (obj instanceof Statement) {
          mergeVars((Statement)obj, remaps);
        }
        else if (obj instanceof AssignmentExprent) {
          checkAssignment((AssignmentExprent)obj, remaps);
        }
      }
    }
    else {
      for (Exprent exp : stat.getExprents()) {
        if (exp instanceof AssignmentExprent) {
          checkAssignment((AssignmentExprent)exp, remaps);
        }
      }
    }
  }

  private void checkAssignment(AssignmentExprent exp, Map<VarVersionPair, VarVersionPair> remaps) {
    if (exp.getLeft().type != Exprent.EXPRENT_VAR) {
      return;
    }

    VarExprent left = (VarExprent)exp.getLeft();
    int index = varproc.getRemapped(left.getIndex());

    for (Exprent e : exp.getRight().getAllExprents(true)) {
      if (e.type == Exprent.EXPRENT_VAR) {
          VarExprent right = (VarExprent)e;
        if (varproc.getRemapped(right.getIndex()) == index) {
          if (!left.equals(right) && left.getIndex() > right.getIndex()) {
            remaps.put(new VarVersionPair(left), new VarVersionPair(right));
          }
          return;
        }
      }
    }
  }

  private static void remapVar(Map<VarVersionPair, VarVersionPair> remap, Statement stat) {
    if (remap == null || stat == null) {
      return;
    }

    if (stat.getExprents() == null) {
      for (Object obj : stat.getSequentialObjects()) {
        if (obj instanceof Statement) {
          remapVar(remap, (Statement)obj);
        }
        else if (obj instanceof Exprent) {
          remapVar(remap, (Exprent)obj);
        }
      }
    }
    else {
      for (Exprent exp : stat.getExprents()) {
        remapVar(remap, exp);
      }
    }
  }

  private static void remapVar(Map<VarVersionPair, VarVersionPair> remap, Exprent exprent) {
    if (exprent == null) {
      return;
    }
    List<Exprent> lst = exprent.getAllExprents(true);
    lst.add(exprent);

    for (Exprent expr : lst) {
      if (expr.type == Exprent.EXPRENT_VAR) {
        VarExprent var = (VarExprent)expr;
        VarVersionPair old = new VarVersionPair(var);
        VarVersionPair new_ = remap.get(old);
        if (new_ != null) {
          var.setIndex(new_.var);
          var.setVersion(new_.version);
        }
      }
    }
  }
}