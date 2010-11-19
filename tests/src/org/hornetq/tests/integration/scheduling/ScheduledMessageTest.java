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
package org.hornetq.tests.integration.scheduling;

import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import junit.framework.Assert;

import org.hornetq.api.core.Message;
import org.hornetq.api.core.SimpleString;
import org.hornetq.api.core.client.*;
import org.hornetq.core.config.Configuration;
import org.hornetq.core.logging.Logger;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.core.settings.impl.AddressSettings;
import org.hornetq.core.transaction.impl.XidImpl;
import org.hornetq.jms.client.HornetQTextMessage;
import org.hornetq.tests.util.ServiceTestBase;
import org.hornetq.tests.util.UnitTestCase;
import org.hornetq.utils.UUIDGenerator;

/**
 * @author <a href="mailto:andy.taylor@jboss.org">Andy Taylor</a>
 */
public class ScheduledMessageTest extends ServiceTestBase
{
   private static final Logger log = Logger.getLogger(ScheduledMessageTest.class);

   private final SimpleString atestq = new SimpleString("ascheduledtestq");

   private final SimpleString atestq2 = new SimpleString("ascheduledtestq2");

   private Configuration configuration;

   private HornetQServer server;

   private ServerLocator locator;

   @Override
   protected void setUp() throws Exception
   {
      super.setUp();
      clearData();
      configuration = createDefaultConfig();
      configuration.setSecurityEnabled(false);
      configuration.setJournalMinFiles(2);
      server = createServer(true, configuration);
      server.start();
      locator = createInVMNonHALocator();
   }

   @Override
   protected void tearDown() throws Exception
   {
      locator.close();
      
      if (server != null)
      {
         try
         {
            server.stop();
            server = null;
         }
         catch (Exception e)
         {
            // ignore
         }
      }
      super.tearDown();
   }

   public void testRecoveredMessageDeliveredCorrectly() throws Exception
   {
      testMessageDeliveredCorrectly(true);
   }

   public void testMessageDeliveredCorrectly() throws Exception
   {
      testMessageDeliveredCorrectly(false);
   }

   public void testScheduledMessagesDeliveredCorrectly() throws Exception
   {
      testScheduledMessagesDeliveredCorrectly(false);
   }

   public void testRecoveredScheduledMessagesDeliveredCorrectly() throws Exception
   {
      testScheduledMessagesDeliveredCorrectly(true);
   }

   public void testScheduledMessagesDeliveredCorrectlyDifferentOrder() throws Exception
   {
      testScheduledMessagesDeliveredCorrectlyDifferentOrder(false);
   }

   public void testRecoveredScheduledMessagesDeliveredCorrectlyDifferentOrder() throws Exception
   {
      testScheduledMessagesDeliveredCorrectlyDifferentOrder(true);
   }

   public void testScheduledAndNormalMessagesDeliveredCorrectly() throws Exception
   {
      testScheduledAndNormalMessagesDeliveredCorrectly(false);
   }

   public void testRecoveredScheduledAndNormalMessagesDeliveredCorrectly() throws Exception
   {
      testScheduledAndNormalMessagesDeliveredCorrectly(true);
   }

   public void testTxMessageDeliveredCorrectly() throws Exception
   {
      testTxMessageDeliveredCorrectly(false);
   }

   public void testRecoveredTxMessageDeliveredCorrectly() throws Exception
   {
      testTxMessageDeliveredCorrectly(true);
   }

