package org.jetbrains.java.decompiler.main.extern;

import org.jetbrains.java.decompiler.main.rels.MethodWrapper;

public interface IAbstractParameterRenamer {
  public String renameParameter(String orig, int index, MethodWrapper wrapper, int flags);
}
