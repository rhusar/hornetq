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

package org.hornetq.tests.integration.cluster.bridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import junit.framework.Assert;

import org.hornetq.api.core.DiscoveryGroupConfiguration;
import org.hornetq.api.core.SimpleString;
import org.hornetq.api.core.TransportConfiguration;
import org.hornetq.api.core.client.ClientConsumer;
import org.hornetq.api.core.client.ClientMessage;
import org.hornetq.api.core.client.ClientProducer;
import org.hornetq.api.core.client.ClientSession;
import org.hornetq.api.core.client.ClientSessionFactory;
import org.hornetq.api.core.client.HornetQClient;
import org.hornetq.api.core.client.ServerLocator;
import org.hornetq.core.config.BridgeConfiguration;
import org.hornetq.core.config.CoreQueueConfiguration;
import org.hornetq.core.config.impl.ConfigurationImpl;
import org.hornetq.core.logging.Logger;
import org.hornetq.core.remoting.impl.invm.TransportConstants;
import org.hornetq.core.remoting.impl.netty.NettyConnectorFactory;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.core.server.cluster.Bridge;
import org.hornetq.tests.util.ServiceTestBase;

/**
 * A BridgeStartTest
 *
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 * 
 * Created 14 Jan 2009 14:05:01
 *
 *
 */
public class BridgeStartTest extends ServiceTestBase
{
   private static final Logger log = Logger.getLogger(BridgeStartTest.class);

   protected boolean isNetty()
   {
      return false;
   }

   private String getConnector()
   {
      if (isNetty())
      {
         return NettyConnectorFactory.class.getName();
      }
      else
      {
         return "org.hornetq.core.remoting.impl.invm.InVMConnectorFactory";
      }
   }