   public void testPagedMessageDeliveredCorrectly() throws Exception
   {
      // then we create a client as normal
      ClientSessionFactory sessionFactory = locator.createSessionFactory();
      ClientSession session = sessionFactory.createSession(false, true, false);
      session.createQueue(atestq, atestq, null, true);
      ClientProducer producer = session.createProducer(atestq);
      ClientMessage message = createDurableMessage(session, "m1");
      long time = System.currentTimeMillis();
      time += 10000;
      message.putLongProperty(Message.HDR_SCHEDULED_DELIVERY_TIME, time);
      producer.send(message);

      producer.close();

      ClientConsumer consumer = session.createConsumer(atestq);

      session.start();

      ClientMessage message2 = consumer.receive(10250);
      Assert.assertTrue(System.currentTimeMillis() >= time);
      Assert.assertEquals("m1", message2.getBodyBuffer().readString());

      message2.acknowledge();

      // Make sure no more messages
      consumer.close();
      consumer = session.createConsumer(atestq);
      Assert.assertNull(consumer.receiveImmediate());

      session.close();
   }

   public void testPagedMessageDeliveredMultipleConsumersCorrectly() throws Exception
   {
      AddressSettings qs = new AddressSettings();
      qs.setRedeliveryDelay(5000l);
      server.getAddressSettingsRepository().addMatch(atestq.toString(), qs);
      // then we create a client as normal
      ClientSessionFactory sessionFactory = locator.createSessionFactory();
      ClientSession session = sessionFactory.createSession(false, true, false);
      session.createQueue(atestq, atestq, null, true);
      session.createQueue(atestq, atestq2, null, true);
      ClientProducer producer = session.createProducer(atestq);
      ClientMessage message = createDurableMessage(session, "m1");
      producer.send(message);

      producer.close();

      ClientConsumer consumer = session.createConsumer(atestq);
      ClientConsumer consumer2 = session.createConsumer(atestq2);

      session.start();
      ClientMessage message3 = consumer.receive(1000);
      ClientMessage message2 = consumer2.receive(1000);
      Assert.assertEquals("m1", message3.getBodyBuffer().readString());
      Assert.assertEquals("m1", message2.getBodyBuffer().readString());
      long time = System.currentTimeMillis();
      // force redelivery
      consumer.close();
      consumer2.close();
      consumer = session.createConsumer(atestq);
      consumer2 = session.createConsumer(atestq2);
      message3 = consumer.receive(5250);
      message2 = consumer2.receive(1000);
      time += 5000;
      Assert.assertTrue(System.currentTimeMillis() >= time);
      Assert.assertEquals("m1", message3.getBodyBuffer().readString());
      Assert.assertEquals("m1", message2.getBodyBuffer().readString());
      message2.acknowledge();
      message3.acknowledge();

      // Make sure no more messages
      consumer.close();
      consumer2.close();
      consumer = session.createConsumer(atestq);
      Assert.assertNull(consumer.receiveImmediate());

      session.close();
   }

   public void testPagedMessageDeliveredMultipleConsumersAfterRecoverCorrectly() throws Exception
   {

      AddressSettings qs = new AddressSettings();
      qs.setRedeliveryDelay(5000l);
      server.getAddressSettingsRepository().addMatch(atestq.toString(), qs);
      // then we create a client as normal
      ClientSessionFactory sessionFactory = locator.createSessionFactory();
      ClientSession session = sessionFactory.createSession(false, true, false);
      session.createQueue(atestq, atestq, null, true);
      session.createQueue(atestq, atestq2, null, true);
      ClientProducer producer = session.createProducer(atestq);
      ClientMessage message = createDurableMessage(session, "m1");
      producer.send(message);

      producer.close();

      ClientConsumer consumer = session.createConsumer(atestq);
      ClientConsumer consumer2 = session.createConsumer(atestq2);

      session.start();
      ClientMessage message3 = consumer.receive(1000);
      Assert.assertNotNull(message3);
      ClientMessage message2 = consumer2.receive(1000);
      Assert.assertNotNull(message2);
      Assert.assertEquals("m1", message3.getBodyBuffer().readString());
      Assert.assertEquals("m1", message2.getBodyBuffer().readString());
      long time = System.currentTimeMillis();
      // force redelivery
      consumer.close();
      consumer2.close();
      producer.close();
      session.close();
      server.stop();
      server = null;
      server = createServer(true, configuration);
      server.start();
      sessionFactory = locator.createSessionFactory();
      session = sessionFactory.createSession(false, true, true);
      consumer = session.createConsumer(atestq);
      consumer2 = session.createConsumer(atestq2);
      session.start();
      message3 = consumer.receive(5250);
      Assert.assertNotNull(message3);
      message2 = consumer2.receive(1000);
      Assert.assertNotNull(message2);
      time += 5000;
      Assert.assertTrue(System.currentTimeMillis() >= time);
      Assert.assertEquals("m1", message3.getBodyBuffer().readString());
      Assert.assertEquals("m1", message2.getBodyBuffer().readString());
      message2.acknowledge();
      message3.acknowledge();

      // Make sure no more messages
      consumer.close();
      consumer2.close();
      consumer = session.createConsumer(atestq);
      Assert.assertNull(consumer.receiveImmediate());

      session.close();
   }

