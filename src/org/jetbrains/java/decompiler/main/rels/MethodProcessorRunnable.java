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
package org.jetbrains.java.decompiler.main.rels;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.code.InstructionSequence;
import org.jetbrains.java.decompiler.code.cfg.ControlFlowGraph;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.collectors.BytecodeMappingTracer;
import org.jetbrains.java.decompiler.main.collectors.CounterContainer;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.modules.code.DeadCodeHelper;
import org.jetbrains.java.decompiler.modules.decompiler.*;
import org.jetbrains.java.decompiler.modules.decompiler.deobfuscator.ExceptionDeobfuscator;
import org.jetbrains.java.decompiler.modules.decompiler.exps.AssignmentExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.Exprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.FunctionExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.IfExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.VarExprent;
import org.jetbrains.java.decompiler.modules.decompiler.stats.BasicBlockStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.DoStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.DummyExitStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.IfStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.RootStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarProcessor;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.StructMethod;

import java.io.IOException;
import java.util.BitSet;

public class MethodProcessorRunnable implements Runnable {

  private static RootStatement currentRoot;

  private static VarProcessor vp;

  public final Object lock = new Object();

  private final StructMethod method;
  private final VarProcessor varProc;
  private final DecompilerContext parentContext;

  private volatile RootStatement root;
  private volatile Throwable error;
  private volatile boolean finished = false;

  public MethodProcessorRunnable(StructMethod method, VarProcessor varProc, DecompilerContext parentContext) {
    this.method = method;
    this.varProc = varProc;
    this.parentContext = parentContext;
  }

  @Override
  public void run() {
    DecompilerContext.setCurrentContext(parentContext);

    error = null;
    root = null;

    try {
      root = codeToJava(method, varProc);
    }
    catch (ThreadDeath ex) {
      throw ex;
    }
    catch (Throwable ex) {
      error = ex;
    }
    finally {
      DecompilerContext.setCurrentContext(null);
    }

    finished = true;
    synchronized (lock) {
      lock.notifyAll();
    }
  }

  public static RootStatement codeToJava(StructMethod mt, VarProcessor varProc) throws IOException {
    StructClass cl = mt.getClassStruct();

    boolean isInitializer = CodeConstants.CLINIT_NAME.equals(mt.getName()); // for now static initializer only

    mt.expandData();
    InstructionSequence seq = mt.getInstructionSequence();
    ControlFlowGraph graph = new ControlFlowGraph(seq);

    DeadCodeHelper.removeDeadBlocks(graph);
    graph.inlineJsr(mt);

    // TODO: move to the start, before jsr inlining
    DeadCodeHelper.connectDummyExitBlock(graph);

    DeadCodeHelper.removeGotos(graph);

    ExceptionDeobfuscator.removeCircularRanges(graph);

    ExceptionDeobfuscator.restorePopRanges(graph);

    if (DecompilerContext.getOption(IFernflowerPreferences.REMOVE_EMPTY_RANGES)) {
      ExceptionDeobfuscator.removeEmptyRanges(graph);
    }

    if (DecompilerContext.getOption(IFernflowerPreferences.NO_EXCEPTIONS_RETURN)) {
      // special case: single return instruction outside of a protected range
      DeadCodeHelper.incorporateValueReturns(graph);
    }

    //		ExceptionDeobfuscator.restorePopRanges(graph);
    ExceptionDeobfuscator.insertEmptyExceptionHandlerBlocks(graph);

    DeadCodeHelper.mergeBasicBlocks(graph);

    DecompilerContext.getCounterContainer().setCounter(CounterContainer.VAR_COUNTER, mt.getLocalVariables());

    if (ExceptionDeobfuscator.hasObfuscatedExceptions(graph)) {
      DecompilerContext.getLogger().writeMessage("Heavily obfuscated exception ranges found!", IFernflowerLogger.Severity.WARN);
    }

    RootStatement root = DomHelper.parseGraph(graph, mt);
    MethodProcessorRunnable.currentRoot = root;
    MethodProcessorRunnable.vp = varProc;
    FinallyProcessor fProc = new FinallyProcessor(varProc);
    while (fProc.iterateGraph(mt, root, graph)) {
      root = DomHelper.parseGraph(graph, mt);
    }

    // remove synchronized exception handler
    // not until now because of comparison between synchronized statements in the finally cycle
    DomHelper.removeSynchronizedHandler(root);

    //		LabelHelper.lowContinueLabels(root, new HashSet<StatEdge>());

    SequenceHelper.condenseSequences(root);

    ClearStructHelper.clearStatements(root);

    ExprProcessor proc = new ExprProcessor();
    proc.processStatement(root, cl);

    SequenceHelper.condenseSequences(root);

    while (true) {
      StackVarsProcessor stackProc = new StackVarsProcessor();
      stackProc.simplifyStackVars(root, mt, cl);

      varProc.setVarVersions(root);

      if (!new PPandMMHelper(varProc).findPPandMM(root)) {
        break;
      }
    }

    while (true) {
      LabelHelper.cleanUpEdges(root);

      while (true) {
        if (EliminateLoopsHelper.eliminateLoops(root, cl)) {
          continue;
        }

        if (LoopExtractHelper.extractLoops(root)) {
          continue;
        }

        MergeHelper.enhanceLoops(root);

        if (!IfHelper.mergeAllIfs(root)) {
          break;
        }
      }

      if (DecompilerContext.getOption(IFernflowerPreferences.IDEA_NOT_NULL_ANNOTATION)) {
        if (IdeaNotNullHelper.removeHardcodedChecks(root, mt)) {
          SequenceHelper.condenseSequences(root);

          StackVarsProcessor stackProc = new StackVarsProcessor();
          stackProc.simplifyStackVars(root, mt, cl);

          varProc.setVarVersions(root);
        }
      }

      LabelHelper.identifyLabels(root);

      if (InlineSingleBlockHelper.inlineSingleBlocks(root)) {
        continue;
      }

      // initializer may have at most one return point, so no transformation of method exits permitted
      if (isInitializer || !ExitHelper.condenseExits(root)) {
        break;
      }

      // FIXME: !!
      //			if(!EliminateLoopsHelper.eliminateLoops(root)) {
      //				break;
      //			}
    }

    ExitHelper.removeRedundantReturns(root);

    SecondaryFunctionsHelper.identifySecondaryFunctions(root);

    SynchronizedHelper.cleanSynchronizedVar(root);

    varProc.setVarDefinitions(root);

    // must be the last invocation, because it makes the statement structure inconsistent
    // FIXME: new edge type needed
    LabelHelper.replaceContinueWithBreak(root);

    mt.releaseResources();

    return root;
  }

