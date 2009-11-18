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

package org.hornetq.tests.performance.paging;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;

import org.hornetq.core.client.ClientMessage;
import org.hornetq.core.client.ClientProducer;
import org.hornetq.core.client.ClientSession;
import org.hornetq.core.client.ClientSessionFactory;
import org.hornetq.core.config.Configuration;
import org.hornetq.core.exception.HornetQException;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.core.settings.impl.AddressSettings;
import org.hornetq.tests.util.ServiceTestBase;
import org.hornetq.utils.SimpleString;

/**
 * A MeasurePagingMultiThreadTest
 *
 * @author <a href="mailto:clebert.suconic@jboss.org">Clebert Suconic</a>
 * 
 * Created Dec 1, 2008 1:02:39 PM
 *
 *
 */
public class MeasurePagingMultiThreadTest extends ServiceTestBase
{

   // Constants -----------------------------------------------------

   // Attributes ----------------------------------------------------

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   // Public --------------------------------------------------------

   @Override
   protected void setUp() throws Exception
   {
      super.setUp();
      clearData();
   }

   public void testPagingMultipleSenders() throws Throwable
   {

      final int NUMBER_OF_THREADS = 18;
      final int NUMBER_OF_MESSAGES = 50000;
      final int SIZE_OF_MESSAGE = 1024;

      Configuration config = createDefaultConfig();

      HashMap<String, AddressSettings> settings = new HashMap<String, AddressSettings>();

      HornetQServer messagingService = createServer(true, config, 10 * 1024, 20 * 1024, settings);
      messagingService.start();
      try
      {

         final ClientSessionFactory factory = createInVMFactory();
         final SimpleString adr = new SimpleString("test-adr");

         createDestination(factory, adr);

         // Send some messages to make sure the destination is in page mode before we measure
         // And that will also help with VM optimizations
         sendInitialBatch(adr, NUMBER_OF_MESSAGES, SIZE_OF_MESSAGE, factory);

         final CountDownLatch latchAlign = new CountDownLatch(NUMBER_OF_THREADS);

         final CountDownLatch latchStart = new CountDownLatch(1);

         class Sender extends Thread
         {

            private final ClientSession session;

            private final ClientProducer producer;

            private final ClientMessage msg;

            Throwable e;

            public Sender() throws Exception
            {
               session = factory.createSession(false, true, true);
               producer = session.createProducer(adr);
               msg = session.createClientMessage(true);
               msg.getBuffer().writeBytes(new byte[SIZE_OF_MESSAGE]);
            }

            // run is not going to close sessions or anything, as we don't want to measure that time
            // so this will be done in a second time
            public void cleanUp() throws Exception
            {
               session.close();
            }

            @Override
            public void run()
            {
               try
               {
                  latchAlign.countDown();
                  latchStart.await();

                  long start = System.currentTimeMillis();
                  sendMessages(NUMBER_OF_MESSAGES, producer, msg);
                  long end = System.currentTimeMillis();

                  System.out.println("Thread " + Thread.currentThread().getName() +
                                     " finished sending in " +
                                     (end - start) +
                                     " milliseconds");
               }
               catch (Throwable e)
               {
                  this.e = e;
               }

            }
         }

         Sender senders[] = new Sender[NUMBER_OF_THREADS];

         for (int i = 0; i < NUMBER_OF_THREADS; i++)
         {
            senders[i] = new Sender();
            senders[i].start();
         }

         latchAlign.await();

         long timeStart = System.currentTimeMillis();

         latchStart.countDown();

         for (Thread t : senders)
         {
            t.join();
         }

         long timeEnd = System.currentTimeMillis();

         System.out.println("Total Time: " + (timeEnd - timeStart) +
                            " milliseconds what represented " +
                            (NUMBER_OF_MESSAGES * NUMBER_OF_THREADS * 1000 / (timeEnd - timeStart)) +
                            " per second");

         for (Sender s : senders)
         {
            if (s.e != null)
            {
               throw s.e;
            }
            s.cleanUp();
         }

      }
      finally
      {
         messagingService.stop();

      }

   }

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   /**
    * @param adr
    * @param nMessages
    * @param messageSize
    * @param factory
    * @throws HornetQException
    */
   private void sendInitialBatch(final SimpleString adr,
                                 final int nMessages,
                                 final int messageSize,
                                 final ClientSessionFactory factory) throws HornetQException
   {
      ClientSession session = factory.createSession(false, true, true);
      ClientProducer producer = session.createProducer(adr);
      ClientMessage msg = session.createClientMessage(true);

      msg.getBuffer().writeBytes(new byte[messageSize]);

      sendMessages(nMessages, producer, msg);
   }

   /**
    * @param nMessages
    * @param producer
    * @param msg
    * @throws HornetQException
    */
   private void sendMessages(final int nMessages, final ClientProducer producer, final ClientMessage msg) throws HornetQException
   {
      for (int i = 0; i < nMessages; i++)
      {
         producer.send(msg);
      }
   }

   /**
    * @param factory
    * @param adr
    * @throws HornetQException
    */
   private void createDestination(final ClientSessionFactory factory, final SimpleString adr) throws HornetQException
   {
      {
         ClientSession session = factory.createSession(false, false, false);
         session.createQueue(adr, adr, null, true);
         session.close();
      }
   }

   // Inner classes -------------------------------------------------

}