   public void testMessageDeliveredCorrectly(final boolean recover) throws Exception
   {

      // then we create a client as normal
      ClientSessionFactory sessionFactory = locator.createSessionFactory();
      ClientSession session = sessionFactory.createSession(false, true, false);
      session.createQueue(atestq, atestq, null, true);
      ClientProducer producer = session.createProducer(atestq);
      ClientMessage message = session.createMessage(HornetQTextMessage.TYPE,
                                                          false,
                                                          0,
                                                          System.currentTimeMillis(),
                                                          (byte)1);
      message.getBodyBuffer().writeString("testINVMCoreClient");
      message.setDurable(true);
      long time = System.currentTimeMillis();
      time += 10000;
      message.putLongProperty(Message.HDR_SCHEDULED_DELIVERY_TIME, time);
      producer.send(message);

      ScheduledMessageTest.log.info("Recover is " + recover);
      if (recover)
      {
         producer.close();
         session.close();
         server.stop();
         server = null;
         server = createServer(true, configuration);
         server.start();
         sessionFactory = locator.createSessionFactory();
         session = sessionFactory.createSession(false, true, true);
      }
      ClientConsumer consumer = session.createConsumer(atestq);

      session.start();

      ClientMessage message2 = consumer.receive(11000);
      Assert.assertTrue(System.currentTimeMillis() >= time);
      Assert.assertEquals("testINVMCoreClient", message2.getBodyBuffer().readString());

      message2.acknowledge();

      // Make sure no more messages
      consumer.close();
      consumer = session.createConsumer(atestq);
      Assert.assertNull(consumer.receiveImmediate());

      session.close();
   }

