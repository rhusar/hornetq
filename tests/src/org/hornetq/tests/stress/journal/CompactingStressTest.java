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

package org.hornetq.tests.stress.journal;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import junit.framework.Assert;

import org.hornetq.api.core.HornetQException;
import org.hornetq.api.core.Message;
import org.hornetq.api.core.client.ClientConsumer;
import org.hornetq.api.core.client.ClientMessage;
import org.hornetq.api.core.client.ClientProducer;
import org.hornetq.api.core.client.ClientSession;
import org.hornetq.api.core.client.ClientSessionFactory;
import org.hornetq.core.asyncio.impl.AsynchronousFileImpl;
import org.hornetq.core.config.Configuration;
import org.hornetq.core.config.impl.ConfigurationImpl;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.core.server.JournalType;
import org.hornetq.tests.util.ServiceTestBase;

/**
 * A CompactingTest
 *
 * @author <mailto:clebert.suconic@jboss.org">Clebert Suconic</a>
 *
 *
 */
public class CompactingStressTest extends ServiceTestBase
{

   // Constants -----------------------------------------------------

   // Attributes ----------------------------------------------------

   private static final String AD1 = "ad1";

   private static final String AD2 = "ad2";

   private static final String AD3 = "ad3";

   private static final String Q1 = "q1";

   private static final String Q2 = "q2";

   private static final String Q3 = "q3";

   private static final int TOT_AD3 = 5000;

   private HornetQServer server;

   private ClientSessionFactory sf;

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   // Public --------------------------------------------------------

   public void testCleanupAIO() throws Throwable
   {
      if (AsynchronousFileImpl.isLoaded())
      {
         internalTestCleanup(JournalType.ASYNCIO);
         tearDown();
         setUp();
      }
   }

   public void testCleanupNIO() throws Throwable
   {
      internalTestCleanup(JournalType.NIO);
      tearDown();
      setUp();
   }

   private void internalTestCleanup(final JournalType journalType) throws Throwable
   {
      setupServer(journalType);

      ClientSession session = sf.createSession(false, true, true);

      ClientProducer prod = session.createProducer(CompactingStressTest.AD1);

      for (int i = 0; i < 500; i++)
      {
         prod.send(session.createMessage(true));
      }

      session.commit();

      prod.close();

      ClientConsumer cons = session.createConsumer(CompactingStressTest.Q2);
      prod = session.createProducer(CompactingStressTest.AD2);

      session.start();

      for (int i = 0; i < 200; i++)
      {
         System.out.println("Iteration " + i);
         // Sending non transactionally, so it would test non transactional stuff on the journal
         for (int j = 0; j < 1000; j++)
         {
            Message msg = session.createMessage(true);
            msg.getBodyBuffer().writeBytes(new byte[1024]);

            prod.send(msg);
         }

         // I need to guarantee a roundtrip to the server, to make sure everything is persisted
         session.commit();

         for (int j = 0; j < 1000; j++)
         {
            ClientMessage msg = cons.receive(2000);
            Assert.assertNotNull(msg);
            msg.acknowledge();
         }

         // I need to guarantee a roundtrip to the server, to make sure everything is persisted
         session.commit();

      }

      Assert.assertNull(cons.receiveImmediate());

      session.close();

      server.stop();

      server.start();

      session = sf.createSession(false, true, true);
      cons = session.createConsumer(CompactingStressTest.Q1);
      session.start();

      for (int i = 0; i < 500; i++)
      {
         ClientMessage msg = cons.receive(1000);
         Assert.assertNotNull(msg);
         msg.acknowledge();
      }

      Assert.assertNull(cons.receiveImmediate());

      prod = session.createProducer(CompactingStressTest.AD2);

      session.close();

   }

   public void testMultiProducerAndCompactAIO() throws Throwable
   {
      internalTestMultiProducer(JournalType.ASYNCIO);
   }

   public void testMultiProducerAndCompactNIO() throws Throwable
   {
      internalTestMultiProducer(JournalType.NIO);
   }

