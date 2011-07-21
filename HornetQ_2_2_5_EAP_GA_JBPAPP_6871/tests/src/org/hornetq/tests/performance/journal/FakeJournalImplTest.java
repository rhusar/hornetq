/*
 * Copyright 2009 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.hornetq.tests.performance.journal;

import org.hornetq.core.journal.SequentialFileFactory;
import org.hornetq.tests.unit.core.journal.impl.fakes.FakeSequentialFileFactory;

/**
 * 
 * A FakeJournalImplTest
 * 
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 *
 */
public class FakeJournalImplTest extends JournalImplTestUnit
{
   @Override
   protected SequentialFileFactory getFileFactory() throws Exception
   {
      return new FakeSequentialFileFactory();
   }
}