   public void testScheduledMessagesDeliveredCorrectly(final boolean recover) throws Exception
   {

      ClientSessionFactory sessionFactory = locator.createSessionFactory();
      ClientSession session = sessionFactory.createSession(false, true, false);
      session.createQueue(atestq, atestq, null, true);
      ClientProducer producer = session.createProducer(atestq);
      ClientMessage m1 = createDurableMessage(session, "m1");
      ClientMessage m2 = createDurableMessage(session, "m2");
      ClientMessage m3 = createDurableMessage(session, "m3");
      ClientMessage m4 = createDurableMessage(session, "m4");
      ClientMessage m5 = createDurableMessage(session, "m5");
      long time = System.currentTimeMillis();
      time += 10000;
      m1.putLongProperty(Message.HDR_SCHEDULED_DELIVERY_TIME, time);
      producer.send(m1);
      time += 1000;
      m2.putLongProperty(Message.HDR_SCHEDULED_DELIVERY_TIME, time);
      producer.send(m2);
      time += 1000;
      m3.putLongProperty(Message.HDR_SCHEDULED_DELIVERY_TIME, time);
      producer.send(m3);
      time += 1000;
      m4.putLongProperty(Message.HDR_SCHEDULED_DELIVERY_TIME, time);
      producer.send(m4);
      time += 1000;
      m5.putLongProperty(Message.HDR_SCHEDULED_DELIVERY_TIME, time);
      producer.send(m5);
      time -= 4000;
      if (recover)
      {
         producer.close();
         session.close();
         server.stop();
         server = null;
         server = createServer(true, configuration);
         server.start();

         sessionFactory = locator.createSessionFactory();

         session = sessionFactory.createSession(false, true, true);
      }

      ClientConsumer consumer = session.createConsumer(atestq);

      session.start();

      ClientMessage message = consumer.receive(11000);
      Assert.assertTrue(System.currentTimeMillis() >= time);
      Assert.assertEquals("m1", message.getBodyBuffer().readString());
      message.acknowledge();
      time += 1000;
      message = consumer.receive(1250);
      Assert.assertTrue(System.currentTimeMillis() >= time);
      Assert.assertEquals("m2", message.getBodyBuffer().readString());
      message.acknowledge();
      time += 1000;
      message = consumer.receive(1250);
      Assert.assertTrue(System.currentTimeMillis() >= time);
      Assert.assertEquals("m3", message.getBodyBuffer().readString());
      message.acknowledge();
      time += 1000;
      message = consumer.receive(1250);
      Assert.assertTrue(System.currentTimeMillis() >= time);
      Assert.assertEquals("m4", message.getBodyBuffer().readString());
      message.acknowledge();
      time += 1000;
      message = consumer.receive(1250);
      Assert.assertTrue(System.currentTimeMillis() >= time);
      Assert.assertEquals("m5", message.getBodyBuffer().readString());
      message.acknowledge();

      // Make sure no more messages
      consumer.close();
      consumer = session.createConsumer(atestq);
      Assert.assertNull(consumer.receiveImmediate());

      session.close();
   }

   public void testScheduledMessagesDeliveredCorrectlyDifferentOrder(final boolean recover) throws Exception
   {

      ClientSessionFactory sessionFactory = locator.createSessionFactory();
      ClientSession session = sessionFactory.createSession(false, true, false);
      session.createQueue(atestq, atestq, null, true);
      ClientProducer producer = session.createProducer(atestq);
      ClientMessage m1 = createDurableMessage(session, "m1");
      ClientMessage m2 = createDurableMessage(session, "m2");
      ClientMessage m3 = createDurableMessage(session, "m3");
      ClientMessage m4 = createDurableMessage(session, "m4");
      ClientMessage m5 = createDurableMessage(session, "m5");
      long time = System.currentTimeMillis();
      time += 10000;
      m1.putLongProperty(Message.HDR_SCHEDULED_DELIVERY_TIME, time);
      producer.send(m1);
      time += 3000;
      m2.putLongProperty(Message.HDR_SCHEDULED_DELIVERY_TIME, time);
      producer.send(m2);
      time -= 2000;
      m3.putLongProperty(Message.HDR_SCHEDULED_DELIVERY_TIME, time);
      producer.send(m3);
      time += 3000;
      m4.putLongProperty(Message.HDR_SCHEDULED_DELIVERY_TIME, time);
      producer.send(m4);
      time -= 2000;
      m5.putLongProperty(Message.HDR_SCHEDULED_DELIVERY_TIME, time);
      producer.send(m5);
      time -= 2000;
      ClientConsumer consumer = null;
      if (recover)
      {
         producer.close();
         session.close();
         server.stop();
         server = null;
         server = createServer(true, configuration);
         server.start();

         sessionFactory = locator.createSessionFactory();

         session = sessionFactory.createSession(false, true, true);

      }
      consumer = session.createConsumer(atestq);

      session.start();

      ClientMessage message = consumer.receive(10250);
      Assert.assertTrue(System.currentTimeMillis() >= time);
      Assert.assertEquals("m1", message.getBodyBuffer().readString());
      message.acknowledge();
      time += 1000;
      message = consumer.receive(1250);
      Assert.assertTrue(System.currentTimeMillis() >= time);
      Assert.assertEquals("m3", message.getBodyBuffer().readString());
      message.acknowledge();
      time += 1000;
      message = consumer.receive(1250);
      Assert.assertTrue(System.currentTimeMillis() >= time);
      Assert.assertEquals("m5", message.getBodyBuffer().readString());
      message.acknowledge();
      time += 1000;
      message = consumer.receive(1250);
      Assert.assertTrue(System.currentTimeMillis() >= time);
      Assert.assertEquals("m2", message.getBodyBuffer().readString());
      message.acknowledge();
      time += 1000;
      message = consumer.receive(1250);
      Assert.assertTrue(System.currentTimeMillis() >= time);
      Assert.assertEquals("m4", message.getBodyBuffer().readString());
      message.acknowledge();

      // Make sure no more messages
      consumer.close();
      consumer = session.createConsumer(atestq);
      Assert.assertNull(consumer.receiveImmediate());

      session.close();
   }

