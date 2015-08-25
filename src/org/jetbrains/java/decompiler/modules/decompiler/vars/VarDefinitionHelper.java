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
import org.jetbrains.java.decompiler.main.collectors.CounterContainer;
import org.jetbrains.java.decompiler.main.collectors.VarNamesCollector;
import org.jetbrains.java.decompiler.main.rels.MethodProcessorRunnable;
import org.jetbrains.java.decompiler.modules.decompiler.exps.AssignmentExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.Exprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.VarExprent;
import org.jetbrains.java.decompiler.modules.decompiler.stats.CatchAllStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.CatchStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.DoStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;

import java.util.*;
import java.util.Map.Entry;

public class VarDefinitionHelper {

  private final HashMap<Integer, Statement> mapVarDefStatements;

  // statement.id, defined vars
  private final HashMap<Integer, HashSet<Integer>> mapStatementVars;

  private final HashSet<Integer> implDefVars;

  private final VarProcessor varproc;

  private final CounterContainer counters = DecompilerContext.getCounterContainer();

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
      varproc.setVarName(new VarVersionPair(varindex, 0), vc.getFreeName(varindex));

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

    splitVaribles(root, "");

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
          varproc.setVarName(new VarVersionPair(var), vc.getFreeName(var.getIndex()));
          var.setDefinition(true);
        }
      }

      stack.addAll(st.getStats());
    }

    initStatement(root);
  }

  public void setVarDefinitions() {

    VarNamesCollector vc = DecompilerContext.getVarNamesCollector();

    for (Entry<Integer, Statement> en : mapVarDefStatements.entrySet()) {
      Statement stat = en.getValue();
      Integer index = en.getKey();

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

          if (dstat.getInitExprent() != null && setDefinition(dstat.getInitExprent(), index)) {
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

        if (setDefinition(expr, index)) {
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
          BitSet values = new BitSet();
          MethodProcessorRunnable.getOffset(stat, values);
          int start = values.nextSetBit(0);
          int end = values.length()-1;
          List<LVTVariable> vars = varproc.getLVT().getVars(index, start, end);
          if (vars != null) {
            if (vars.size() == 1) {
              var.setLVT(vars.get(0));
            }
            // ToDo: If this is >1 then we need to decrease the scope of these variables.
            //if this is = 0 and we have lvts for this... then we need to expand the scope...
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

  private static boolean setDefinition(Exprent expr, Integer index) {
    if (expr.type == Exprent.EXPRENT_ASSIGNMENT) {
      Exprent left = ((AssignmentExprent)expr).getLeft();
      if (left.type == Exprent.EXPRENT_VAR) {
        VarExprent var = (VarExprent)left;
        if (var.getIndex() == index.intValue()) {
          var.setDefinition(true);
          return true;
        }
      }
    }
    return false;
  }

  //We merge variables together earlier to make ++ and -- work, we need to now
  // split them using the LVT data and full method structure into separate versions.
  // this also allows us to re-index/version variables that were not done
  // before such as exceptions.
  // Return value of referenced variables
  //  The boolean is 'isAssignment', true if first reference is assignment, false if just reference.
  private Map<VarVersionPair, Boolean> splitVaribles(Statement stat, String indent) {
    Map<VarVersionPair, Boolean> vars = new HashMap<VarVersionPair, Boolean>();

    //BitSet values = new BitSet();
    //MethodProcessorRunnable.getOffset(stat, values);
    //int start = values.nextSetBit(0);
    //int end = values.length()-1;
    //System.out.println(indent + stat.getClass().getSimpleName() + " (" + start +", " + end + ")");

    if (stat.type == Statement.TYPE_DO) {
      DoStatement dost = (DoStatement)stat;
      if (dost.getLooptype() == DoStatement.LOOP_FOREACH) {
        vars.put(new VarVersionPair((VarExprent)dost.getInitExprent()), true);
        splitExprent(dost.getIncExprent(), vars, indent);
      }
      else if (dost.getLooptype() == DoStatement.LOOP_FOR) {
        splitExprent(dost.getInitExprent(), vars, indent);
        splitExprent(dost.getConditionExprent(), vars, indent);
        splitExprent(dost.getIncExprent(), vars, indent);
      }
      else if (dost.getLooptype() == DoStatement.LOOP_WHILE) {
        splitExprent(dost.getConditionExprent(), vars, indent);
      }
    }

    if (stat.getExprents() == null) {
      List<Statement> stats = stat.getStats();
      List<Map<VarVersionPair, Boolean>> stat_vars = new ArrayList<Map<VarVersionPair, Boolean>>(stats.size());

      for (Statement st : stats) {
        stat_vars.add(splitVaribles(st, "  " + indent));
      }

      for (int x = 0; x < stats.size(); x++) {
        switch (stats.get(x).type) {
          case Statement.TYPE_DO: {
            DoStatement dost = (DoStatement)stats.get(x);
            VarVersionPair init = extractVar(dost.getInitExprent());
            if (init != null && (dost.getLooptype() == DoStatement.LOOP_FOR || dost.getLooptype()== DoStatement.LOOP_FOREACH)) {
              if (safeToRename(init, stats, stat_vars, x)) {
                VarVersionPair newIndex = new VarVersionPair(counters.getCounterAndIncrement(CounterContainer.VAR_COUNTER), 0);
                remapVar(init, newIndex, stats.get(x), stat_vars.get(x));
              }
            }
            break;
          }
        }
      }

      for (Map<VarVersionPair, Boolean> var : stat_vars) {
        for (Entry<VarVersionPair, Boolean> entry : var.entrySet()) {
          if (!vars.containsKey(entry.getKey())) {
            vars.put(entry.getKey(), entry.getValue());
          }
        }
      }
    }
    else {
      for (Exprent e : stat.getExprents()) {
        splitExprent(e, vars, indent);
      }
    }

    if (stat.type == Statement.TYPE_DO) {
      DoStatement dost = (DoStatement)stat;
      if (dost.getLooptype() == DoStatement.LOOP_DOWHILE) {
        splitExprent(dost.getConditionExprent(), vars, indent);
      }
    }

    //for (Map.Entry<VarVersionPair, Boolean> entry : vars.entrySet()) {
    //  System.out.println(indent + "  " + (entry.getValue() ? "ass " : "ref ") + entry.getKey());
    //}

    return vars;
  }

  private static void splitExprent(Exprent e, Map<VarVersionPair, Boolean> map, String indent) {
    if (e == null)
      return;
    if (e.type == Exprent.EXPRENT_ASSIGNMENT && ((AssignmentExprent)e).getLeft().type == Exprent.EXPRENT_VAR) {
      VarVersionPair var = new VarVersionPair((VarExprent)((AssignmentExprent)e).getLeft());
      if (!map.containsKey(var)) {
        map.put(var, true);
      }
      splitExprent(((AssignmentExprent)e).getRight(), map, indent);
    }
    else {
      for (VarVersionPair var : e.getAllVariables()) {
        if (!map.containsKey(var)) {
          map.put(var, false);
        }
      }
    }
  }

  private static VarVersionPair extractVar(Exprent exp) {
    if (exp == null) {
      return null;
    }
    if (exp.type == Exprent.EXPRENT_ASSIGNMENT && ((AssignmentExprent)exp).getLeft().type == Exprent.EXPRENT_VAR) {
      return new VarVersionPair((VarExprent)((AssignmentExprent)exp).getLeft());
    }
    else if (exp.type == Exprent.EXPRENT_VAR) {
      return new VarVersionPair((VarExprent)exp);
    }
    return null;
  }

  private static boolean safeToRename(VarVersionPair var, List<Statement> stats, List<Map<VarVersionPair, Boolean>> list, int index) {
    for (int x = index + 1; x < list.size(); x++) {
      Map<VarVersionPair, Boolean> map = list.get(x);
      if (map.containsKey(var)) {
        return map.get(var);
      }
    }
    return false;
  }

  private void remapVar(VarVersionPair from, VarVersionPair to, Statement stat, Map<VarVersionPair, Boolean> stats) {
    remapVar(from, to, stat);
    varproc.copyVarInfo(from, to);
    stats.put(to, true);
    stats.remove(from);
  }

  private static void remapVar(VarVersionPair from, VarVersionPair to, Statement stat) {
    if (stat == null) {
      return;
    }
    if (stat.getExprents() == null) {
      for (Object obj : stat.getSequentialObjects()) {
        if (obj instanceof Statement) {
          remapVar(from, to, (Statement)obj);
        }
        else if (obj instanceof Exprent) {
          remapVar(from, to, (Exprent)obj);
        }
      }
    }
    else {
      for (Exprent exp : stat.getExprents()) {
        remapVar(from, to, exp);
      }
    }
  }

  private static void remapVar(VarVersionPair from, VarVersionPair to, Exprent exprent) {
    if (exprent == null) {
      return;
    }
    List<Exprent> lst = exprent.getAllExprents(true);
    lst.add(exprent);

    for (Exprent expr : lst) {
      if (expr.type == Exprent.EXPRENT_VAR) {
        VarExprent var = (VarExprent)expr;
        if (var.getIndex() == from.var && var.getVersion() == from.version) {
          var.setIndex(to.var);
          var.setVersion(to.version);
        }
      }
    }
  }

  private void setupLVTs(Statement stat) {
    if (stat == null || varproc.getLVT() != null) {
      return;
    }

    Map<Integer, LVTVariable> vars = varproc.getLVT().getVars(stat);
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
}
