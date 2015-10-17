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
package org.jetbrains.java.decompiler.struct.attr;

import org.jetbrains.java.decompiler.struct.consts.ConstantPool;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/*
 * InnerClasses_attribute {
 *   u2 attribute_name_index;
 *   u4 attribute_length;
 *   u2 number_of_classes;
 *   {   u2 inner_class_info_index;
 *       u2 outer_class_info_index;
 *       u2 inner_name_index;
 *       u2 inner_class_access_flags;
 *   } classes[number_of_classes];
 * }
 */

public class StructInnerClassesAttribute extends StructGeneralAttribute {
	private List<InnerClassInfo> entries;
	private List<int[]> classEntries;
	private List<String[]> stringEntries;

	@Override
	public void initContent(ConstantPool pool) throws IOException {
		DataInputStream data = stream();

		int len = data.readUnsignedShort();
		if (len > 0) {
			entries = new ArrayList<InnerClassInfo>();
			classEntries = new ArrayList<int[]>(len);
			stringEntries = new ArrayList<String[]>(len);

			for (int i = 0; i < len; i++) {
				entries.add(new InnerClassInfo(data, pool));
				int[] classEntry = new int[4];
				for (int j = 0; j < 4; j++) {
					classEntry[j] = data.readUnsignedShort();
				}
				classEntries.add(classEntry);

				// inner name, enclosing class, original simple name
				String[] stringEntry = new String[3];
				stringEntry[0] = pool.getPrimitiveConstant(classEntry[0]).getString();
				if (classEntry[1] != 0) {
					stringEntry[1] = pool.getPrimitiveConstant(classEntry[1]).getString();
				}
				if (classEntry[2] != 0) {
					stringEntry[2] = pool.getPrimitiveConstant(classEntry[2]).getString();
				}
				stringEntries.add(stringEntry);
			}
		} else {
			entries = Collections.emptyList();
			classEntries = Collections.emptyList();
			stringEntries = Collections.emptyList();
		}
	}

	public List<InnerClassInfo> getEntries() {
		return entries;
	}

	public List<int[]> getClassEntries() {
		return classEntries;
	}

	public List<String[]> getStringEntries() {
		return stringEntries;
	}

	public static class InnerClassInfo {
		public String inner_class;
		public String outer_class;
		public String inner_name;
		public int access;

		private InnerClassInfo(DataInputStream data, ConstantPool pool) throws IOException {
			this.inner_class = readString(pool, data.readUnsignedShort());
			this.outer_class = readString(pool, data.readUnsignedShort());
			this.inner_name = readString(pool, data.readUnsignedShort());
			this.access = data.readUnsignedShort();
		}

		private String readString(ConstantPool pool, int index) {
			return index == 0 ? null : pool.getPrimitiveConstant(index).getString();
		}
	}
}