   public void testScheduledAndNormalMessagesDeliveredCorrectly(final boolean recover) throws Exception
   {

      ClientSessionFactory sessionFactory = locator.createSessionFactory();
      ClientSession session = sessionFactory.createSession(false, true, false);
      session.createQueue(atestq, atestq, null, true);
      ClientProducer producer = session.createProducer(atestq);
      ClientMessage m1 = createDurableMessage(session, "m1");
      ClientMessage m2 = createDurableMessage(session, "m2");
      ClientMessage m3 = createDurableMessage(session, "m3");
      ClientMessage m4 = createDurableMessage(session, "m4");
      ClientMessage m5 = createDurableMessage(session, "m5");
      long time = System.currentTimeMillis();
      time += 10000;
      m1.putLongProperty(Message.HDR_SCHEDULED_DELIVERY_TIME, time);
      producer.send(m1);
      producer.send(m2);
      time += 1000;
      m3.putLongProperty(Message.HDR_SCHEDULED_DELIVERY_TIME, time);
      producer.send(m3);
      producer.send(m4);
      time += 1000;
      m5.putLongProperty(Message.HDR_SCHEDULED_DELIVERY_TIME, time);
      producer.send(m5);
      time -= 2000;
      ClientConsumer consumer = null;
      if (recover)
      {
         producer.close();
         session.close();
         server.stop();
         server = null;
         server = createServer(true, configuration);
         server.start();

         sessionFactory = locator.createSessionFactory();

         session = sessionFactory.createSession(false, true, true);
      }

      consumer = session.createConsumer(atestq);
      session.start();

      ClientMessage message = consumer.receive(1000);
      Assert.assertEquals("m2", message.getBodyBuffer().readString());
      message.acknowledge();
      message = consumer.receive(1000);
      Assert.assertEquals("m4", message.getBodyBuffer().readString());
      message.acknowledge();
      message = consumer.receive(10250);
      Assert.assertTrue(System.currentTimeMillis() >= time);
      Assert.assertEquals("m1", message.getBodyBuffer().readString());
      message.acknowledge();
      time += 1000;
      message = consumer.receive(1250);
      Assert.assertTrue(System.currentTimeMillis() >= time);
      Assert.assertEquals("m3", message.getBodyBuffer().readString());
      message.acknowledge();
      time += 1000;
      message = consumer.receive(1250);
      Assert.assertTrue(System.currentTimeMillis() >= time);
      Assert.assertEquals("m5", message.getBodyBuffer().readString());
      message.acknowledge();

      // Make sure no more messages
      consumer.close();
      consumer = session.createConsumer(atestq);
      Assert.assertNull(consumer.receiveImmediate());

      session.close();
   }