   public void testStartStop() throws Exception
   {
      Map<String, Object> server0Params = new HashMap<String, Object>();
      HornetQServer server0 = createClusteredServerWithParams(isNetty(), 0, true, server0Params);

      Map<String, Object> server1Params = new HashMap<String, Object>();
      if (isNetty())
      {
         server1Params.put("port", org.hornetq.core.remoting.impl.netty.TransportConstants.DEFAULT_PORT + 1);
      }
      else
      {
         server1Params.put(TransportConstants.SERVER_ID_PROP_NAME, 1);
      }
      HornetQServer server1 = createClusteredServerWithParams(isNetty(), 1, true, server1Params);
      ServerLocator locator = null;
      try
      {
         final String testAddress = "testAddress";
         final String queueName0 = "queue0";
         final String forwardAddress = "forwardAddress";
         final String queueName1 = "queue1";

         Map<String, TransportConfiguration> connectors = new HashMap<String, TransportConfiguration>();
         TransportConfiguration server0tc = new TransportConfiguration(getConnector(), server0Params);
         TransportConfiguration server1tc = new TransportConfiguration(getConnector(), server1Params);
         connectors.put(server1tc.getName(), server1tc);

         server0.getConfiguration().setConnectorConfigurations(connectors);

         DiscoveryGroupConfiguration dg = createStaticDiscoveryGroupConfiguration(server1tc);
         server0.getConfiguration().getDiscoveryGroupConfigurations().put(dg.getName(), dg);

         final String bridgeName = "bridge1";

         BridgeConfiguration bridgeConfiguration = new BridgeConfiguration(bridgeName,
                                                                           queueName0,
                                                                           forwardAddress,
                                                                           null,
                                                                           null,
                                                                           HornetQClient.DEFAULT_CLIENT_FAILURE_CHECK_PERIOD,
                                                                           HornetQClient.DEFAULT_CONNECTION_TTL,
                                                                           1000,
                                                                           HornetQClient.DEFAULT_MAX_RETRY_INTERVAL,
                                                                           1d,
                                                                           0,
                                                                           true,
                                                                           1024,
                                                                           dg,
                                                                           false,
                                                                           ConfigurationImpl.DEFAULT_CLUSTER_USER,
                                                                           ConfigurationImpl.DEFAULT_CLUSTER_PASSWORD);

         List<BridgeConfiguration> bridgeConfigs = new ArrayList<BridgeConfiguration>();
         bridgeConfigs.add(bridgeConfiguration);
         server0.getConfiguration().setBridgeConfigurations(bridgeConfigs);

         CoreQueueConfiguration queueConfig0 = new CoreQueueConfiguration(testAddress, queueName0, null, true);
         List<CoreQueueConfiguration> queueConfigs0 = new ArrayList<CoreQueueConfiguration>();
         queueConfigs0.add(queueConfig0);
         server0.getConfiguration().setQueueConfigurations(queueConfigs0);

         CoreQueueConfiguration queueConfig1 = new CoreQueueConfiguration(forwardAddress, queueName1, null, true);
         List<CoreQueueConfiguration> queueConfigs1 = new ArrayList<CoreQueueConfiguration>();
         queueConfigs1.add(queueConfig1);
         server1.getConfiguration().setQueueConfigurations(queueConfigs1);

         server1.start();
         waitForServer(server1);
         
         server0.start();
         waitForServer(server0);

         locator = HornetQClient.createServerLocatorWithoutHA(server0tc, server1tc);
         ClientSessionFactory sf0 = locator.createSessionFactory(server0tc);

         ClientSessionFactory sf1 = locator.createSessionFactory(server1tc);

         ClientSession session0 = sf0.createSession(false, true, true);

         ClientSession session1 = sf1.createSession(false, true, true);

         ClientProducer producer0 = session0.createProducer(new SimpleString(testAddress));

         ClientConsumer consumer1 = session1.createConsumer(queueName1);

         session1.start();

         final int numMessages = 10;

         final SimpleString propKey = new SimpleString("testkey");

         for (int i = 0; i < numMessages; i++)
         {
            ClientMessage message = session0.createMessage(false);

            message.putIntProperty(propKey, i);

            producer0.send(message);
         }

         for (int i = 0; i < numMessages; i++)
         {
            ClientMessage message = consumer1.receive(200);

            Assert.assertNotNull(message);

            Assert.assertEquals(i, message.getObjectProperty(propKey));

            message.acknowledge();
         }

         Assert.assertNull(consumer1.receiveImmediate());

         Bridge bridge = server0.getClusterManager().getBridges().get(bridgeName);

         bridge.stop();
         
         bridge.flushExecutor();

         for (int i = 0; i < numMessages; i++)
         {
            ClientMessage message = session0.createMessage(false);

            message.putIntProperty(propKey, i);

            producer0.send(message);
         }

         Assert.assertNull(consumer1.receiveImmediate());

         bridge.start();

         for (int i = 0; i < numMessages; i++)
         {
            ClientMessage message = consumer1.receive(1000);

            Assert.assertNotNull(message);

            Assert.assertEquals(i, message.getObjectProperty(propKey));

            message.acknowledge();
         }

         Assert.assertNull(consumer1.receiveImmediate());

         session0.close();

         session1.close();

         sf0.close();

         sf1.close();
      }
      finally
      {
         if(locator != null)
         {
            locator.close();
         }

         server0.stop();

         server1.stop();
      }

   }

