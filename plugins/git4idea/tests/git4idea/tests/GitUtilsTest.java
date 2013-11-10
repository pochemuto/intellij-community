/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package git4idea.tests;

import git4idea.GitUtil;
import org.junit.Assert;
import org.junit.Test;

/**
 * The test for utility classes
 */
public class GitUtilsTest {
  @Test
  public void testFormatLongRev() {
    Assert.assertEquals("0000000000000000", GitUtil.formatLongRev(0));
    Assert.assertEquals("fffffffffffffffe", GitUtil.formatLongRev(-2));
  }

  @Test
  public void testUnescapePath() throws Exception {
    Assert.assertEquals("Cyrillic folder", "папка/file.txt", GitUtil.unescapePath("\\320\\277\\320\\260\\320\\277\\320\\272\\320\\260/file.txt"));
    Assert.assertEquals("Cyrillic folder and filename", "папка/документ", GitUtil.unescapePath("\\320\\277\\320\\260\\320\\277\\320\\272\\320\\260/\\320\\264\\320\\276\\320\\272\\321\\203\\320\\274\\320\\265\\320\\275\\321\\202"));
  }
}
