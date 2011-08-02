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

package org.hornetq.tests.integration.cluster.failover;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import junit.framework.Assert;

import org.hornetq.api.core.HornetQException;
import org.hornetq.api.core.Pair;
import org.hornetq.api.core.SimpleString;
import org.hornetq.api.core.TransportConfiguration;
import org.hornetq.api.core.client.ClientSession;
import org.hornetq.api.core.client.ClusterTopologyListener;
import org.hornetq.api.core.client.HornetQClient;
import org.hornetq.api.core.client.ServerLocator;
import org.hornetq.api.core.client.SessionFailureListener;
import org.hornetq.core.client.impl.ClientSessionFactoryInternal;
import org.hornetq.core.client.impl.ServerLocatorInternal;
import org.hornetq.core.config.Configuration;
import org.hornetq.core.remoting.impl.invm.InVMConnector;
import org.hornetq.core.remoting.impl.invm.InVMRegistry;
import org.hornetq.core.remoting.impl.netty.NettyAcceptorFactory;
import org.hornetq.core.remoting.impl.netty.NettyConnectorFactory;
import org.hornetq.core.server.NodeManager;
import org.hornetq.core.server.impl.HornetQServerImpl;
import org.hornetq.core.server.impl.InVMNodeManager;
import org.hornetq.tests.integration.cluster.util.SameProcessHornetQServer;
import org.hornetq.tests.integration.cluster.util.TestableServer;
import org.hornetq.tests.util.ReplicatedBackupUtils;
import org.hornetq.tests.util.ServiceTestBase;

/**
 * A FailoverTestBase
 *
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 */
public abstract class FailoverTestBase extends ServiceTestBase
{
   // Constants -----------------------------------------------------

   protected static final SimpleString ADDRESS = new SimpleString("FailoverTestAddress");


   // Attributes ----------------------------------------------------

   protected TestableServer liveServer;

   protected TestableServer backupServer;

   protected Configuration backupConfig;

   protected Configuration liveConfig;

   protected NodeManager nodeManager;

   protected boolean startBackupServer = true;

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   // Public --------------------------------------------------------

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   /**
    * @param name
    */
   public FailoverTestBase(final String name)
   {
      super(name);
   }

   public FailoverTestBase()
   {
   }

   @Override
   protected void setUp() throws Exception
   {
      super.setUp();
      clearData();
      createConfigs();

      liveServer.start();

      if (backupServer != null && startBackupServer)
      {
         backupServer.start();
      }
   }

   protected TestableServer createLiveServer()
   {
      return new SameProcessHornetQServer(createInVMFailoverServer(true, liveConfig, nodeManager));
   }

   protected TestableServer createBackupServer()
   {
      return new SameProcessHornetQServer(createInVMFailoverServer(true, backupConfig, nodeManager));
   }

   protected void createConfigs() throws Exception
   {
      nodeManager = new InVMNodeManager();

      backupConfig = super.createDefaultConfig();
      backupConfig.getAcceptorConfigurations().clear();
      backupConfig.getAcceptorConfigurations().add(getAcceptorTransportConfiguration(false));
      backupConfig.setSecurityEnabled(false);
      backupConfig.setSharedStore(true);
      backupConfig.setBackup(true);
      backupConfig.setClustered(true);
      TransportConfiguration liveConnector = getConnectorTransportConfiguration(true);
      TransportConfiguration backupConnector = getConnectorTransportConfiguration(false);
      backupConfig.getConnectorConfigurations().put(liveConnector.getName(), liveConnector);
      backupConfig.getConnectorConfigurations().put(backupConnector.getName(), backupConnector);
      ReplicatedBackupUtils.createClusterConnectionConf(backupConfig, backupConnector.getName(),
                                                        liveConnector.getName());
      backupServer = createBackupServer();

      liveConfig = super.createDefaultConfig();
      liveConfig.getAcceptorConfigurations().clear();
      liveConfig.getAcceptorConfigurations().add(getAcceptorTransportConfiguration(true));
      liveConfig.setSecurityEnabled(false);
      liveConfig.setSharedStore(true);
      liveConfig.setClustered(true);
      ReplicatedBackupUtils.createClusterConnectionConf(liveConfig, liveConnector.getName());
      liveConfig.getConnectorConfigurations().put(liveConnector.getName(), liveConnector);
      liveServer = createLiveServer();
   }

   protected void createReplicatedConfigs() throws Exception
   {
      final TransportConfiguration liveConnector = getConnectorTransportConfiguration(true);
      final TransportConfiguration backupConnector = getConnectorTransportConfiguration(false);
      final TransportConfiguration backupAcceptor = getAcceptorTransportConfiguration(false);

      nodeManager = new InVMNodeManager();
      backupConfig = createDefaultConfig();
      liveConfig = createDefaultConfig();

      ReplicatedBackupUtils.configureReplicationPair(backupConfig, backupConnector, backupAcceptor, liveConfig,
                                                     liveConnector);

      backupConfig.setBindingsDirectory(backupConfig.getBindingsDirectory() + "_backup");
      backupConfig.setJournalDirectory(backupConfig.getJournalDirectory() + "_backup");
      backupConfig.setPagingDirectory(backupConfig.getPagingDirectory() + "_backup");
      backupConfig.setLargeMessagesDirectory(backupConfig.getLargeMessagesDirectory() + "_backup");
      backupConfig.setSecurityEnabled(false);

      backupServer = createBackupServer();
      backupServer.getServer().setIdentity("idBackup");

      liveConfig.getAcceptorConfigurations().clear();
      liveConfig.getAcceptorConfigurations().add(getAcceptorTransportConfiguration(true));

      liveServer = createLiveServer();
      liveServer.getServer().setIdentity("idLive");
   }

