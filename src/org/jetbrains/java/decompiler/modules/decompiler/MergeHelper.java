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
package org.jetbrains.java.decompiler.modules.decompiler;

import org.jetbrains.java.decompiler.code.cfg.BasicBlock;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.collectors.CounterContainer;
import org.jetbrains.java.decompiler.modules.decompiler.exps.ArrayExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.AssignmentExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.Exprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.FunctionExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.IfExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.InvocationExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.VarExprent;
import org.jetbrains.java.decompiler.modules.decompiler.stats.*;
import org.jetbrains.java.decompiler.util.ExprentUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MergeHelper {

  public static void enhanceLoops(Statement root) {

    while (enhanceLoopsRec(root)) ;

    SequenceHelper.condenseSequences(root);
  }

  private static boolean enhanceLoopsRec(Statement stat) {

    boolean res = false;

    for (Statement st : stat.getStats()) {
      if (st.getExprents() == null) {
        res |= enhanceLoopsRec(st);
      }
    }

    if (stat.type == Statement.TYPE_DO) {
      res |= enhanceLoop((DoStatement)stat);
    }

    return res;
  }

  private static boolean enhanceLoop(DoStatement stat) {

    int oldloop = stat.getLooptype();

    switch (oldloop) {
      case DoStatement.LOOP_DO:

        // identify a while loop
        if (matchWhile(stat)) {
          // identify a for loop - subtype of while
          matchFor(stat);
          // identify for each loop,
          matchForEach(stat);
        }
        else {
          // identify a do{}while loop
          matchDoWhile(stat);
        }

        break;
      case DoStatement.LOOP_WHILE:
        matchFor(stat);
        matchForEach(stat);
    }

    return (stat.getLooptype() != oldloop);
  }

  private static boolean matchDoWhile(DoStatement stat) {

    // search for an if condition at the end of the loop
    Statement last = stat.getFirst();
    while (last.type == Statement.TYPE_SEQUENCE) {
      last = last.getStats().getLast();
    }

    if (last.type == Statement.TYPE_IF) {
      IfStatement lastif = (IfStatement)last;
      if (lastif.iftype == IfStatement.IFTYPE_IF && lastif.getIfstat() == null) {
        StatEdge ifedge = lastif.getIfEdge();
        StatEdge elseedge = lastif.getAllSuccessorEdges().get(0);

        if ((ifedge.getType() == StatEdge.TYPE_BREAK && elseedge.getType() == StatEdge.TYPE_CONTINUE && elseedge.closure == stat
             && isDirectPath(stat, ifedge.getDestination())) ||
            (ifedge.getType() == StatEdge.TYPE_CONTINUE && elseedge.getType() == StatEdge.TYPE_BREAK && ifedge.closure == stat
             && isDirectPath(stat, elseedge.getDestination()))) {

          Set<Statement> set = stat.getNeighboursSet(StatEdge.TYPE_CONTINUE, Statement.DIRECTION_BACKWARD);
          set.remove(last);

          if (!set.isEmpty()) {
            return false;
          }


          stat.setLooptype(DoStatement.LOOP_DOWHILE);

          IfExprent ifexpr = (IfExprent)lastif.getHeadexprent().copy();
          if (ifedge.getType() == StatEdge.TYPE_BREAK) {
            ifexpr.negateIf();
          }
          if (stat.getConditionExprent() != null) {
            ifexpr.getCondition().addBytecodeOffsets(stat.getConditionExprent().bytecode);
          }
          ifexpr.getCondition().addBytecodeOffsets(lastif.getHeadexprent().bytecode);
          stat.setConditionExprent(ifexpr.getCondition());
          lastif.getFirst().removeSuccessor(ifedge);
          lastif.removeSuccessor(elseedge);

          // remove empty if
          if (lastif.getFirst().getExprents().isEmpty()) {
            removeLastEmptyStatement(stat, lastif);
          }
          else {
            lastif.setExprents(lastif.getFirst().getExprents());

            StatEdge newedge = new StatEdge(StatEdge.TYPE_CONTINUE, lastif, stat);
            lastif.addSuccessor(newedge);
            stat.addLabeledEdge(newedge);
          }

          if (stat.getAllSuccessorEdges().isEmpty()) {
            StatEdge edge = elseedge.getType() == StatEdge.TYPE_CONTINUE ? ifedge : elseedge;

            edge.setSource(stat);
            if (edge.closure == stat) {
              edge.closure = stat.getParent();
            }
            stat.addSuccessor(edge);
          }

          return true;
        }
      }
    }
    return false;
  }

  private static boolean matchWhile(DoStatement stat) {

    // search for an if condition at the entrance of the loop
    Statement first = stat.getFirst();
    while (first.type == Statement.TYPE_SEQUENCE) {
      first = first.getFirst();
    }

    // found an if statement
    if (first.type == Statement.TYPE_IF) {
      IfStatement firstif = (IfStatement)first;

      if (firstif.getFirst().getExprents().isEmpty()) {

        if (firstif.iftype == IfStatement.IFTYPE_IF) {
          if (firstif.getIfstat() == null) {
            StatEdge ifedge = firstif.getIfEdge();
            if (isDirectPath(stat, ifedge.getDestination())) {
              // exit condition identified
              stat.setLooptype(DoStatement.LOOP_WHILE);

              // negate condition (while header)
              IfExprent ifexpr = (IfExprent)firstif.getHeadexprent().copy();
              ifexpr.negateIf();
              if (stat.getConditionExprent() != null) {
                ifexpr.getCondition().addBytecodeOffsets(stat.getConditionExprent().bytecode);
              }
              ifexpr.getCondition().addBytecodeOffsets(firstif.getHeadexprent().bytecode);
              stat.setConditionExprent(ifexpr.getCondition());

              // remove edges
              firstif.getFirst().removeSuccessor(ifedge);
              firstif.removeSuccessor(firstif.getAllSuccessorEdges().get(0));

              if (stat.getAllSuccessorEdges().isEmpty()) {
                ifedge.setSource(stat);
                if (ifedge.closure == stat) {
                  ifedge.closure = stat.getParent();
                }
                stat.addSuccessor(ifedge);
              }

              // remove empty if statement as it is now part of the loop
              if (firstif == stat.getFirst()) {
                BasicBlockStatement bstat = new BasicBlockStatement(new BasicBlock(
                  DecompilerContext.getCounterContainer().getCounterAndIncrement(CounterContainer.STATEMENT_COUNTER)));
                bstat.setExprents(new ArrayList<Exprent>());
                stat.replaceStatement(firstif, bstat);
              }
              else {
                // precondition: sequence must contain more than one statement!
                Statement sequence = firstif.getParent();
                sequence.getStats().removeWithKey(firstif.id);
                sequence.setFirst(sequence.getStats().get(0));
              }

              return true;
            }
          }
          else {
            StatEdge elseedge = firstif.getAllSuccessorEdges().get(0);
            if (isDirectPath(stat, elseedge.getDestination())) {
              // exit condition identified
              stat.setLooptype(DoStatement.LOOP_WHILE);

              // no need to negate the while condition
              IfExprent ifexpr = (IfExprent)firstif.getHeadexprent().copy();
              if (stat.getConditionExprent() != null) {
                ifexpr.getCondition().addBytecodeOffsets(stat.getConditionExprent().bytecode);
              }
              ifexpr.getCondition().addBytecodeOffsets(firstif.getHeadexprent().bytecode);
              stat.setConditionExprent(ifexpr.getCondition());

              // remove edges
              StatEdge ifedge = firstif.getIfEdge();
              firstif.getFirst().removeSuccessor(ifedge);
              firstif.removeSuccessor(elseedge);

              if (stat.getAllSuccessorEdges().isEmpty()) {

                elseedge.setSource(stat);
                if (elseedge.closure == stat) {
                  elseedge.closure = stat.getParent();
                }
                stat.addSuccessor(elseedge);
              }

              if (firstif.getIfstat() == null) {
                BasicBlockStatement bstat = new BasicBlockStatement(new BasicBlock(
                  DecompilerContext.getCounterContainer().getCounterAndIncrement(CounterContainer.STATEMENT_COUNTER)));
                bstat.setExprents(new ArrayList<Exprent>());

                ifedge.setSource(bstat);
                bstat.addSuccessor(ifedge);

                stat.replaceStatement(firstif, bstat);
              }
              else {
                // replace the if statement with its content
                first.getParent().replaceStatement(first, firstif.getIfstat());

                // lift closures
                for (StatEdge prededge : elseedge.getDestination().getPredecessorEdges(StatEdge.TYPE_BREAK)) {
                  if (stat.containsStatementStrict(prededge.closure)) {
                    stat.addLabeledEdge(prededge);
                  }
                }

                LabelHelper.lowClosures(stat);
              }

              return true;
            }
          }
        }
      }
    }
    return false;
  }

  public static boolean isDirectPath(Statement stat, Statement endstat) {

    Set<Statement> setStat = stat.getNeighboursSet(Statement.STATEDGE_DIRECT_ALL, Statement.DIRECTION_FORWARD);
    if (setStat.isEmpty()) {
      Statement parent = stat.getParent();
      if (parent == null) {
        return false;
      }
      else {
        switch (parent.type) {
          case Statement.TYPE_ROOT:
            return endstat.type == Statement.TYPE_DUMMYEXIT;
          case Statement.TYPE_DO:
            return (endstat == parent);
          case Statement.TYPE_SWITCH:
            SwitchStatement swst = (SwitchStatement)parent;
            for (int i = 0; i < swst.getCaseStatements().size() - 1; i++) {
              Statement stt = swst.getCaseStatements().get(i);
              if (stt == stat) {
                Statement stnext = swst.getCaseStatements().get(i + 1);

                if (stnext.getExprents() != null && stnext.getExprents().isEmpty()) {
                  stnext = stnext.getAllSuccessorEdges().get(0).getDestination();
                }
                return (endstat == stnext);
              }
            }
          default:
            return isDirectPath(parent, endstat);
        }
      }
    }
    else {
      return setStat.contains(endstat);
    }
  }

  private static boolean matchFor(DoStatement stat) {

    Exprent lastDoExprent = null, initDoExprent = null;
    Statement lastData = null, preData = null;

    // get last exprent
    lastData = getLastDirectData(stat.getFirst());
    if (lastData == null || lastData.getExprents().isEmpty()) {
      return false;
    }

    List<Exprent> lstExpr = lastData.getExprents();
    lastDoExprent = lstExpr.get(lstExpr.size() - 1);

    boolean issingle = false;
    if (lstExpr.size() == 1) {  // single exprent
      if (lastData.getAllPredecessorEdges().size() > 1) { // break edges
        issingle = true;
      }
    }

    boolean haslast = issingle || (lastDoExprent.type == Exprent.EXPRENT_ASSIGNMENT ||
                                   lastDoExprent.type == Exprent.EXPRENT_FUNCTION);

    if (!haslast) {
      return false;
    }

    boolean hasinit = false;

    // search for an initializing exprent
    Statement current = stat;
    while (true) {
      Statement parent = current.getParent();
      if (parent == null) {
        break;
      }

      if (parent.type == Statement.TYPE_SEQUENCE) {
        if (current == parent.getFirst()) {
          current = parent;
        }
        else {
          preData = current.getNeighbours(StatEdge.TYPE_REGULAR, Statement.DIRECTION_BACKWARD).get(0);
          preData = getLastDirectData(preData);
          if (preData != null && !preData.getExprents().isEmpty()) {
            initDoExprent = preData.getExprents().get(preData.getExprents().size() - 1);
            if (initDoExprent.type == Exprent.EXPRENT_ASSIGNMENT) {
              hasinit = true;
            }
          }
          break;
        }
      }
      else {
        break;
      }
    }

    if ((hasinit && haslast) || issingle) {  // FIXME: issingle sufficient?

      Set<Statement> set = stat.getNeighboursSet(StatEdge.TYPE_CONTINUE, Statement.DIRECTION_BACKWARD);
      set.remove(lastData);

      if (!set.isEmpty()) {
        return false;
      }

      stat.setLooptype(DoStatement.LOOP_FOR);
      if (hasinit) {
        Exprent exp = preData.getExprents().remove(preData.getExprents().size() - 1);
        if (stat.getInitExprent() != null) {
          exp.addBytecodeOffsets(stat.getInitExprent().bytecode);
        }
        stat.setInitExprent(exp);
      }
      Exprent exp = lastData.getExprents().remove(lastData.getExprents().size() - 1);
      if (stat.getIncExprent() != null) {
        exp.addBytecodeOffsets(stat.getIncExprent().bytecode);
      }
      stat.setIncExprent(exp);
    }

    cleanEmptyStatements(stat, lastData);

    return true;
  }

  private static boolean matchForEach(DoStatement stat) {
    AssignmentExprent firstDoExprent = null, initDoExprent = null, initCopyExprent = null;
    Statement firstData, preData = null;

    // search for an initializing exprent
    Statement current = stat;
    while (true) {
      Statement parent = current.getParent();
      if (parent == null) {
        break;
      }

      if (parent.type == Statement.TYPE_SEQUENCE) {
        if (current == parent.getFirst()) {
          current = parent;
        }
        else {
          preData = current.getNeighbours(StatEdge.TYPE_REGULAR, Statement.DIRECTION_BACKWARD).get(0);
          preData = getLastDirectData(preData);
          if (preData != null && !preData.getExprents().isEmpty()) {
            Exprent exprent = preData.getExprents().get(preData.getExprents().size() - 1);
            if (exprent.type == Exprent.EXPRENT_ASSIGNMENT) {
              initDoExprent = (AssignmentExprent)exprent;
              if (preData.getExprents().size() >= 2) {
                exprent = preData.getExprents().get(preData.getExprents().size() - 2);
                if (exprent.type == Exprent.EXPRENT_ASSIGNMENT) {
                  initCopyExprent = (AssignmentExprent)exprent;
                }
              }
            }
          }
          break;
        }
      }
      else {
        break;
      }
    }

    firstData = getFirstDirectData(stat.getFirst());
    if (firstData != null && firstData.getExprents().get(0).type == Exprent.EXPRENT_ASSIGNMENT) {
      firstDoExprent = (AssignmentExprent)firstData.getExprents().get(0);
    }

    if (stat.getLooptype() == DoStatement.LOOP_WHILE && initDoExprent != null && firstDoExprent != null) {
      if (initDoExprent.type == Exprent.EXPRENT_ASSIGNMENT &&
          isIteratorCall(((AssignmentExprent)initDoExprent).getRight())) {

        if (!isHasNextCall(drillNots(stat.getConditionExprent())) ||
            firstDoExprent.type != Exprent.EXPRENT_ASSIGNMENT) {
          return false;
        }

        AssignmentExprent ass = (AssignmentExprent)firstDoExprent;
        if (!isNextCall(ass.getRight()) || ass.getLeft().type != Exprent.EXPRENT_VAR) {
          return false;
        }

        InvocationExprent next = (InvocationExprent)getUncast(ass.getRight());
        InvocationExprent hnext = (InvocationExprent)getUncast(drillNots(stat.getConditionExprent()));
        if (next.getInstance().type != Exprent.EXPRENT_VAR ||
            hnext.getInstance().type != Exprent.EXPRENT_VAR ||
            ExprentUtil.isVarReferenced((VarExprent)initDoExprent.getLeft(), stat, (VarExprent)next.getInstance(), (VarExprent)hnext.getInstance())) {
          return false;
        }

        InvocationExprent holder = (InvocationExprent)((AssignmentExprent)initDoExprent).getRight();

        holder.getInstance().addBytecodeOffsets(initDoExprent.bytecode);
        ass.getLeft().addBytecodeOffsets(firstDoExprent.bytecode);
        if (stat.getIncExprent() != null) {
          holder.getInstance().addBytecodeOffsets(stat.getIncExprent().bytecode);
        }
        if (stat.getInitExprent() != null) {
          ass.getLeft().addBytecodeOffsets(stat.getInitExprent().bytecode);
        }

        stat.setLooptype(DoStatement.LOOP_FOREACH);
        stat.setInitExprent(ass.getLeft());
        stat.setIncExprent(holder.getInstance());
        preData.getExprents().remove(initDoExprent);
        firstData.getExprents().remove(firstDoExprent);
      }
    }
    else if (stat.getLooptype() == DoStatement.LOOP_FOR) {
      if (isType(stat.getInitExprent(), Exprent.EXPRENT_ASSIGNMENT) &&
          isIteratorCall(((AssignmentExprent)stat.getInitExprent()).getRight()) &&
          isType(stat.getConditionExprent(), Exprent.EXPRENT_FUNCTION)) {

        Exprent exp = drillNots(stat.getConditionExprent());
        if (!isHasNextCall(exp) ||
            !isType(stat.getIncExprent(), Exprent.EXPRENT_ASSIGNMENT)) {
          return false;
        }

        AssignmentExprent itr = (AssignmentExprent)stat.getInitExprent();
        InvocationExprent hnext = (InvocationExprent)exp;
        if (itr.getLeft().type != Exprent.EXPRENT_VAR || hnext.getInstance().type != Exprent.EXPRENT_VAR) {
          return false;
        }

        AssignmentExprent ass = (AssignmentExprent)stat.getIncExprent();
        if (!isNextCall(ass.getRight()) || ass.getLeft().type != Exprent.EXPRENT_VAR) {

          if (firstDoExprent == null || !isNextCall(firstDoExprent.getRight()) ||
              firstDoExprent.getLeft().type != Exprent.EXPRENT_VAR) {
            return false;
          }

          InvocationExprent next = (InvocationExprent)getUncast(firstDoExprent.getRight());
          if (next.getInstance().type != Exprent.EXPRENT_VAR ||
              ExprentUtil.isVarReferenced((VarExprent)itr.getLeft(), stat, (VarExprent)next.getInstance(), (VarExprent)hnext.getInstance())) {
            return false;
          }

          //Move the inc exprent back to the end of the body and remove the .next call
          Statement last = getLastDirectData(stat.getFirst());
          InvocationExprent holder = (InvocationExprent)getUncast(((AssignmentExprent)stat.getInitExprent()).getRight());

          firstData.getExprents().remove(firstDoExprent);
          last.getExprents().add(stat.getIncExprent());

          firstDoExprent.getLeft().addBytecodeOffsets(stat.getInitExprent().bytecode);
          firstDoExprent.getLeft().addBytecodeOffsets(stat.getIncExprent().bytecode);
          firstDoExprent.getLeft().addBytecodeOffsets(firstDoExprent.bytecode);

          stat.setLooptype(DoStatement.LOOP_FOREACH);
          stat.setInitExprent(firstDoExprent.getLeft());
          stat.setIncExprent(holder.getInstance());
        }
        else {
          InvocationExprent next = (InvocationExprent)getUncast(ass.getRight());
          if (next.getInstance().type != Exprent.EXPRENT_VAR ||
              ExprentUtil.isVarReferenced((VarExprent)itr.getLeft(), stat, (VarExprent)next.getInstance(), (VarExprent)hnext.getInstance())) {
            return false;
          }

          InvocationExprent holder = (InvocationExprent)getUncast(((AssignmentExprent)stat.getInitExprent()).getRight());

          holder.getInstance().addBytecodeOffsets(stat.getInitExprent().bytecode);
          ass.getLeft().addBytecodeOffsets(stat.getIncExprent().bytecode);

          stat.setLooptype(DoStatement.LOOP_FOREACH);
          stat.setInitExprent(ass.getLeft());
          stat.setIncExprent(holder.getInstance());
        }
      }
      else if (initDoExprent != null && initDoExprent.getRight().type == Exprent.EXPRENT_FUNCTION) {
        if (firstDoExprent == null ||
            firstDoExprent.getRight().type != Exprent.EXPRENT_ARRAY ||
            firstDoExprent.getLeft().type != Exprent.EXPRENT_VAR ||
            !isType(stat.getIncExprent(), Exprent.EXPRENT_FUNCTION) ||
            !isType(stat.getInitExprent(), Exprent.EXPRENT_ASSIGNMENT)) {
          return false;
        }

        FunctionExprent funcRight = (FunctionExprent)initDoExprent.getRight();
        FunctionExprent funcInc = (FunctionExprent)stat.getIncExprent();
        ArrayExprent arr = (ArrayExprent)firstDoExprent.getRight();

        if (funcRight.getFuncType() != FunctionExprent.FUNCTION_ARRAY_LENGTH ||
            (funcInc.getFuncType() != FunctionExprent.FUNCTION_PPI && funcInc.getFuncType() != FunctionExprent.FUNCTION_IPP) ||
            arr.getIndex().type != Exprent.EXPRENT_VAR ||
            arr.getArray().type != Exprent.EXPRENT_VAR) {
            return false;
        }

        VarExprent index = (VarExprent)arr.getIndex();
        VarExprent array = (VarExprent)arr.getArray();
        VarExprent counter = (VarExprent)funcInc.getLstOperands().get(0);

        if (counter.getIndex() != index.getIndex() ||
            counter.getVersion() != index.getVersion()) {
          return false;
        }

        funcRight.getLstOperands().get(0).addBytecodeOffsets(initDoExprent.bytecode);
        funcRight.getLstOperands().get(0).addBytecodeOffsets(stat.getIncExprent().bytecode);
        firstDoExprent.getLeft().addBytecodeOffsets(firstDoExprent.bytecode);
        firstDoExprent.getLeft().addBytecodeOffsets(stat.getInitExprent().bytecode);

        stat.setLooptype(DoStatement.LOOP_FOREACH);
        stat.setInitExprent(firstDoExprent.getLeft());
        stat.setIncExprent(funcRight.getLstOperands().get(0));
        preData.getExprents().remove(initDoExprent);
        firstData.getExprents().remove(firstDoExprent);


        if (initCopyExprent != null && initCopyExprent.getLeft().type == Exprent.EXPRENT_VAR) {
          VarExprent copy = (VarExprent)initCopyExprent.getLeft();
          if (copy.getIndex() == array.getIndex() && copy.getVersion() == array.getVersion()) {
            preData.getExprents().remove(initCopyExprent);
            initCopyExprent.getRight().addBytecodeOffsets(initCopyExprent.bytecode);
            initCopyExprent.getRight().addBytecodeOffsets(stat.getIncExprent().bytecode);
            stat.setIncExprent(initCopyExprent.getRight());
          }
        }
      }
    }

    //cleanEmptyStatements(stat, firstData); //TODO: Look into this and see what it does...

    return true;
  }

  private static boolean isType(Exprent exp, int type) { //This is just a helper macro, Wish java had real macros.
    return exp != null && exp.type == type;
  }
  private static boolean isInvoke(Exprent exp, String cls, String method, String desc) {
    exp = getUncast(exp);
    if (!isType(exp,  Exprent.EXPRENT_INVOCATION)) {
      return false;
    }
    InvocationExprent invoc = (InvocationExprent)exp;
    if (cls != null && !cls.equals(invoc.getClassname())) {
      return false;
    }
    return method.equals(invoc.getName()) && desc.equals(invoc.getStringDescriptor());
  }
  private static Exprent drillNots(Exprent exp) {
    while (true) {
      if (exp.type == Exprent.EXPRENT_FUNCTION) {
        FunctionExprent fun = (FunctionExprent)exp;
        if (fun.getFuncType() == FunctionExprent.FUNCTION_BOOL_NOT) {
          exp = fun.getLstOperands().get(0);
        }
        else if (fun.getFuncType() == FunctionExprent.FUNCTION_EQ ||
                 fun.getFuncType() == FunctionExprent.FUNCTION_NE) {
          return fun.getLstOperands().get(0);
        }
        else {
          return null;
        }
      }
      else {
        return null;
      }
    }
  }

  private static void cleanEmptyStatements(DoStatement dostat, Statement stat) {
    if (stat != null && stat.getExprents().isEmpty()) {
      List<StatEdge> lst = stat.getAllSuccessorEdges();
      if (!lst.isEmpty()) {
        stat.removeSuccessor(lst.get(0));
      }
      removeLastEmptyStatement(dostat, stat);
    }
  }
  private static void removeLastEmptyStatement(DoStatement dostat, Statement stat) {

    if (stat == dostat.getFirst()) {
      BasicBlockStatement bstat = new BasicBlockStatement(new BasicBlock(
        DecompilerContext.getCounterContainer().getCounterAndIncrement(CounterContainer.STATEMENT_COUNTER)));
      bstat.setExprents(new ArrayList<Exprent>());
      dostat.replaceStatement(stat, bstat);
    }
    else {
      for (StatEdge edge : stat.getAllPredecessorEdges()) {
        edge.getSource().changeEdgeType(Statement.DIRECTION_FORWARD, edge, StatEdge.TYPE_CONTINUE);

        stat.removePredecessor(edge);
        edge.getSource().changeEdgeNode(Statement.DIRECTION_FORWARD, edge, dostat);
        dostat.addPredecessor(edge);

        dostat.addLabeledEdge(edge);
      }

      // parent is a sequence statement
      stat.getParent().getStats().removeWithKey(stat.id);
    }
  }

  private static Statement getLastDirectData(Statement stat) {

    if (stat.getExprents() != null) {
      return stat;
    }

    for (int i = stat.getStats().size() - 1; i >= 0; i--) {
      Statement tmp = getLastDirectData(stat.getStats().get(i));
      if (tmp == null || !tmp.getExprents().isEmpty()) {
        return tmp;
      }
    }

    return null;
  }

  private static Statement getFirstDirectData(Statement stat) {
    if (stat.getExprents() != null && !stat.getExprents().isEmpty()) {
      return stat;
    }

    for (Statement tmp : stat.getStats()) {
      Statement ret = getFirstDirectData(tmp);
      if (ret != null) {
        return ret;
      }
    }
    return null;
  }

  private static Exprent getUncast(Exprent exp) {
    if (exp.type == Exprent.EXPRENT_FUNCTION) {
      FunctionExprent func = (FunctionExprent)exp;
      if (func.getFuncType() == FunctionExprent.FUNCTION_CAST) {
        return getUncast(func.getLstOperands().get(0));
      }
    }
    return exp;
  }

  private static boolean isIteratorCall(Exprent exp) {
    return isInvoke(exp, null, "iterator",     "()Ljava/util/Iterator;"    ) ||
           isInvoke(exp, null, "listIterator", "()Ljava/util/ListIterator;");
  }

  private static boolean isHasNextCall(Exprent exp) {
    return isInvoke(exp, "java/util/Iterator",     "hasNext", "()Z") ||
           isInvoke(exp, "java/util/ListIterator", "hasNext", "()Z");
  }

  private static boolean isNextCall(Exprent exp) {
    return isInvoke(exp, "java/util/Iterator",     "next", "()Ljava/lang/Object;") ||
           isInvoke(exp, "java/util/ListIterator", "next", "()Ljava/lang/Object;");
  }
}
