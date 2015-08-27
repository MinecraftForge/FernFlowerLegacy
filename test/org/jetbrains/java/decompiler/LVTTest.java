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
package org.jetbrains.java.decompiler;

import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class LVTTest extends SingleClassesTestBase {
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
  @Test public void testMatchLM() { doTest("pkg/TestLexManosLVT"); }
  @Test public void testMatch1() { doTest("pkg/TestLVT"); }
  @Test public void testMatch2() { doTest("pkg/TestLVTScoping"); }
}