   public void testTargetServerUpAndDown() throws Exception
   {
      // This test needs to use real files, since it requires duplicate detection, since when the target server is
      // shutdown, messages will get resent when it is started, so the dup id cache needs
      // to be persisted

      Map<String, Object> server0Params = new HashMap<String, Object>();
      HornetQServer server0 = createClusteredServerWithParams(isNetty(), 0, true, server0Params);

      Map<String, Object> server1Params = new HashMap<String, Object>();
      if (isNetty())
      {
         server1Params.put("port", org.hornetq.core.remoting.impl.netty.TransportConstants.DEFAULT_PORT + 1);
      }
      else
      {
         server1Params.put(TransportConstants.SERVER_ID_PROP_NAME, 1);
      }
      HornetQServer server1 = createClusteredServerWithParams(isNetty(), 1, true, server1Params);

      final String testAddress = "testAddress";
      final String queueName0 = "queue0";
      final String forwardAddress = "forwardAddress";
      final String queueName1 = "queue1";

      Map<String, TransportConfiguration> connectors = new HashMap<String, TransportConfiguration>();
      TransportConfiguration server0tc = new TransportConfiguration(getConnector(), server0Params);
      TransportConfiguration server1tc = new TransportConfiguration(getConnector(), server1Params);
      connectors.put(server1tc.getName(), server1tc);

      server0.getConfiguration().setConnectorConfigurations(connectors);

      DiscoveryGroupConfiguration dg = createStaticDiscoveryGroupConfiguration(server1tc);
      server0.getConfiguration().getDiscoveryGroupConfigurations().put(dg.getName(), dg);

      final String bridgeName = "bridge1";

      BridgeConfiguration bridgeConfiguration = new BridgeConfiguration(bridgeName,
                                                                        queueName0,
                                                                        forwardAddress,
                                                                        null,
                                                                        null,
                                                                        HornetQClient.DEFAULT_CLIENT_FAILURE_CHECK_PERIOD,
                                                                        HornetQClient.DEFAULT_CONNECTION_TTL,
                                                                        500,
                                                                        HornetQClient.DEFAULT_MAX_RETRY_INTERVAL,
                                                                        1d,
                                                                        -1,
                                                                        true,
                                                                        1024,
                                                                        dg,
                                                                        false,
                                                                        ConfigurationImpl.DEFAULT_CLUSTER_USER,
                                                                        ConfigurationImpl.DEFAULT_CLUSTER_PASSWORD);

      List<BridgeConfiguration> bridgeConfigs = new ArrayList<BridgeConfiguration>();
      bridgeConfigs.add(bridgeConfiguration);
      server0.getConfiguration().setBridgeConfigurations(bridgeConfigs);

      CoreQueueConfiguration queueConfig0 = new CoreQueueConfiguration(testAddress, queueName0, null, true);
      List<CoreQueueConfiguration> queueConfigs0 = new ArrayList<CoreQueueConfiguration>();
      queueConfigs0.add(queueConfig0);
      server0.getConfiguration().setQueueConfigurations(queueConfigs0);

      CoreQueueConfiguration queueConfig1 = new CoreQueueConfiguration(forwardAddress, queueName1, null, true);
      List<CoreQueueConfiguration> queueConfigs1 = new ArrayList<CoreQueueConfiguration>();
      queueConfigs1.add(queueConfig1);
      server1.getConfiguration().setQueueConfigurations(queueConfigs1);
      ServerLocator locator = null;
      try
      {
         // Don't start server 1 yet

         server0.start();
         waitForServer(server0);

         locator = HornetQClient.createServerLocatorWithoutHA(server0tc, server1tc);
         ClientSessionFactory sf0 = locator.createSessionFactory(server0tc);



         ClientSession session0 = sf0.createSession(false, true, true);

         ClientProducer producer0 = session0.createProducer(new SimpleString(testAddress));

         final int numMessages = 10;

         final SimpleString propKey = new SimpleString("testkey");

         for (int i = 0; i < numMessages; i++)
         {
            ClientMessage message = session0.createMessage(false);

            message.putIntProperty(propKey, i);

            producer0.send(message);
         }

         // Wait a bit
         Thread.sleep(1000);

         server1.start();
         waitForServer(server1);
         
         ClientSessionFactory sf1 = locator.createSessionFactory(server1tc);

         ClientSession session1 = sf1.createSession(false, true, true);

         ClientConsumer consumer1 = session1.createConsumer(queueName1);

         session1.start();

         for (int i = 0; i < numMessages; i++)
         {
            ClientMessage message = consumer1.receive(1000);

            Assert.assertNotNull(message);

            Assert.assertEquals(i, message.getObjectProperty(propKey));

            message.acknowledge();
         }

         Assert.assertNull(consumer1.receiveImmediate());

         for (int i = 0; i < numMessages; i++)
         {
            ClientMessage message = session0.createMessage(false);

            message.putIntProperty(propKey, i);

            producer0.send(message);
         }

         for (int i = 0; i < numMessages; i++)
         {
            ClientMessage message = consumer1.receive(1000);

            Assert.assertNotNull(message);

            Assert.assertEquals(i, message.getObjectProperty(propKey));

            message.acknowledge();
         }

         Assert.assertNull(consumer1.receiveImmediate());

         session1.close();

         sf1.close();

         BridgeStartTest.log.info("stopping server 1");

         server1.stop();

         BridgeStartTest.log.info("stopped server 1");

         for (int i = 0; i < numMessages; i++)
         {
            ClientMessage message = session0.createMessage(false);

            message.putIntProperty(propKey, i);

            producer0.send(message);
         }

         BridgeStartTest.log.info("sent some more messages");

         server1.start();
         waitForServer(server1);

         BridgeStartTest.log.info("started server1");

         sf1 = locator.createSessionFactory(server1tc);

         session1 = sf1.createSession(false, true, true);

         consumer1 = session1.createConsumer(queueName1);

         session1.start();

         BridgeStartTest.log.info("started session");

         for (int i = 0; i < numMessages; i++)
         {
            ClientMessage message = consumer1.receive(1000);

            Assert.assertNotNull(message);

            Assert.assertEquals(i, message.getObjectProperty(propKey));

            message.acknowledge();
         }

         Assert.assertNull(consumer1.receiveImmediate());

         session1.close();

         sf1.close();

         session0.close();

         sf0.close();

         locator.close();
      }
      finally
      {
         if(locator != null)
         {
            locator.close();
         }

         server0.stop();

         server1.stop();
      }
   }