   public void internalTestMultiProducer(final JournalType journalType) throws Throwable
   {

      setupServer(journalType);

      ClientSession session = sf.createSession(false, false);

      try
      {
         ClientProducer producer = session.createProducer(CompactingStressTest.AD3);

         byte[] buffer = new byte[10 * 1024];

         ClientMessage msg = session.createMessage(true);

         for (int i = 0; i < CompactingStressTest.TOT_AD3; i++)
         {
            producer.send(msg);
            if (i % 100 == 0)
            {
               session.commit();
            }
         }

         session.commit();
      }
      finally
      {
         session.close();
      }

      server.stop();

      setupServer(journalType);

      final AtomicInteger numberOfMessages = new AtomicInteger(0);
      final int NUMBER_OF_FAST_MESSAGES = 100000;
      final int SLOW_INTERVAL = 100;

      final CountDownLatch latchReady = new CountDownLatch(2);
      final CountDownLatch latchStart = new CountDownLatch(1);

      class FastProducer extends Thread
      {
         Throwable e;

         FastProducer()
         {
            super("Fast-Thread");
         }

         @Override
         public void run()
         {
            ClientSession session = null;
            ClientSession sessionSlow = null;
            latchReady.countDown();
            try
            {
               latchStart.await();
               session = sf.createSession(true, true);
               sessionSlow = sf.createSession(false, false);
               ClientProducer prod = session.createProducer(CompactingStressTest.AD2);
               ClientProducer slowProd = sessionSlow.createProducer(CompactingStressTest.AD1);
               for (int i = 0; i < NUMBER_OF_FAST_MESSAGES; i++)
               {
                  if (i % SLOW_INTERVAL == 0)
                  {
                     if (numberOfMessages.incrementAndGet() % 5 == 0)
                     {
                        sessionSlow.commit();
                     }
                     slowProd.send(session.createMessage(true));
                  }
                  ClientMessage msg = session.createMessage(true);

                  prod.send(msg);
               }
               sessionSlow.commit();
            }
            catch (Throwable e)
            {
               this.e = e;
            }
            finally
            {
               try
               {
                  session.close();
               }
               catch (Throwable e)
               {
                  this.e = e;
               }
               try
               {
                  sessionSlow.close();
               }
               catch (Throwable e)
               {
                  this.e = e;
               }
            }
         }
      }

      class FastConsumer extends Thread
      {
         Throwable e;

         FastConsumer()
         {
            super("Fast-Consumer");
         }

         @Override
         public void run()
         {
            ClientSession session = null;
            latchReady.countDown();
            try
            {
               latchStart.await();
               session = sf.createSession(true, true);
               session.start();
               ClientConsumer cons = session.createConsumer(CompactingStressTest.Q2);
               for (int i = 0; i < NUMBER_OF_FAST_MESSAGES; i++)
               {
                  ClientMessage msg = cons.receive(60 * 1000);
                  msg.acknowledge();
               }

               Assert.assertNull(cons.receiveImmediate());
            }
            catch (Throwable e)
            {
               this.e = e;
            }
            finally
            {
               try
               {
                  session.close();
               }
               catch (Throwable e)
               {
                  this.e = e;
               }
            }
         }
      }

      FastConsumer f1 = new FastConsumer();
      f1.start();

      FastProducer p1 = new FastProducer();
      p1.start();

      latchReady.await();
      latchStart.countDown();

      p1.join();

      if (p1.e != null)
      {
         throw p1.e;
      }

      f1.join();

      if (f1.e != null)
      {
         throw f1.e;
      }

      sf.close();

      server.stop();

      setupServer(journalType);

      ClientSession sess = null;

      try
      {

         sess = sf.createSession(true, true);

         ClientConsumer cons = sess.createConsumer(CompactingStressTest.Q1);

         sess.start();

         for (int i = 0; i < numberOfMessages.intValue(); i++)
         {
            ClientMessage msg = cons.receive(60000);
            Assert.assertNotNull(msg);
            msg.acknowledge();
         }

         Assert.assertNull(cons.receiveImmediate());

         cons.close();

         cons = sess.createConsumer(CompactingStressTest.Q2);

         Assert.assertNull(cons.receiveImmediate());

         cons.close();

         cons = sess.createConsumer(CompactingStressTest.Q3);

         for (int i = 0; i < CompactingStressTest.TOT_AD3; i++)
         {
            ClientMessage msg = cons.receive(60000);
            Assert.assertNotNull(msg);
            msg.acknowledge();
         }

         Assert.assertNull(cons.receiveImmediate());

      }
      finally
      {
         try
         {
            sess.close();
         }
         catch (Throwable ignored)
         {
         }
      }
   }

   @Override
   protected void setUp() throws Exception
   {
      super.setUp();

      clearData();
   }

   /**
    * @throws Exception
    * @throws HornetQException
    */
   private void setupServer(final JournalType journalType) throws Exception, HornetQException
   {
      Configuration config = createDefaultConfig();
      config.setJournalSyncNonTransactional(false);
      config.setJournalFileSize(ConfigurationImpl.DEFAULT_JOURNAL_FILE_SIZE);

      config.setJournalType(journalType);

      config.setJournalCompactMinFiles(10);
      config.setJournalCompactPercentage(50);

      server = createServer(true, config);

      server.start();

      sf = createInVMFactory();
      sf.getServerLocator().setBlockOnDurableSend(false);
      sf.getServerLocator().setBlockOnAcknowledge(false);

      ClientSession sess = sf.createSession();

      try
      {
         sess.createQueue(CompactingStressTest.AD1, CompactingStressTest.Q1, true);
      }
      catch (Exception ignored)
      {
      }

      try
      {
         sess.createQueue(CompactingStressTest.AD2, CompactingStressTest.Q2, true);
      }
      catch (Exception ignored)
      {
      }

      try
      {
         sess.createQueue(CompactingStressTest.AD3, CompactingStressTest.Q3, true);
      }
      catch (Exception ignored)
      {
      }

      sess.close();
   }

   @Override
   protected void tearDown() throws Exception
   {
      try
      {
         if (sf != null)
         {
            sf.close();
         }

         if (server != null)
         {
            server.stop();
         }
      }
      catch (Exception e)
      {
         e.printStackTrace(); // system.out -> junit reports
      }

      server = null;

      sf = null;

      super.tearDown();
   }

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------

}