   @Override
   protected void tearDown() throws Exception
   {
      stopComponent(backupServer);
      stopComponent(liveServer);

      Assert.assertEquals(0, InVMRegistry.instance.size());

      backupServer = null;
      liveServer = null;
      nodeManager = null;

      InVMConnector.failOnCreateConnection = false;

      super.tearDown();
      try
      {
         ServerSocket serverSocket = new ServerSocket(5445);
         serverSocket.close();
      }
      catch (IOException e)
      {
         e.printStackTrace();
         System.exit(9);
      }
      try
      {
         ServerSocket serverSocket = new ServerSocket(5446);
         serverSocket.close();
      }
      catch (IOException e)
      {
         e.printStackTrace();
         System.exit(9);
      }
   }

   protected ClientSessionFactoryInternal createSessionFactoryAndWaitForTopology(ServerLocator locator,
                                                                                 int topologyMembers) throws Exception
   {
      ClientSessionFactoryInternal sf;
      CountDownLatch countDownLatch = new CountDownLatch(topologyMembers);

      locator.addClusterTopologyListener(new LatchClusterTopologyListener(countDownLatch));

      sf = (ClientSessionFactoryInternal) locator.createSessionFactory();

      assertTrue("topology members expected " + topologyMembers, countDownLatch.await(5, TimeUnit.SECONDS));
      return sf;
   }

   /**
    * This method will Waits for backup to be in the "started" state and to finish synchronization
    * with the live.
    * @param sessionFactory
    * @param seconds
    * @throws Exception
    */
   protected void waitForBackup(ClientSessionFactoryInternal sessionFactory, long seconds) throws Exception
   {
      final long toWait = seconds * 1000;
      final long time = System.currentTimeMillis();
      final HornetQServerImpl actualServer = (HornetQServerImpl)backupServer.getServer();
      while (true)
      {
         if (sessionFactory.getBackupConnector() != null && actualServer.isRemoteBackupUpToDate())
         {
            break;
         }
         if (System.currentTimeMillis() > (time + toWait))
         {
            fail("backup server never started (" + backupServer.isStarted() + "), or never finished synchronizing (" +
                     actualServer.isRemoteBackupUpToDate() + ")");
         }
         try
         {
            Thread.sleep(100);
         }
         catch (InterruptedException e)
         {
            //ignore
         }
      }

      System.out.println("Backup server state: [started=" + actualServer.isStarted() + ", upToDate=" +
               actualServer.isRemoteBackupUpToDate() + "]");
   }

   protected TransportConfiguration getNettyAcceptorTransportConfiguration(final boolean live)
   {
      if (live)
      {
         return new TransportConfiguration(NettyAcceptorFactory.class.getCanonicalName());
      }
      else
      {
         Map<String, Object> server1Params = new HashMap<String, Object>();

         server1Params.put(org.hornetq.core.remoting.impl.netty.TransportConstants.PORT_PROP_NAME,
               org.hornetq.core.remoting.impl.netty.TransportConstants.DEFAULT_PORT + 1);

         return new TransportConfiguration(NettyAcceptorFactory.class.getCanonicalName(),
               server1Params);
      }
   }

   protected TransportConfiguration getNettyConnectorTransportConfiguration(final boolean live)
   {
      if (live)
      {
         return new TransportConfiguration(NettyConnectorFactory.class.getCanonicalName());
      }
      else
      {
         Map<String, Object> server1Params = new HashMap<String, Object>();

         server1Params.put(org.hornetq.core.remoting.impl.netty.TransportConstants.PORT_PROP_NAME,
               org.hornetq.core.remoting.impl.netty.TransportConstants.DEFAULT_PORT + 1);

         return new TransportConfiguration(NettyConnectorFactory.class.getCanonicalName(), server1Params);
      }
   }

   protected abstract TransportConfiguration getAcceptorTransportConfiguration(boolean live);

   protected abstract TransportConfiguration getConnectorTransportConfiguration(final boolean live);

   protected ServerLocatorInternal getServerLocator() throws Exception
   {
      ServerLocator locator = HornetQClient.createServerLocatorWithHA(getConnectorTransportConfiguration(true), getConnectorTransportConfiguration(false));
      return (ServerLocatorInternal) locator;
   }

   protected void crash(final ClientSession... sessions) throws Exception
   {
      liveServer.crash(sessions);
   }

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------

   abstract class BaseListener implements SessionFailureListener
   {
      public void beforeReconnect(final HornetQException me)
      {
      }
   }

   class LatchClusterTopologyListener implements ClusterTopologyListener
   {
      final CountDownLatch latch;
      int liveNodes = 0;
      int backUpNodes = 0;
      List<String> liveNode = new ArrayList<String>();
      List<String> backupNode = new ArrayList<String>();

      public LatchClusterTopologyListener(CountDownLatch latch)
      {
         this.latch = latch;
      }

      public void nodeUP(String nodeID, Pair<TransportConfiguration, TransportConfiguration> connectorPair, boolean last)
      {
         if (connectorPair.a != null && !liveNode.contains(connectorPair.a.getName()))
         {
            liveNode.add(connectorPair.a.getName());
            latch.countDown();
         }
         if (connectorPair.b != null && !backupNode.contains(connectorPair.b.getName()))
         {
            backupNode.add(connectorPair.b.getName());
            latch.countDown();
         }
      }

      public void nodeDown(String nodeID)
      {
         //To change body of implemented methods use File | Settings | File Templates.
      }
   }


}