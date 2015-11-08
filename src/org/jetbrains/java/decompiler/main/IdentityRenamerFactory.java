package org.jetbrains.java.decompiler.main;

import java.util.Map;

import org.jetbrains.java.decompiler.main.extern.IVariableNameProvider;
import org.jetbrains.java.decompiler.main.extern.IVariableNamingFactory;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionPair;
import org.jetbrains.java.decompiler.struct.StructMethod;

public class IdentityRenamerFactory implements IVariableNamingFactory, IVariableNameProvider {
  @Override
  public IVariableNameProvider createFactory(StructMethod method) {
    return this;
  }

  @Override
  public String renameAbstractParameter(String abstractParam, int index) {
    return abstractParam;
  }

  @Override
  public Map<VarVersionPair, String> rename(Map<VarVersionPair, String> variables) {
    return null;
  }
  @Override
  public void addParentContext(IVariableNameProvider renamer) {
  }
}