   public void testTargetServerNotAvailableNoReconnectTries() throws Exception
   {
      Map<String, Object> server0Params = new HashMap<String, Object>();
      HornetQServer server0 = createClusteredServerWithParams(isNetty(), 0, false, server0Params);

      Map<String, Object> server1Params = new HashMap<String, Object>();
      if (isNetty())
      {
         server1Params.put("port", org.hornetq.core.remoting.impl.netty.TransportConstants.DEFAULT_PORT + 1);
      }
      else
      {
         server1Params.put(TransportConstants.SERVER_ID_PROP_NAME, 1);
      }
      HornetQServer server1 = createClusteredServerWithParams(isNetty(), 1, false, server1Params);

      final String testAddress = "testAddress";
      final String queueName0 = "queue0";
      final String forwardAddress = "forwardAddress";
      final String queueName1 = "queue1";
      ServerLocator locator = null;
      try
      {
         Map<String, TransportConfiguration> connectors = new HashMap<String, TransportConfiguration>();
         TransportConfiguration server0tc = new TransportConfiguration(getConnector(), server0Params);
         TransportConfiguration server1tc = new TransportConfiguration(getConnector(), server1Params);
         connectors.put(server1tc.getName(), server1tc);

         server0.getConfiguration().setConnectorConfigurations(connectors);

         DiscoveryGroupConfiguration dg = createStaticDiscoveryGroupConfiguration(server1tc);
         server0.getConfiguration().getDiscoveryGroupConfigurations().put(dg.getName(), dg);

         final String bridgeName = "bridge1";

         BridgeConfiguration bridgeConfiguration = new BridgeConfiguration(bridgeName,
                                                                           queueName0,
                                                                           forwardAddress,
                                                                           null,
                                                                           null,
                                                                           HornetQClient.DEFAULT_CLIENT_FAILURE_CHECK_PERIOD,
                                                                           HornetQClient.DEFAULT_CONNECTION_TTL,
                                                                           1000,
                                                                           HornetQClient.DEFAULT_MAX_RETRY_INTERVAL,
                                                                           1d,
                                                                           0,
                                                                           false,
                                                                           1024,
                                                                           dg,
                                                                           false,
                                                                           ConfigurationImpl.DEFAULT_CLUSTER_USER,
                                                                           ConfigurationImpl.DEFAULT_CLUSTER_PASSWORD);

         List<BridgeConfiguration> bridgeConfigs = new ArrayList<BridgeConfiguration>();
         bridgeConfigs.add(bridgeConfiguration);
         server0.getConfiguration().setBridgeConfigurations(bridgeConfigs);

         CoreQueueConfiguration queueConfig0 = new CoreQueueConfiguration(testAddress, queueName0, null, true);
         List<CoreQueueConfiguration> queueConfigs0 = new ArrayList<CoreQueueConfiguration>();
         queueConfigs0.add(queueConfig0);
         server0.getConfiguration().setQueueConfigurations(queueConfigs0);

         CoreQueueConfiguration queueConfig1 = new CoreQueueConfiguration(forwardAddress, queueName1, null, true);
         List<CoreQueueConfiguration> queueConfigs1 = new ArrayList<CoreQueueConfiguration>();
         queueConfigs1.add(queueConfig1);
         server1.getConfiguration().setQueueConfigurations(queueConfigs1);

         // Don't start server 1 yet

         server0.start();
         waitForServer(server0);

         locator = HornetQClient.createServerLocatorWithoutHA(server0tc, server1tc);
         ClientSessionFactory sf0 = locator.createSessionFactory(server0tc);


         ClientSession session0 = sf0.createSession(false, true, true);

         ClientProducer producer0 = session0.createProducer(new SimpleString(testAddress));

         final int numMessages = 10;

         final SimpleString propKey = new SimpleString("testkey");

         for (int i = 0; i < numMessages; i++)
         {
            ClientMessage message = session0.createMessage(false);

            message.putIntProperty(propKey, i);

            producer0.send(message);
         }

         // Wait a bit
         Thread.sleep(1000);

         // JMSBridge should be stopped since retries = 0

         server1.start();
         waitForServer(server1);
         
         ClientSessionFactory sf1 = locator.createSessionFactory(server1tc);

         ClientSession session1 = sf1.createSession(false, true, true);

         ClientConsumer consumer1 = session1.createConsumer(queueName1);

         session1.start();

         // Won't be received since the bridge was deactivated
         Assert.assertNull(consumer1.receiveImmediate());

         // Now start the bridge manually

         Bridge bridge = server0.getClusterManager().getBridges().get(bridgeName);

         bridge.start();

         // Messages should now be received

         for (int i = 0; i < numMessages; i++)
         {
            ClientMessage message = consumer1.receive(1000);

            Assert.assertNotNull(message);

            Assert.assertEquals(i, message.getObjectProperty(propKey));

            message.acknowledge();
         }

         Assert.assertNull(consumer1.receiveImmediate());

         session1.close();

         sf1.close();

         session0.close();

         sf0.close();

      }
      finally
      {
         if(locator != null)
         {
            locator.close();
         }

         server0.stop();

         server1.stop();
      }

   }

