package org.jetbrains.java.decompiler.main.extern;

import org.jetbrains.java.decompiler.struct.StructMethod;

public interface IVariableNamingFactory {
  public IVariableNameProvider createFactory(StructMethod structMethod);
}