   public void testTxMessageDeliveredCorrectly(final boolean recover) throws Exception
   {
      Xid xid = new XidImpl("xa1".getBytes(), 1, UUIDGenerator.getInstance().generateStringUUID().getBytes());
      Xid xid2 = new XidImpl("xa2".getBytes(), 1, UUIDGenerator.getInstance().generateStringUUID().getBytes());

      ClientSessionFactory sessionFactory = locator.createSessionFactory();
      ClientSession session = sessionFactory.createSession(true, false, false);
      session.createQueue(atestq, atestq, null, true);
      session.start(xid, XAResource.TMNOFLAGS);
      ClientProducer producer = session.createProducer(atestq);
      ClientMessage message = createDurableMessage(session, "testINVMCoreClient");
      long time = System.currentTimeMillis() + 1000;
      message.putLongProperty(Message.HDR_SCHEDULED_DELIVERY_TIME, time);
      producer.send(message);
      session.end(xid, XAResource.TMSUCCESS);
      session.prepare(xid);
      if (recover)
      {
         producer.close();
         session.close();
         server.stop();
         server = null;
         server = createServer(true, configuration);
         server.start();

         sessionFactory = locator.createSessionFactory();

         session = sessionFactory.createSession(true, false, false);
      }
      session.commit(xid, false);
      ClientConsumer consumer = session.createConsumer(atestq);

      session.start();
      session.start(xid2, XAResource.TMNOFLAGS);
      ClientMessage message2 = consumer.receive(11000);
      long end = System.currentTimeMillis();
      System.out.println("elapsed time = " + (end - time));
      Assert.assertTrue(end >= time);
      Assert.assertNotNull(message2);
      Assert.assertEquals("testINVMCoreClient", message2.getBodyBuffer().readString());

      message2.acknowledge();
      session.end(xid2, XAResource.TMSUCCESS);
      session.prepare(xid2);
      session.commit(xid2, false);
      consumer.close();
      // Make sure no more messages
      consumer = session.createConsumer(atestq);
      Assert.assertNull(consumer.receiveImmediate());
      session.close();
   }

   public void testScheduledDeliveryTX() throws Exception
   {
      scheduledDelivery(true);
   }

   public void testScheduledDeliveryNoTX() throws Exception
   {
      scheduledDelivery(false);
   }

   // Private -------------------------------------------------------