  public RootStatement getResult() throws Throwable {
    Throwable t = error;
    if (t != null) throw t;
    return root;
  }

  public boolean isFinished() {
    return finished;
  }

  public static void printMethod(String desc) {
      printMethod(currentRoot, desc, vp);
  }
  public static void printMethod(Statement root, String name, VarProcessor varProc) {
    System.out.println(name + " {");
    if (root == null || root.getSequentialObjects() == null) {
        System.out.println("}");
        return;
    }
    for (Object obj : root.getSequentialObjects()) {
      if (obj instanceof Statement) {
        printStatement((Statement)obj, "  ",varProc);
      } else if (obj == null) {
          System.out.println("  null");
      } else {
          System.out.println("  " + obj.getClass().getSimpleName());
      }
    }
    if (root instanceof RootStatement) {
      printStatement(((RootStatement)root).getDummyExit(), "  ",varProc);
    }
    System.out.println("}");
  }

  public static void getOffset(Statement st, BitSet values) {
    if (st instanceof DummyExitStatement && ((DummyExitStatement)st).bytecode != null)
      values.or(((DummyExitStatement)st).bytecode);
    if (st.getExprents() != null) {
      for (Exprent e : st.getExprents()) {
        e.getBytecodeRange(values);
      }
    } else {
      for (Object obj : st.getSequentialObjects()) {
        if (obj == null) {
          continue;
        }
        if (obj instanceof Statement) {
          getOffset((Statement)obj, values);
        } else if (obj instanceof Exprent) {
          ((Exprent)obj).getBytecodeRange(values);
        } else {
          System.out.println("WTF?" + obj.getClass());
        }
      }
    }
  }

  private static void printStatement(Statement statement, String indent, VarProcessor varProc) {
    BitSet values = new BitSet();
    getOffset(statement, values);
    int start = values.nextSetBit(0);
    int end = values.length()-1;

    System.out.print(indent + "{" + statement.getClass().getSimpleName() + "}:" + statement.id + " (" + start + ", " + end + ") " + statement.getClass().getSimpleName());
    if (statement.type == Statement.TYPE_DO) {
        System.out.print(" t:"+((DoStatement)statement).getLooptype());
    } else if (statement.type == Statement.TYPE_BASICBLOCK) {
        System.out.print(" i:"+((BasicBlockStatement)statement).getBlock().toStringOldIndices().replaceAll("\n", ";").replaceAll("\r", ""));
    }
    System.out.println();
    for (StatEdge edge : statement.getAllSuccessorEdges())
      System.out.println(indent + " Dest: " + edge.getDestination());

    if (statement.getExprents() != null) {
      for(Exprent exp : statement.getExprents()) {
          System.out.println(printExprent(indent + "  ", exp,varProc));
      }
    }
    indent += "  ";
    for (Object obj : statement.getSequentialObjects()) {
      if (obj == null) {
        continue;
      }
      if (obj instanceof Statement) {
        printStatement((Statement)obj, indent,varProc);
      } else if (obj instanceof Exprent) {
          System.out.println(printExprent(indent, (Exprent) obj, varProc));
      } else {
        System.out.println(indent + obj.getClass().getSimpleName());
      }
    }
  }
  private static String printExprent(String indent, Exprent exp, VarProcessor varProc) {
      StringBuffer sb = new StringBuffer();
      sb.append(indent);
      BitSet values = new BitSet();
      exp.getBytecodeRange(values);
      sb.append("(").append(values.nextSetBit(0)).append(", ").append(values.length()-1).append(") ");
      sb.append(exp.getClass().getSimpleName());
      sb.append(" ").append(exp.id).append(" ");
      if (exp instanceof VarExprent) {
          VarExprent varExprent = (VarExprent)exp;
        int currindex = varExprent.getIndex();
        int origindex = varProc == null ? -2 : varProc.getRemapped(currindex);
        sb.append("[").append(currindex).append(":").append(origindex).append(", ").append(varExprent.isStack()).append("]");
        if (varProc != null && varProc.getLVT() != null)
          sb.append(varProc.getLVT().getCandidates(origindex));
      } else if (exp instanceof AssignmentExprent) {
        AssignmentExprent assignmentExprent = (AssignmentExprent)exp;
        sb.append("{").append(printExprent(" ",assignmentExprent.getLeft(),varProc)).append(" =").append(printExprent(" ",assignmentExprent.getRight(),varProc)).append("}");
      } else if (exp instanceof IfExprent) {
        sb.append(" ").append(exp.toJava(0, new BytecodeMappingTracer()));
      } else if (exp instanceof FunctionExprent) {
        sb.append(" ").append(exp.toJava(0, new BytecodeMappingTracer()));
      }
      return sb.toString();
  }
}
