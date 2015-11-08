package org.jetbrains.java.decompiler.main.extern;

import java.util.Map;

import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionPair;

public interface IVariableNameProvider {
  public Map<VarVersionPair,String> rename(Map<VarVersionPair,String> variables);
  public String renameAbstractParameter(String abstractParam, int index);
  public void addParentContext(IVariableNameProvider renamer);
}
