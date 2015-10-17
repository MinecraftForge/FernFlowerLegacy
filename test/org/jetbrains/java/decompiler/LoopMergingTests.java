package org.jetbrains.java.decompiler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.junit.Test;

public class LoopMergingTests extends SingleClassesTestBase {
    @SuppressWarnings("serial")
    @Override
    protected Map<String, Object> getDecompilerOptions() {
      return new HashMap<String, Object>() {{
        put(IFernflowerPreferences.USE_DEBUG_LINE_NUMBERS, "1");
      }};
    }

    @Override
      public void setUp() throws IOException {
          super.setUp();
          fixture.cleanup = false;
      }

    @Test
    public void testLoopMerging() {
        doTest("pkg/TestLoopMerging");
    }

}