   private void scheduledDelivery(final boolean tx) throws Exception
   {
      UnitTestCase.forceGC();

      Xid xid = new XidImpl("xa1".getBytes(), 1, UUIDGenerator.getInstance().generateStringUUID().getBytes());

      ClientSessionFactory sessionFactory = locator.createSessionFactory();
      ClientSession session = sessionFactory.createSession(tx, false, false);
      session.createQueue(atestq, atestq, null, true);
      ClientProducer producer = session.createProducer(atestq);
      ClientConsumer consumer = session.createConsumer(atestq);

      session.start();
      if (tx)
      {
         session.start(xid, XAResource.TMNOFLAGS);
      }

      // Send one scheduled
      long now = System.currentTimeMillis();

      ClientMessage tm1 = createDurableMessage(session, "testScheduled1");
      tm1.putLongProperty(Message.HDR_SCHEDULED_DELIVERY_TIME, now + 7000);
      producer.send(tm1);

      // First send some non scheduled messages

      ClientMessage tm2 = createDurableMessage(session, "testScheduled2");
      producer.send(tm2);

      ClientMessage tm3 = createDurableMessage(session, "testScheduled3");
      producer.send(tm3);

      ClientMessage tm4 = createDurableMessage(session, "testScheduled4");
      producer.send(tm4);

      // Now send some more scheduled messages

      ClientMessage tm5 = createDurableMessage(session, "testScheduled5");
      tm5.putLongProperty(Message.HDR_SCHEDULED_DELIVERY_TIME, now + 5000);
      producer.send(tm5);

      ClientMessage tm6 = createDurableMessage(session, "testScheduled6");
      tm6.putLongProperty(Message.HDR_SCHEDULED_DELIVERY_TIME, now + 4000);
      producer.send(tm6);

      ClientMessage tm7 = createDurableMessage(session, "testScheduled7");
      tm7.putLongProperty(Message.HDR_SCHEDULED_DELIVERY_TIME, now + 3000);
      producer.send(tm7);

      ClientMessage tm8 = createDurableMessage(session, "testScheduled8");
      tm8.putLongProperty(Message.HDR_SCHEDULED_DELIVERY_TIME, now + 6000);
      producer.send(tm8);

      // And one scheduled with a -ve number

      ClientMessage tm9 = createDurableMessage(session, "testScheduled9");
      tm9.putLongProperty(Message.HDR_SCHEDULED_DELIVERY_TIME, -3);
      producer.send(tm9);

      if (tx)
      {
         session.end(xid, XAResource.TMSUCCESS);
         session.prepare(xid);
         session.commit(xid, false);
      }
      else
      {
         session.commit();
      }

      // First the non scheduled messages should be received

      if (tx)
      {
         session.start(xid, XAResource.TMNOFLAGS);
      }

      ClientMessage rm1 = consumer.receive(250);
      Assert.assertNotNull(rm1);
      Assert.assertEquals("testScheduled2", rm1.getBodyBuffer().readString());

      ClientMessage rm2 = consumer.receive(250);
      Assert.assertNotNull(rm2);
      Assert.assertEquals("testScheduled3", rm2.getBodyBuffer().readString());

      ClientMessage rm3 = consumer.receive(250);
      Assert.assertNotNull(rm3);
      Assert.assertEquals("testScheduled4", rm3.getBodyBuffer().readString());

      // Now the one with a scheduled with a -ve number
      ClientMessage rm5 = consumer.receive(250);
      Assert.assertNotNull(rm5);
      Assert.assertEquals("testScheduled9", rm5.getBodyBuffer().readString());

      // Now the scheduled
      ClientMessage rm6 = consumer.receive(3250);
      Assert.assertNotNull(rm6);
      Assert.assertEquals("testScheduled7", rm6.getBodyBuffer().readString());

      long now2 = System.currentTimeMillis();

      Assert.assertTrue(now2 - now >= 3000);

      ClientMessage rm7 = consumer.receive(1250);
      Assert.assertNotNull(rm7);
      Assert.assertEquals("testScheduled6", rm7.getBodyBuffer().readString());

      now2 = System.currentTimeMillis();

      Assert.assertTrue(now2 - now >= 4000);

      ClientMessage rm8 = consumer.receive(1250);
      Assert.assertNotNull(rm8);
      Assert.assertEquals("testScheduled5", rm8.getBodyBuffer().readString());

      now2 = System.currentTimeMillis();

      Assert.assertTrue(now2 - now >= 5000);

      ClientMessage rm9 = consumer.receive(1250);
      Assert.assertNotNull(rm9);
      Assert.assertEquals("testScheduled8", rm9.getBodyBuffer().readString());

      now2 = System.currentTimeMillis();

      Assert.assertTrue(now2 - now >= 6000);

      ClientMessage rm10 = consumer.receive(1250);
      Assert.assertNotNull(rm10);
      Assert.assertEquals("testScheduled1", rm10.getBodyBuffer().readString());

      now2 = System.currentTimeMillis();

      Assert.assertTrue(now2 - now >= 7000);

      if (tx)
      {
         session.end(xid, XAResource.TMSUCCESS);
         session.prepare(xid);
         session.commit(xid, false);
      }

      session.close();
      sessionFactory.close();
   }

   private ClientMessage createDurableMessage(final ClientSession session, final String body)
   {
      ClientMessage message = session.createMessage(HornetQTextMessage.TYPE,
                                                          true,
                                                          0,
                                                          System.currentTimeMillis(),
                                                          (byte)1);
      message.getBodyBuffer().writeString(body);
      return message;
   }
}
