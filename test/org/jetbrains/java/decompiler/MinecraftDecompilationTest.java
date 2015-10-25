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

import org.hamcrest.Matchers;
import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;
import org.jetbrains.java.decompiler.main.extern.IAbstractParameterRenamer;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.rels.MethodWrapper;
import org.jetbrains.java.decompiler.util.InterpreterUtil;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.*;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class MinecraftDecompilationTest {
  public static final Pattern p = Pattern.compile("func_(\\d+)_.*");
  private DecompilerTestFixture fixture;
  public static final int LOOPS = Integer.parseInt(System.getProperty("fftestloops","50"));
  public static final String OUTROOT = System.getProperty("fftestout","C:/TEMP/FFTEST");
  public static final String MD5IN = System.getProperty("ffmd5in",null);

  private static final String MC_JAR = "minecraft_ff_in.jar";
  @Before
  public void setUp() throws IOException {
    fixture = new DecompilerTestFixture() {
      @Override
      public File getRandomDir() {
        return new File(OUTROOT,"fftest");
      }
    };
    Map<String,Object> mcFFOptions = new HashMap<String,Object>() {{
        put(IFernflowerPreferences.DECOMPILE_INNER,"1");
        put(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES,"1");
        put(IFernflowerPreferences.ASCII_STRING_CHARACTERS,"1");
        put(IFernflowerPreferences.INCLUDE_ENTIRE_CLASSPATH, "1");
        put(IFernflowerPreferences.REMOVE_BRIDGE, "1");
        put(IFernflowerPreferences.REMOVE_SYNTHETIC, "1");
        put(IFernflowerPreferences.NEW_LINE_SEPARATOR, "1");
        put(IFernflowerPreferences.LITERALS_AS_IS, "0");
    }};
    fixture.setUp(mcFFOptions);
    DecompilerContext.setProperty("abstractparamrenamer", new IAbstractParameterRenamer() {
        @Override
        public String renameParameter(String orig, int index, MethodWrapper wrapper, int flags) {
            String result = orig;
            if ((flags & CodeConstants.ACC_ABSTRACT) != 0) {
                String methName = wrapper.methodStruct.getName();
                Matcher m = p.matcher(methName);
                if (m.matches()) {
                    result = String.format("p_%s_%d_", m.group(1),index);
                }
            }
            return result;
        }
    });
    if (!new File(fixture.getTestDataDir(), MC_JAR).exists()) {
      throw new RuntimeException("Missing "+MC_JAR+" in testData dir - aborting");
    }
  }

  @After
  public void tearDown() {
    fixture.tearDown();
    fixture = null;
  }

//  @Test
//  public void testDirectory() {
//    File classes = new File(fixture.getTempDir(), "classes");
//    unpack(new File(fixture.getTestDataDir(), "mc-fernflower-in.jar"), classes);
//
//    ConsoleDecompiler decompiler = fixture.getDecompiler();
//    decompiler.addSpace(classes, true);
//    decompiler.decompileContext();
//
//    compareDirectories(new File(fixture.getTestDataDir(), "bulk"), fixture.getTargetDir());
//  }

  @Test
  public void testJar() throws IOException {
    Map<String,String> md5s = new HashMap<String,String>();
    MessageDigest md5;
    try {
      md5 = MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException e1) {
      md5 = null;
    }
    System.out.printf("TEST SETUP: MD5: %s, OUTPUT: %s, LOOPS %d\n",MD5IN, OUTROOT, LOOPS);
    ConsoleDecompiler decompiler = fixture.getDecompiler();
    Map<String, String> valid = new HashMap<String,String>();
    if (MD5IN != null) {
        byte[] bytes = InterpreterUtil.getBytes(new File(MD5IN));
        String md5list = new String(bytes,"UTF-8");
        for (String line : md5list.split("\n")) {
            String[] parts = line.split(",");
            md5s.put(parts[0],parts[1]);
        }
    } else {

        System.out.println("Decompiling base");
        decompiler.addSpace(new File(fixture.getTestDataDir(), MC_JAR), true);
        decompiler.decompileContext();


        readJar(new File(fixture.getTargetDir(), MC_JAR),md5s,md5);
        File outmd5 = new File(fixture.getRandomDir(),"md5s.csv");
        FileWriter fos = new FileWriter(outmd5);
        for (Entry<String, String> md5sum : md5s.entrySet()) {
          fos.write(String.format("%s,%s\n", md5sum.getKey(),md5sum.getValue()));
        }
        fos.close();
    }
    Map<String, HashSet<String>> variants = new HashMap<String, HashSet<String>>();
    File outRoot = new File(OUTROOT,"ffbulk");
    outRoot.mkdirs();
    for (int x = 0; x < LOOPS; x++) {
      this.tearDown();
      this.setUp();
      System.out.printf("%d/%s Starting Decompile",x,LOOPS);
      decompiler = fixture.getDecompiler();
      decompiler.addSpace(new File(fixture.getTestDataDir(), MC_JAR), true);
      decompiler.decompileContext();
      System.gc();

      Map<String, String> data = readJar(new File(fixture.getTargetDir(), MC_JAR),null,null);

      for (Entry<String, String> e : data.entrySet()) {
        String fname = e.getKey();
        String found = e.getValue();
        String md5digest = md5digest(found, md5);
        String expected = null;
        if (md5digest.equals(md5s.get(fname)) && !valid.containsKey(fname)) {
          valid.put(fname, found);
          expected = found;
        } else if (valid.containsKey(fname)) {
          expected = valid.get(fname);
        }
        HashSet<String> set = variants.get(fname);
        if (set == null) {
          set = new HashSet<String>();
          set.add(md5s.get(fname));
          variants.put(fname, set);
        }
        if (!set.contains(md5digest)) {
            System.out.println("New Variant: " + fname);
            set.add(md5digest);
            System.out.println("Orig md5:"+md5s.get(fname));
            System.out.println("Variant md5: "+md5digest);
            writeFile(expected,fname,outRoot);
            writeFile(found,fname+"."+md5digest,outRoot);
        } else if (!md5digest.equals(md5s.get(fname))) {
          System.out.println("Existing Variant: " + fname);
          System.out.println("Variant md5: "+md5digest);
        }
      }
    }
//    compareDirectories(new File(fixture.getTestDataDir(), "bulk"), unpacked);
  }

  private static void writeFile(String fl, String path, File outRoot) {
      File out = new File(outRoot,path);
      out.getParentFile().mkdirs();
      try {
        FileWriter fw = new FileWriter(out.getAbsoluteFile());
          fw.write(fl);
          fw.close();
    } catch (IOException e) {
        e.printStackTrace();
    }
  }
  private static Map<String, String> readJar(File archive, Map<String, String> md5s, MessageDigest md5) {
    Map<String, String> ret = new HashMap<String, String>();
    try {
      ZipFile zip = new ZipFile(archive);
      try {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
          ZipEntry entry = entries.nextElement();
          if (!entry.isDirectory() && entry.getName().endsWith(".java")) {
            InputStream in = zip.getInputStream(entry);
            ByteArrayOutputStream out = new ByteArrayOutputStream((int)entry.getSize());
            InterpreterUtil.copyStream(in, out);
            out.close();
            in.close();
            String fileContent = new String(out.toByteArray());
            ret.put(entry.getName(), fileContent);
            if (md5!=null && md5s != null) {
                md5s.put(entry.getName(), md5digest(fileContent, md5));
            }
          }
        }
      }
      finally {
        zip.close();
      }
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
    return ret;
  }
  private static String md5digest(String input, MessageDigest md5) {
      md5.reset();
      try {
        return String.format("0%032x",new BigInteger(1,md5.digest(input.getBytes("UTF-8"))));
    } catch (UnsupportedEncodingException e) {
        return null;
    }
  }
}