   public void testManualStopStart() throws Exception
   {
      Map<String, Object> server0Params = new HashMap<String, Object>();
      HornetQServer server0 = createClusteredServerWithParams(isNetty(), 0, false, server0Params);

      Map<String, Object> server1Params = new HashMap<String, Object>();
      if (isNetty())
      {
         server1Params.put("port", org.hornetq.core.remoting.impl.netty.TransportConstants.DEFAULT_PORT + 1);
      }
      else
      {
         server1Params.put(TransportConstants.SERVER_ID_PROP_NAME, 1);
      }
      HornetQServer server1 = createClusteredServerWithParams(isNetty(), 1, false, server1Params);

      final String testAddress = "testAddress";
      final String queueName0 = "queue0";
      final String forwardAddress = "forwardAddress";
      final String queueName1 = "queue1";
      ServerLocator locator = null;
      try
      {
         Map<String, TransportConfiguration> connectors = new HashMap<String, TransportConfiguration>();
         TransportConfiguration server0tc = new TransportConfiguration(getConnector(), server0Params);
         TransportConfiguration server1tc = new TransportConfiguration(getConnector(), server1Params);
         connectors.put(server1tc.getName(), server1tc);

         server0.getConfiguration().setConnectorConfigurations(connectors);

         DiscoveryGroupConfiguration dg = createStaticDiscoveryGroupConfiguration(server1tc);
         server0.getConfiguration().getDiscoveryGroupConfigurations().put(dg.getName(), dg);

         final String bridgeName = "bridge1";

         BridgeConfiguration bridgeConfiguration = new BridgeConfiguration(bridgeName,
                                                                           queueName0,
                                                                           forwardAddress,
                                                                           null,
                                                                           null,
                                                                           HornetQClient.DEFAULT_CLIENT_FAILURE_CHECK_PERIOD,
                                                                           HornetQClient.DEFAULT_CONNECTION_TTL,
                                                                           1000,
                                                                           HornetQClient.DEFAULT_MAX_RETRY_INTERVAL,
                                                                           1d,
                                                                           1,
                                                                           true,
                                                                           1024,
                                                                           dg,
                                                                           false,
                                                                           ConfigurationImpl.DEFAULT_CLUSTER_USER,
                                                                           ConfigurationImpl.DEFAULT_CLUSTER_PASSWORD);

         List<BridgeConfiguration> bridgeConfigs = new ArrayList<BridgeConfiguration>();
         bridgeConfigs.add(bridgeConfiguration);
         server0.getConfiguration().setBridgeConfigurations(bridgeConfigs);

         CoreQueueConfiguration queueConfig0 = new CoreQueueConfiguration(testAddress, queueName0, null, true);
         List<CoreQueueConfiguration> queueConfigs0 = new ArrayList<CoreQueueConfiguration>();
         queueConfigs0.add(queueConfig0);
         server0.getConfiguration().setQueueConfigurations(queueConfigs0);

         CoreQueueConfiguration queueConfig1 = new CoreQueueConfiguration(forwardAddress, queueName1, null, true);
         List<CoreQueueConfiguration> queueConfigs1 = new ArrayList<CoreQueueConfiguration>();
         queueConfigs1.add(queueConfig1);
         server1.getConfiguration().setQueueConfigurations(queueConfigs1);

         server1.start();
         waitForServer(server1);

         server0.start();
         waitForServer(server0);

         locator = HornetQClient.createServerLocatorWithoutHA(server0tc, server1tc);
         ClientSessionFactory sf0 = locator.createSessionFactory(server0tc);


         ClientSession session0 = sf0.createSession(false, true, true);

         ClientProducer producer0 = session0.createProducer(new SimpleString(testAddress));

         final int numMessages = 10;

         final SimpleString propKey = new SimpleString("testkey");

         for (int i = 0; i < numMessages; i++)
         {
            ClientMessage message = session0.createMessage(false);

            message.putIntProperty(propKey, i);

            producer0.send(message);
         }
         ClientSessionFactory sf1 = locator.createSessionFactory(server1tc);

         ClientSession session1 = sf1.createSession(false, true, true);

         ClientConsumer consumer1 = session1.createConsumer(queueName1);

         session1.start();

         for (int i = 0; i < numMessages; i++)
         {
            ClientMessage message = consumer1.receive(1000);

            Assert.assertNotNull(message);

            Assert.assertEquals(i, message.getObjectProperty(propKey));

            message.acknowledge();
         }

         Assert.assertNull(consumer1.receiveImmediate());

         // Now stop the bridge manually

         Bridge bridge = server0.getClusterManager().getBridges().get(bridgeName);

         BridgeStartTest.log.info("stopping bridge manually");

         bridge.stop();
         
         bridge.flushExecutor();

         for (int i = numMessages; i < numMessages * 2; i++)
         {
            ClientMessage message = session0.createMessage(false);

            message.putIntProperty(propKey, i);

            producer0.send(message);
         }

         Assert.assertNull(consumer1.receiveImmediate());

         bridge.start();

         BridgeStartTest.log.info("started bridge");

         // The previous messages will get resent, but with duplicate detection they will be rejected
         // at the target

         for (int i = numMessages; i < numMessages * 2; i++)
         {
            ClientMessage message = consumer1.receive(1000);

            Assert.assertNotNull(message);

            Assert.assertEquals(i, message.getObjectProperty(propKey));

            message.acknowledge();
         }

         Assert.assertNull(consumer1.receiveImmediate());

         bridge.stop();
         
         bridge.flushExecutor();

         for (int i = 0; i < numMessages; i++)
         {
            ClientMessage message = session0.createMessage(false);

            message.putIntProperty(propKey, i);

            producer0.send(message);
         }

         Assert.assertNull(consumer1.receiveImmediate());

         bridge.start();

         for (int i = 0; i < numMessages; i++)
         {
            ClientMessage message = consumer1.receive(1000);

            Assert.assertNotNull(message);

            Assert.assertEquals(i, message.getObjectProperty(propKey));

            message.acknowledge();
         }

         Assert.assertNull(consumer1.receiveImmediate());

         session1.close();

         sf1.close();

         session0.close();

         sf0.close();
      }
      finally
      {
         if(locator != null)
         {
            locator.close();
         }
         server0.stop();

         server1.stop();
      }

   }

   @Override
   protected void setUp() throws Exception
   {
      super.setUp();
      clearData();
   }

   @Override
   protected void tearDown() throws Exception
   {
      clearData();
      super.tearDown();
   }

}
