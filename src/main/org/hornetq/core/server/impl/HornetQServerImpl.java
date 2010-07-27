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

package org.hornetq.core.server.impl;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import javax.management.MBeanServer;

import org.hornetq.api.core.HornetQException;
import org.hornetq.api.core.Pair;
import org.hornetq.api.core.SimpleString;
import org.hornetq.core.client.impl.ClientSessionFactoryImpl;
import org.hornetq.core.config.Configuration;
import org.hornetq.core.config.CoreQueueConfiguration;
import org.hornetq.core.config.DivertConfiguration;
import org.hornetq.core.config.impl.ConfigurationImpl;
import org.hornetq.core.deployers.Deployer;
import org.hornetq.core.deployers.DeploymentManager;
import org.hornetq.core.deployers.impl.AddressSettingsDeployer;
import org.hornetq.core.deployers.impl.BasicUserCredentialsDeployer;
import org.hornetq.core.deployers.impl.FileDeploymentManager;
import org.hornetq.core.deployers.impl.QueueDeployer;
import org.hornetq.core.deployers.impl.SecurityDeployer;
import org.hornetq.core.filter.Filter;
import org.hornetq.core.filter.impl.FilterImpl;
import org.hornetq.core.journal.JournalLoadInformation;
import org.hornetq.core.journal.impl.SyncSpeedTest;
import org.hornetq.core.logging.Logger;
import org.hornetq.core.management.impl.HornetQServerControlImpl;
import org.hornetq.core.paging.PagingManager;
import org.hornetq.core.paging.impl.PagingManagerImpl;
import org.hornetq.core.paging.impl.PagingStoreFactoryNIO;
import org.hornetq.core.persistence.GroupingInfo;
import org.hornetq.core.persistence.QueueBindingInfo;
import org.hornetq.core.persistence.StorageManager;
import org.hornetq.core.persistence.config.PersistedAddressSetting;
import org.hornetq.core.persistence.config.PersistedRoles;
import org.hornetq.core.persistence.impl.journal.JournalStorageManager;
import org.hornetq.core.persistence.impl.nullpm.NullStorageManager;
import org.hornetq.core.postoffice.Binding;
import org.hornetq.core.postoffice.DuplicateIDCache;
import org.hornetq.core.postoffice.PostOffice;
import org.hornetq.core.postoffice.impl.DivertBinding;
import org.hornetq.core.postoffice.impl.LocalQueueBinding;
import org.hornetq.core.postoffice.impl.PostOfficeImpl;
import org.hornetq.core.protocol.core.Channel;
import org.hornetq.core.remoting.server.RemotingService;
import org.hornetq.core.remoting.server.impl.RemotingServiceImpl;
import org.hornetq.core.replication.ReplicationEndpoint;
import org.hornetq.core.replication.ReplicationManager;
import org.hornetq.core.security.CheckType;
import org.hornetq.core.security.Role;
import org.hornetq.core.security.SecurityStore;
import org.hornetq.core.security.impl.SecurityStoreImpl;
import org.hornetq.core.server.ActivateCallback;
import org.hornetq.core.server.Divert;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.core.server.MemoryManager;
import org.hornetq.core.server.Queue;
import org.hornetq.core.server.QueueFactory;
import org.hornetq.core.server.ServerSession;
import org.hornetq.core.server.cluster.ClusterManager;
import org.hornetq.core.server.cluster.LockFile;
import org.hornetq.core.server.cluster.Transformer;
import org.hornetq.core.server.cluster.impl.ClusterManagerImpl;
import org.hornetq.core.server.cluster.impl.LockFileImpl;
import org.hornetq.core.server.group.GroupingHandler;
import org.hornetq.core.server.group.impl.GroupBinding;
import org.hornetq.core.server.group.impl.GroupingHandlerConfiguration;
import org.hornetq.core.server.group.impl.LocalGroupingHandler;
import org.hornetq.core.server.group.impl.RemoteGroupingHandler;
import org.hornetq.core.server.management.ManagementService;
import org.hornetq.core.server.management.impl.ManagementServiceImpl;
import org.hornetq.core.settings.HierarchicalRepository;
import org.hornetq.core.settings.impl.AddressSettings;
import org.hornetq.core.settings.impl.HierarchicalObjectRepository;
import org.hornetq.core.transaction.ResourceManager;
import org.hornetq.core.transaction.impl.ResourceManagerImpl;
import org.hornetq.core.version.Version;
import org.hornetq.spi.core.logging.LogDelegateFactory;
import org.hornetq.spi.core.protocol.RemotingConnection;
import org.hornetq.spi.core.protocol.SessionCallback;
import org.hornetq.spi.core.security.HornetQSecurityManager;
import org.hornetq.utils.ExecutorFactory;
import org.hornetq.utils.HornetQThreadFactory;
import org.hornetq.utils.OrderedExecutorFactory;
import org.hornetq.utils.SecurityFormatter;
import org.hornetq.utils.UUID;
import org.hornetq.utils.UUIDGenerator;
import org.hornetq.utils.VersionLoader;

/**
 * The HornetQ server implementation
 *
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 * @author <a href="mailto:ataylor@redhat.com>Andy Taylor</a>
 * @version <tt>$Revision: 3543 $</tt> <p/> $Id: ServerPeer.java 3543 2008-01-07 22:31:58Z clebert.suconic@jboss.com $
 */
public class HornetQServerImpl implements HornetQServer
{
   // Constants
   // ------------------------------------------------------------------------------------

   private static final Logger log = Logger.getLogger(HornetQServerImpl.class);

   // Static
   // ---------------------------------------------------------------------------------------

   // Attributes
   // -----------------------------------------------------------------------------------

   private volatile SimpleString nodeID;

   private volatile UUID uuid;
   
   private final Version version;

   private final HornetQSecurityManager securityManager;

   private final Configuration configuration;

   private final MBeanServer mbeanServer;

   private volatile boolean started;

   private volatile SecurityStore securityStore;

   private final HierarchicalRepository<AddressSettings> addressSettingsRepository;

   private volatile QueueFactory queueFactory;

   private volatile PagingManager pagingManager;

   private volatile PostOffice postOffice;

   private volatile ExecutorService threadPool;

   private volatile ScheduledExecutorService scheduledPool;

   private volatile ExecutorFactory executorFactory;

   private final HierarchicalRepository<Set<Role>> securityRepository;

   private volatile ResourceManager resourceManager;

   private volatile HornetQServerControlImpl messagingServerControl;

   private volatile ClusterManager clusterManager;

   private volatile StorageManager storageManager;

   private volatile RemotingService remotingService;

   private volatile ManagementService managementService;

   private MemoryManager memoryManager;

   private volatile DeploymentManager deploymentManager;

   private Deployer basicUserCredentialsDeployer;

   private Deployer addressSettingsDeployer;

   private Deployer queueDeployer;

   private Deployer securityDeployer;

   private final Map<String, ServerSession> sessions = new ConcurrentHashMap<String, ServerSession>();

   private final Object initialiseLock = new Object();

   private boolean initialised;

  // private FailoverManager replicationFailoverManager;

   private ReplicationManager replicationManager;

   private ReplicationEndpoint replicationEndpoint;

   private final Set<ActivateCallback> activateCallbacks = new HashSet<ActivateCallback>();

   private volatile GroupingHandler groupingHandler;

   // Constructors
   // ---------------------------------------------------------------------------------

   public HornetQServerImpl()
   {
      this(null, null, null);
   }

   public HornetQServerImpl(final Configuration configuration)
   {
      this(configuration, null, null);
   }

   public HornetQServerImpl(final Configuration configuration, final MBeanServer mbeanServer)
   {
      this(configuration, mbeanServer, null);
   }

   public HornetQServerImpl(final Configuration configuration, final HornetQSecurityManager securityManager)
   {
      this(configuration, null, securityManager);
   }

   public HornetQServerImpl(Configuration configuration,
                            MBeanServer mbeanServer,
                            final HornetQSecurityManager securityManager)
   {
      if (configuration == null)
      {
         configuration = new ConfigurationImpl();
      }

      if (mbeanServer == null)
      {
         // Just use JVM mbean server
         mbeanServer = ManagementFactory.getPlatformMBeanServer();
      }

      // We need to hard code the version information into a source file

      version = VersionLoader.getVersion();

      this.configuration = configuration;

      this.mbeanServer = mbeanServer;

      this.securityManager = securityManager;

      addressSettingsRepository = new HierarchicalObjectRepository<AddressSettings>();

      addressSettingsRepository.setDefault(new AddressSettings());

      securityRepository = new HierarchicalObjectRepository<Set<Role>>();

      securityRepository.setDefault(new HashSet<Role>());

   }

   // lifecycle methods
   // ----------------------------------------------------------------

   private interface Activation extends Runnable
   {
      void close() throws Exception;
   }

   /*
    * Can be overridden for tests
    */
   protected LockFile createLockFile(final String fileName, final String directory)
   {
      return new LockFileImpl(fileName, directory);
   }

   private class NoSharedStoreLiveActivation implements Activation
   {
      LockFile liveLock;

      public void run()
      {
         try
         {
            File journalDir = new File(configuration.getJournalDirectory());

            if (!journalDir.exists())
            {
               if (configuration.isCreateJournalDir())
               {
                  journalDir.mkdirs();
               }
               else
               {
                  throw new IllegalArgumentException("Directory " + journalDir +
                                                     " does not exist and will not create it");
               }
            }


            // We now load the node id file, creating it, if it doesn't exist yet
            File nodeIDFile = new File(configuration.getJournalDirectory(), "node.id");

            if (!nodeIDFile.exists())
            {
               // We use another file lock to prevent a backup reading it before it is complete

               LockFile nodeIDLockFile = createLockFile("nodeid.lock", configuration.getJournalDirectory());

               nodeIDLockFile.lock();

               OutputStream os = null;

               try
               {
                  os = new BufferedOutputStream(new FileOutputStream(nodeIDFile));

                  uuid = UUIDGenerator.getInstance().generateUUID();

                  nodeID = new SimpleString(uuid.toString());

                  os.write(uuid.asBytes());

                  log.info("Wrote node id, it is " + nodeID);
               }
               finally
               {
                  if (os != null)
                  {
                     os.close();
                  }
               }

               nodeIDLockFile.unlock();
            }
            else
            {
               // Read it

               readNodeID(nodeIDFile);
            }

            initialisePart1();

            initialisePart2();

            log.info("Server is now live");
         }
         catch (Exception e)
         {
            log.error("Failure in initialisation", e);
         }
      }

      public void close() throws Exception
      {
         if (liveLock != null)
         {
            // We need to delete the file too, otherwise the backup will failover when we shutdown or if the backup is
            // started before the live

            File liveFile = new File(configuration.getJournalDirectory(), "live.lock");

            liveFile.delete();

            liveLock.unlock();

         }
      }
   }

   private class SharedStoreLiveActivation implements Activation
   {
      LockFile liveLock;

      public void run()
      {
         try
         {
            log.info("Waiting to obtain live lock");

            File journalDir = new File(configuration.getJournalDirectory());

            if (!journalDir.exists())
            {
               if (configuration.isCreateJournalDir())
               {
                  journalDir.mkdirs();
               }
               else
               {
                  throw new IllegalArgumentException("Directory " + journalDir +
                                                     " does not exist and will not create it");
               }
            }

            liveLock = createLockFile("live.lock", configuration.getJournalDirectory());

            liveLock.lock();

            log.info("Obtained live lock");

            // We now load the node id file, creating it, if it doesn't exist yet
            File nodeIDFile = new File(configuration.getJournalDirectory(), "node.id");

            if (!nodeIDFile.exists())
            {
               // We use another file lock to prevent a backup reading it before it is complete

               LockFile nodeIDLockFile = createLockFile("nodeid.lock", configuration.getJournalDirectory());

               nodeIDLockFile.lock();

               OutputStream os = null;

               try
               {
                  os = new BufferedOutputStream(new FileOutputStream(nodeIDFile));

                  uuid = UUIDGenerator.getInstance().generateUUID();

                  nodeID = new SimpleString(uuid.toString());

                  os.write(uuid.asBytes());

                  log.info("Wrote node id, it is " + nodeID);
               }
               finally
               {
                  if (os != null)
                  {
                     os.close();
                  }
               }

               nodeIDLockFile.unlock();
            }
            else
            {
               // Read it

               readNodeID(nodeIDFile);
            }

            initialisePart1();

            initialisePart2();
            
            log.info("Server is now live");
         }
         catch (Exception e)
         {
            log.error("Failure in initialisation", e);
         }
      }

      public void close() throws Exception
      {
         if (liveLock != null)
         {
            // We need to delete the file too, otherwise the backup will failover when we shutdown or if the backup is
            // started before the live

            File liveFile = new File(configuration.getJournalDirectory(), "live.lock");

            liveFile.delete();

            liveLock.unlock();

         }
      }
   }

   private void readNodeID(final File nodeIDFile) throws Exception
   {
      // Read it
      InputStream is = null;

      try
      {
         is = new BufferedInputStream(new FileInputStream(nodeIDFile));

         byte[] bytes = new byte[16];

         int read = 0;

         while (read < 16)
         {
            int r = is.read(bytes, read, 16 - read);

            if (r <= 0)
            {
               throw new IllegalStateException("Cannot read node id file, perhaps it is corrupt?");
            }

            read += r;
         }

         uuid = new UUID(UUID.TYPE_TIME_BASED, bytes);

         nodeID = new SimpleString(uuid.toString());

         log.info("Read node id, it is " + nodeID);
      }
      finally
      {
         if (is != null)
         {
            is.close();
         }
      }
   }

   private class SharedStoreBackupActivation implements Activation
   {
      LockFile backupLock;

      LockFile liveLock;

      public void run()
      {
         try
         {
            backupLock = createLockFile("backup.lock", configuration.getJournalDirectory());

            log.info("Waiting to become backup node");

            backupLock.lock();

            log.info("** got backup lock");

            // We load the node id from the file in the journal dir - if the backup is started before live and live has
            // never been started before it may not exist yet, so
            // we wait for it

            File nodeIDFile = new File(configuration.getJournalDirectory(), "node.id");

            while (true)
            {
               // We also need to create another lock file for the node.id file since we don't want to see any partially
               // written
               // node id if the live node is still creating it.
               // Also renaming is not atomic necessarily so we can't use a write and rename strategy safely

               LockFile nodeIDLockFile = createLockFile("nodeid.lock", configuration.getJournalDirectory());

               nodeIDLockFile.lock();

               if (!nodeIDFile.exists())
               {
                  nodeIDLockFile.unlock();

                  Thread.sleep(2000);

                  continue;
               }

               nodeIDLockFile.unlock();

               break;
            }

            readNodeID(nodeIDFile);

            log.info("Read node id " + nodeID);

            initialisePart1();
            
            //TODO TODO at this point the clustermanager needs to announce it's presence so the cluster can know about the backup
            
            // We now look for the live.lock file - if it doesn't exist it means the live isn't started yet, so we wait
            // for that
            
            while (true)
            {
               File liveLockFile = new File(configuration.getJournalDirectory(), "live.lock");

               while (!liveLockFile.exists())
               {
                  log.info("Waiting for server live lock file. Live server is not started");

                  Thread.sleep(2000);
               }

               liveLock = createLockFile("live.lock", configuration.getJournalDirectory());

               log.info("Live server is up - waiting for failover");

               liveLock.lock();

               // We need to test if the file exists again, since the live might have shutdown
               if (!liveLockFile.exists())
               {
                  liveLock.unlock();

                  continue;
               }

               log.info("Obtained live lock");
               
               // Announce presence of live node to cluster
               
               
               break;
            }
            
            configuration.setBackup(false);
            
            clusterManager.activate();

            initialisePart2();

            log.info("Server is now live");

            backupLock.unlock();
         }
         catch (InterruptedException e)
         {
            // This can occur when closing if the thread is blocked - it's ok
         }
         catch (Exception e)
         {
            log.error("Failure in initialisation", e);
         }
      }

      public void close() throws Exception
      {
         long timeout = 30000;

         long start = System.currentTimeMillis();

         while (backupActivationThread.isAlive() && System.currentTimeMillis() - start < timeout)
         {
            backupActivationThread.interrupt();

            Thread.sleep(1000);
         }

         if (System.currentTimeMillis() - start >= timeout)
         {
            log.warn("Timed out waiting for backup activation to exit");
         }

         if (liveLock != null)
         {
            liveLock.unlock();
         }

         if (backupLock != null)
         {
            backupLock.unlock();
         }

      }
   }

   private class SharedNothingBackupActivation implements Activation
   {
      public void run()
      {
         try
         {
            // TODO

            // Try-Connect to live server using live-connector-ref

            // sit in loop and try and connect, if server is not live then it will return NOT_LIVE
         }
         catch (Exception e)
         {
            log.error("Failure in initialisation", e);
         }
      }

      public void close() throws Exception
      {
      }
   }

   private Thread backupActivationThread;

   private Activation activation;

   public synchronized void start() throws Exception
   {
      initialiseLogging();

      if (started)
      {
         HornetQServerImpl.log.info((configuration.isBackup() ? "backup" : "live") + " is already started, ignoring the call to start..");
         return;
      }

      HornetQServerImpl.log.info((configuration.isBackup() ? "backup" : "live") + " server is starting..");

      if (configuration.isRunSyncSpeedTest())
      {
         SyncSpeedTest test = new SyncSpeedTest();

         test.run();
      }

      if (!configuration.isBackup())
      {
         if (configuration.isSharedStore())
         {
            activation = new SharedStoreLiveActivation();

            // This should block until the lock is got

            activation.run();
         }
         else
         {
            activation = new NoSharedStoreLiveActivation();

            activation.run();
         }
      }

      started = true;

      HornetQServerImpl.log.info("HornetQ Server version " + getVersion().getFullVersion() + " [" + nodeID + "] started");

      if (configuration.isBackup())
      {
         if (configuration.isSharedStore())
         {
            activation = new SharedStoreBackupActivation();
         }
         else
         {
            // Replicated

            activation = new SharedNothingBackupActivation();
         }

         backupActivationThread = new Thread(activation);

         backupActivationThread.start();
      }

   }

   @Override
   protected void finalize() throws Throwable
   {
      if (started)
      {
         HornetQServerImpl.log.warn("HornetQServer is being finalized and has not been stopped. Please remember to stop the " + "server before letting it go out of scope");

         stop();
      }

      super.finalize();
   }

   public void stop() throws Exception
   {
      System.out.println("*** stop called on server");

      System.out.flush();

      synchronized (this)
      {
         if (!started)
         {
            return;
         }

         if (clusterManager != null)
         {
            clusterManager.stop();
         }

         if (groupingHandler != null)
         {
            managementService.removeNotificationListener(groupingHandler);
            groupingHandler = null;
         }
      }

      // we stop the remoting service outside a lock
      remotingService.stop();

      synchronized (this)
      {
         // Stop the deployers
         if (configuration.isFileDeploymentEnabled())
         {
            basicUserCredentialsDeployer.stop();

            addressSettingsDeployer.stop();

            if (queueDeployer != null)
            {
               queueDeployer.stop();
            }

            if (securityDeployer != null)
            {
               securityDeployer.stop();
            }

            deploymentManager.stop();
         }

         managementService.unregisterServer();

         managementService.stop();

         if (pagingManager != null)
         {
            pagingManager.stop();
         }

         if (storageManager != null)
         {
            storageManager.stop();
         }

         if (replicationManager != null)
         {
            replicationManager.stop();
            replicationManager = null;
         }

         if (replicationEndpoint != null)
         {
            replicationEndpoint.stop();
            replicationEndpoint = null;
         }

         if (securityManager != null)
         {
            securityManager.stop();
         }

         if (resourceManager != null)
         {
            resourceManager.stop();
         }

         if (postOffice != null)
         {
            postOffice.stop();
         }

         List<Runnable> tasks = scheduledPool.shutdownNow();

         for (Runnable task : tasks)
         {
            HornetQServerImpl.log.debug("Waiting for " + task);
         }

         threadPool.shutdown();

         scheduledPool = null;

         if (memoryManager != null)
         {
            memoryManager.stop();
         }

         addressSettingsRepository.clear();

         securityRepository.clear();

         pagingManager = null;
         securityStore = null;
         resourceManager = null;
         postOffice = null;
         securityStore = null;
         queueFactory = null;
         resourceManager = null;
         messagingServerControl = null;
         memoryManager = null;

         sessions.clear();

         started = false;
         initialised = false;
         // to display in the log message
         SimpleString tempNodeID = nodeID;
         nodeID = null;

         if (activation != null)
         {
            activation.close();
         }

         if (backupActivationThread != null)
         {
            backupActivationThread.join();
         }

         HornetQServerImpl.log.info("HornetQ Server version " + getVersion().getFullVersion() + " [" + tempNodeID + "] stopped");

         Logger.reset();
      }

      try
      {
         if (!threadPool.awaitTermination(30000, TimeUnit.MILLISECONDS))
         {
            HornetQServerImpl.log.warn("Timed out waiting for pool to terminate");
         }
      }
      catch (InterruptedException e)
      {
         // Ignore
      }
      threadPool = null;
   }

   // HornetQServer implementation
   // -----------------------------------------------------------

   public Configuration getConfiguration()
   {
      return configuration;
   }

   public MBeanServer getMBeanServer()
   {
      return mbeanServer;
   }

   public RemotingService getRemotingService()
   {
      return remotingService;
   }

   public StorageManager getStorageManager()
   {
      return storageManager;
   }

   public HornetQSecurityManager getSecurityManager()
   {
      return securityManager;
   }

   public ManagementService getManagementService()
   {
      return managementService;
   }

   public HierarchicalRepository<Set<Role>> getSecurityRepository()
   {
      return securityRepository;
   }

   public HierarchicalRepository<AddressSettings> getAddressSettingsRepository()
   {
      return addressSettingsRepository;
   }

   public DeploymentManager getDeploymentManager()
   {
      return deploymentManager;
   }

   public ResourceManager getResourceManager()
   {
      return resourceManager;
   }

   public Version getVersion()
   {
      return version;
   }

   public synchronized boolean isStarted()
   {
      return started;
   }

   public ClusterManager getClusterManager()
   {
      return clusterManager;
   }

   public ServerSession createSession(final String name,
                                      final String username,
                                      final String password,
                                      final int minLargeMessageSize,
                                      final RemotingConnection connection,
                                      final boolean autoCommitSends,
                                      final boolean autoCommitAcks,
                                      final boolean preAcknowledge,
                                      final boolean xa,
                                      final String defaultAddress,
                                      final SessionCallback callback) throws Exception
   {

      if (securityStore != null)
      {
         securityStore.authenticate(username, password);
      }

      final ServerSessionImpl session = new ServerSessionImpl(name,
                                                              username,
                                                              password,
                                                              minLargeMessageSize,
                                                              autoCommitSends,
                                                              autoCommitAcks,
                                                              preAcknowledge,
                                                              configuration.isPersistDeliveryCountBeforeDelivery(),
                                                              xa,
                                                              connection,
                                                              storageManager,
                                                              postOffice,
                                                              resourceManager,
                                                              securityStore,
                                                              managementService,
                                                              this,
                                                              configuration.getManagementAddress(),
                                                              defaultAddress == null ? null
                                                                                    : new SimpleString(defaultAddress),
                                                              callback);

      sessions.put(name, session);

      return session;
   }

   public synchronized ReplicationEndpoint connectToReplicationEndpoint(final Channel channel) throws Exception
   {
      if (!configuration.isBackup())
      {
         throw new HornetQException(HornetQException.ILLEGAL_STATE, "Connected server is not a backup server");
      }

      if (replicationEndpoint.getChannel() != null)
      {
         throw new HornetQException(HornetQException.ILLEGAL_STATE,
                                    "Backup replication server is already connected to another server");
      }

      replicationEndpoint.setChannel(channel);

      return replicationEndpoint;
   }

   public void removeSession(final String name) throws Exception
   {
      sessions.remove(name);
   }

   public synchronized List<ServerSession> getSessions(final String connectionID)
   {
      Set<Entry<String, ServerSession>> sessionEntries = sessions.entrySet();
      List<ServerSession> matchingSessions = new ArrayList<ServerSession>();
      for (Entry<String, ServerSession> sessionEntry : sessionEntries)
      {
         ServerSession serverSession = sessionEntry.getValue();
         if (serverSession.getConnectionID().toString().equals(connectionID))
         {
            matchingSessions.add(serverSession);
         }
      }
      return matchingSessions;
   }

   public synchronized Set<ServerSession> getSessions()
   {
      return new HashSet<ServerSession>(sessions.values());
   }

   // TODO - should this really be here?? It's only used in tests
   public boolean isInitialised()
   {
      synchronized (initialiseLock)
      {
         return initialised;
      }
   }

   public HornetQServerControlImpl getHornetQServerControl()
   {
      return messagingServerControl;
   }

   public int getConnectionCount()
   {
      return remotingService.getConnections().size();
   }

   public PostOffice getPostOffice()
   {
      return postOffice;
   }

   public QueueFactory getQueueFactory()
   {
      return queueFactory;
   }

   public SimpleString getNodeID()
   {
      return nodeID;
   }

   public Queue createQueue(final SimpleString address,
                            final SimpleString queueName,
                            final SimpleString filterString,
                            final boolean durable,
                            final boolean temporary) throws Exception
   {
      return createQueue(address, queueName, filterString, durable, temporary, false);
   }

   public Queue deployQueue(final SimpleString address,
                            final SimpleString queueName,
                            final SimpleString filterString,
                            final boolean durable,
                            final boolean temporary) throws Exception
   {
      log.info("trying to deploy queue " + queueName);

      return createQueue(address, queueName, filterString, durable, temporary, true);
   }

   public void destroyQueue(final SimpleString queueName, final ServerSession session) throws Exception
   {
      Binding binding = postOffice.getBinding(queueName);

      if (binding == null)
      {
         throw new HornetQException(HornetQException.QUEUE_DOES_NOT_EXIST, "No such queue " + queueName);
      }

      Queue queue = (Queue)binding.getBindable();

      if (queue.getConsumerCount() != 0)
      {
         throw new HornetQException(HornetQException.ILLEGAL_STATE, "Cannot delete queue " + queue.getName() +
                                                                    " on binding " +
                                                                    queueName +
                                                                    " - it has consumers = " +
                                                                    binding.getClass().getName());
      }

      if (session != null)
      {
         if (queue.isDurable())
         {
            // make sure the user has privileges to delete this queue
            securityStore.check(binding.getAddress(), CheckType.DELETE_DURABLE_QUEUE, session);
         }
         else
         {
            securityStore.check(binding.getAddress(), CheckType.DELETE_NON_DURABLE_QUEUE, session);
         }
      }

      queue.deleteAllReferences();

      if (queue.isDurable())
      {
         storageManager.deleteQueueBinding(queue.getID());
      }

      postOffice.removeBinding(queueName);
   }

   public synchronized void registerActivateCallback(final ActivateCallback callback)
   {
      activateCallbacks.add(callback);
   }

   public synchronized void unregisterActivateCallback(final ActivateCallback callback)
   {
      activateCallbacks.remove(callback);
   }

   public synchronized ExecutorFactory getExecutorFactory()
   {
      return executorFactory;
   }

   public void setGroupingHandler(final GroupingHandler groupingHandler)
   {
      this.groupingHandler = groupingHandler;
   }

   public GroupingHandler getGroupingHandler()
   {
      return groupingHandler;
   }

   public ReplicationEndpoint getReplicationEndpoint()
   {
      return replicationEndpoint;
   }

   public ReplicationManager getReplicationManager()
   {
      return replicationManager;
   }

   // Public
   // ---------------------------------------------------------------------------------------

   // Package protected
   // ----------------------------------------------------------------------------

   // Protected
   // ------------------------------------------------------------------------------------

   /**
    * Protected so tests can change this behaviour
    * @param backupConnector
    */
//   protected FailoverManagerImpl createBackupConnectionFailoverManager(final TransportConfiguration backupConnector,
//                                                                       final ExecutorService threadPool,
//                                                                       final ScheduledExecutorService scheduledPool)
//   {
//      return new FailoverManagerImpl((ClientSessionFactory)null,
//                                     backupConnector,
//                                     null,
//                                     false,
//                                     HornetQClient.DEFAULT_CALL_TIMEOUT,
//                                     HornetQClient.DEFAULT_CLIENT_FAILURE_CHECK_PERIOD,
//                                     HornetQClient.DEFAULT_CONNECTION_TTL,
//                                     0,
//                                     1.0d,
//                                     0,
//                                     1,
//                                     false,
//                                     threadPool,
//                                     scheduledPool,
//                                     null);
//   }

   protected PagingManager createPagingManager()
   {
      return new PagingManagerImpl(new PagingStoreFactoryNIO(configuration.getPagingDirectory(),
                                                             executorFactory,
                                                             configuration.isJournalSyncNonTransactional()),
                                   storageManager,
                                   addressSettingsRepository);
   }

   /** 
    * This method is protected as it may be used as a hook for creating a custom storage manager (on tests for instance) 
    */
   protected StorageManager createStorageManager()
   {
      if (configuration.isPersistenceEnabled())
      {
         return new JournalStorageManager(configuration, executorFactory, replicationManager);
      }
      else
      {
         return new NullStorageManager();
      }
   }

   // Private
   // --------------------------------------------------------------------------------------

   // private boolean startReplication() throws Exception
   // {
   // String backupConnectorName = configuration.getBackupConnectorName();
   //
   // if (!configuration.isSharedStore() && backupConnectorName != null)
   // {
   // TransportConfiguration backupConnector = configuration.getConnectorConfigurations().get(backupConnectorName);
   //
   // if (backupConnector == null)
   // {
   // HornetQServerImpl.log.warn("connector with name '" + backupConnectorName +
   // "' is not defined in the configuration.");
   // }
   // else
   // {
   //
   // replicationFailoverManager = createBackupConnectionFailoverManager(backupConnector,
   // threadPool,
   // scheduledPool);
   //
   // replicationManager = new ReplicationManagerImpl(replicationFailoverManager, executorFactory);
   // replicationManager.start();
   // }
   // }
   //
   // return true;
   // }

   private void callActivateCallbacks()
   {
      for (ActivateCallback callback : activateCallbacks)
      {
         callback.activated();
      }
   }

   private void callPreActiveCallbacks()
   {
      for (ActivateCallback callback : activateCallbacks)
      {
         callback.preActivate();
      }
   }

   public synchronized boolean checkActivate() throws Exception
   {
      if (configuration.isBackup())
      {
         // Handle backup server activation

         if (!configuration.isSharedStore())
         {
            if (replicationEndpoint == null)
            {
               HornetQServerImpl.log.warn("There is no replication endpoint, can't activate this backup server");

               throw new HornetQException(HornetQException.INTERNAL_ERROR, "Can't activate the server");
            }

            replicationEndpoint.stop();
         }

         // Complete the startup procedure

         HornetQServerImpl.log.info("Activating backup server");

         configuration.setBackup(false);

         initialisePart2();
      }

      return true;
   }

   private class FileActivateRunner implements Runnable
   {
      public void run()
      {

      }
   }

   private void initialiseLogging()
   {
      LogDelegateFactory logDelegateFactory = (LogDelegateFactory)instantiateInstance(configuration.getLogDelegateFactoryClassName());

      Logger.setDelegateFactory(logDelegateFactory);
   }

   /*
    * Start everything apart from RemotingService and loading the data
    */
   private void initialisePart1() throws Exception
   {
      // Create the pools - we have two pools - one for non scheduled - and another for scheduled

      ThreadFactory tFactory = new HornetQThreadFactory("HornetQ-server-threads" + System.identityHashCode(this),
                                                        false,
                                                        getThisClassLoader());

      if (configuration.getThreadPoolMaxSize() == -1)
      {
         threadPool = Executors.newCachedThreadPool(tFactory);
      }
      else
      {
         threadPool = Executors.newFixedThreadPool(configuration.getThreadPoolMaxSize(), tFactory);
      }

      executorFactory = new OrderedExecutorFactory(threadPool);

      scheduledPool = new ScheduledThreadPoolExecutor(configuration.getScheduledThreadPoolMaxSize(),
                                                      new HornetQThreadFactory("HornetQ-scheduled-threads",
                                                                               false,
                                                                               getThisClassLoader()));

      managementService = new ManagementServiceImpl(mbeanServer, configuration);

      remotingService = new RemotingServiceImpl(configuration, this, managementService, scheduledPool);

      if (configuration.getMemoryMeasureInterval() != -1)
      {
         memoryManager = new MemoryManagerImpl(configuration.getMemoryWarningThreshold(),
                                               configuration.getMemoryMeasureInterval());

         memoryManager.start();
      }

      // Create the hard-wired components

      if (configuration.isFileDeploymentEnabled())
      {
         deploymentManager = new FileDeploymentManager(configuration.getFileDeployerScanPeriod());
      }

      callPreActiveCallbacks();

      // startReplication();

      storageManager = createStorageManager();

      if (ConfigurationImpl.DEFAULT_CLUSTER_USER.equals(configuration.getClusterUser()) && ConfigurationImpl.DEFAULT_CLUSTER_PASSWORD.equals(configuration.getClusterPassword()))
      {
         log.warn("Security risk! It has been detected that the cluster admin user and password " + "have not been changed from the installation default. "
                  + "Please see the HornetQ user guide, cluster chapter, for instructions on how to do this.");
      }

      securityStore = new SecurityStoreImpl(securityRepository,
                                            securityManager,
                                            configuration.getSecurityInvalidationInterval(),
                                            configuration.isSecurityEnabled(),
                                            configuration.getClusterUser(),
                                            configuration.getClusterPassword(),
                                            managementService);

      queueFactory = new QueueFactoryImpl(executorFactory, scheduledPool, addressSettingsRepository, storageManager);

      pagingManager = createPagingManager();

      resourceManager = new ResourceManagerImpl((int)(configuration.getTransactionTimeout() / 1000),
                                                configuration.getTransactionTimeoutScanPeriod(),
                                                scheduledPool);
      postOffice = new PostOfficeImpl(this,
                                      storageManager,
                                      pagingManager,
                                      queueFactory,
                                      managementService,
                                      configuration.getMessageExpiryScanPeriod(),
                                      configuration.getMessageExpiryThreadPriority(),
                                      configuration.isWildcardRoutingEnabled(),
                                      configuration.getIDCacheSize(),
                                      configuration.isPersistIDCache(),
                                      addressSettingsRepository);

      messagingServerControl = managementService.registerServer(postOffice,
                                                                storageManager,
                                                                configuration,
                                                                addressSettingsRepository,
                                                                securityRepository,
                                                                resourceManager,
                                                                remotingService,
                                                                this,
                                                                queueFactory,
                                                                scheduledPool,
                                                                pagingManager,
                                                                configuration.isBackup());

      // Address settings need to deployed initially, since they're require on paging manager.start()

      if (configuration.isFileDeploymentEnabled())
      {
         addressSettingsDeployer = new AddressSettingsDeployer(deploymentManager, addressSettingsRepository);

         addressSettingsDeployer.start();
      }
      else
      {
         deployAddressSettingsFromConfiguration();
      }

      storageManager.start();

      if (securityManager != null)
      {
         securityManager.start();
      }

      postOffice.start();

      pagingManager.start();

      managementService.start();

      resourceManager.start();

      // Deploy all security related config
      if (configuration.isFileDeploymentEnabled())
      {
         basicUserCredentialsDeployer = new BasicUserCredentialsDeployer(deploymentManager, securityManager);

         basicUserCredentialsDeployer.start();

         if (securityManager != null)
         {
            securityDeployer = new SecurityDeployer(deploymentManager, securityRepository);

            securityDeployer.start();
         }
      }
      else
      {
         deploySecurityFromConfiguration();
      }

      deployGroupingHandlerConfiguration(configuration.getGroupingHandlerConfiguration());

      // This can't be created until node id is set
      clusterManager = new ClusterManagerImpl(executorFactory,
                                              this,
                                              postOffice,
                                              scheduledPool,
                                              managementService,
                                              configuration,
                                              uuid,
                                              configuration.isBackup(),
                                              configuration.isClustered());

   }

   /*
    * Load the data, and start remoting service so clients can connect
    */
   private void initialisePart2() throws Exception
   {
      // Load the journal and populate queues, transactions and caches in memory

      JournalLoadInformation[] journalInfo = loadJournals();

      compareJournals(journalInfo);

      pagingManager.resumeDepages();

      final ServerInfo dumper = new ServerInfo(this, pagingManager);

      long dumpInfoInterval = configuration.getServerDumpInterval();

      if (dumpInfoInterval > 0)
      {
         scheduledPool.scheduleWithFixedDelay(new Runnable()
         {
            public void run()
            {
               HornetQServerImpl.log.info(dumper.dump());
            }
         }, 0, dumpInfoInterval, TimeUnit.MILLISECONDS);
      }

      // Deploy the rest of the stuff

      // Deploy any predefined queues
      if (configuration.isFileDeploymentEnabled())
      {
         queueDeployer = new QueueDeployer(deploymentManager, this);

         queueDeployer.start();
      }
      else
      {
         deployQueuesFromConfiguration();
      }

      // We need to call this here, this gives any dependent server a chance to deploy its own addresses
      // this needs to be done before clustering is fully activated
      callActivateCallbacks();

      // Deply any pre-defined diverts
      deployDiverts();

      if (deploymentManager != null)
      {
         deploymentManager.start();
      }

      // We do this at the end - we don't want things like MDBs or other connections connecting to a backup server until
      // it is activated

      // FIXME -- I inverted the order to start the remoting service before the cluster manager.
      // when the cluster manager is started, it will form a cluster -> other nodes will then create bridges
      // to connect to this server. If the remoting service is not started before, the connection will fail
      // and the cluster will not be formed...
      initialised = true;

      remotingService.start();

      clusterManager.start();
   }

   /**
    * @param journalInfo
    */
   private void compareJournals(final JournalLoadInformation[] journalInfo) throws Exception
   {
      if (replicationManager != null)
      {
         replicationManager.compareJournals(journalInfo);
      }
   }

   private void deploySecurityFromConfiguration()
   {
      for (Map.Entry<String, Set<Role>> entry : configuration.getSecurityRoles().entrySet())
      {
         securityRepository.addMatch(entry.getKey(), entry.getValue());
      }
   }

   private void deployQueuesFromConfiguration() throws Exception
   {
      for (CoreQueueConfiguration config : configuration.getQueueConfigurations())
      {
         deployQueue(SimpleString.toSimpleString(config.getAddress()),
                     SimpleString.toSimpleString(config.getName()),
                     SimpleString.toSimpleString(config.getFilterString()),
                     config.isDurable(),
                     false);
      }
   }

   private void deployAddressSettingsFromConfiguration()
   {
      for (Map.Entry<String, AddressSettings> entry : configuration.getAddressesSettings().entrySet())
      {
         addressSettingsRepository.addMatch(entry.getKey(), entry.getValue());
      }
   }

   private JournalLoadInformation[] loadJournals() throws Exception
   {
      JournalLoadInformation[] journalInfo = new JournalLoadInformation[2];

      List<QueueBindingInfo> queueBindingInfos = new ArrayList<QueueBindingInfo>();

      List<GroupingInfo> groupingInfos = new ArrayList<GroupingInfo>();

      journalInfo[0] = storageManager.loadBindingJournal(queueBindingInfos, groupingInfos);

      recoverStoredConfigs();

      Map<Long, Queue> queues = new HashMap<Long, Queue>();

      for (QueueBindingInfo queueBindingInfo : queueBindingInfos)
      {
         Filter filter = FilterImpl.createFilter(queueBindingInfo.getFilterString());

         Queue queue = queueFactory.createQueue(queueBindingInfo.getId(),
                                                queueBindingInfo.getAddress(),
                                                queueBindingInfo.getQueueName(),
                                                filter,
                                                true,
                                                false);

         Binding binding = new LocalQueueBinding(queueBindingInfo.getAddress(), queue, nodeID);

         queues.put(queueBindingInfo.getId(), queue);

         postOffice.addBinding(binding);

         managementService.registerAddress(queueBindingInfo.getAddress());
         managementService.registerQueue(queue, queueBindingInfo.getAddress(), storageManager);
      }

      for (GroupingInfo groupingInfo : groupingInfos)
      {
         if (groupingHandler != null)
         {
            groupingHandler.addGroupBinding(new GroupBinding(groupingInfo.getId(),
                                                             groupingInfo.getGroupId(),
                                                             groupingInfo.getClusterName()));
         }
      }

      Map<SimpleString, List<Pair<byte[], Long>>> duplicateIDMap = new HashMap<SimpleString, List<Pair<byte[], Long>>>();

      journalInfo[1] = storageManager.loadMessageJournal(postOffice,
                                                         pagingManager,
                                                         resourceManager,
                                                         queues,
                                                         duplicateIDMap);

      for (Map.Entry<SimpleString, List<Pair<byte[], Long>>> entry : duplicateIDMap.entrySet())
      {
         SimpleString address = entry.getKey();

         DuplicateIDCache cache = postOffice.getDuplicateIDCache(address);

         if (configuration.isPersistIDCache())
         {
            cache.load(entry.getValue());
         }
      }

      return journalInfo;
   }

   /**
    * @throws Exception
    */
   private void recoverStoredConfigs() throws Exception
   {
      List<PersistedAddressSetting> adsettings = storageManager.recoverAddressSettings();
      for (PersistedAddressSetting set : adsettings)
      {
         addressSettingsRepository.addMatch(set.getAddressMatch().toString(), set.getSetting());
      }

      List<PersistedRoles> roles = storageManager.recoverPersistedRoles();

      for (PersistedRoles roleItem : roles)
      {
         Set<Role> setRoles = SecurityFormatter.createSecurity(roleItem.getSendRoles(),
                                                               roleItem.getConsumeRoles(),
                                                               roleItem.getCreateDurableQueueRoles(),
                                                               roleItem.getDeleteDurableQueueRoles(),
                                                               roleItem.getCreateNonDurableQueueRoles(),
                                                               roleItem.getDeleteNonDurableQueueRoles(),
                                                               roleItem.getManageRoles());

         securityRepository.addMatch(roleItem.getAddressMatch().toString(), setRoles);
      }
   }

   private Queue createQueue(final SimpleString address,
                             final SimpleString queueName,
                             final SimpleString filterString,
                             final boolean durable,
                             final boolean temporary,
                             final boolean ignoreIfExists) throws Exception
   {
      Binding binding = postOffice.getBinding(queueName);

      if (binding != null)
      {
         if (ignoreIfExists)
         {
            return null;
         }
         else
         {
            throw new HornetQException(HornetQException.QUEUE_EXISTS, "Queue " + queueName + " already exists");
         }
      }

      Filter filter = FilterImpl.createFilter(filterString);

      final Queue queue = queueFactory.createQueue(storageManager.generateUniqueID(),
                                                   address,
                                                   queueName,
                                                   filter,
                                                   durable,
                                                   temporary);

      binding = new LocalQueueBinding(address, queue, nodeID);

      if (durable)
      {
         storageManager.addQueueBinding(binding);
      }

      postOffice.addBinding(binding);

      managementService.registerAddress(address);
      managementService.registerQueue(queue, address, storageManager);

      return queue;
   }

   private void deployDiverts() throws Exception
   {
      for (DivertConfiguration config : configuration.getDivertConfigurations())
      {
         if (config.getName() == null)
         {
            HornetQServerImpl.log.warn("Must specify a name for each divert. This one will not be deployed.");

            return;
         }

         if (config.getAddress() == null)
         {
            HornetQServerImpl.log.warn("Must specify an address for each divert. This one will not be deployed.");

            return;
         }

         if (config.getForwardingAddress() == null)
         {
            HornetQServerImpl.log.warn("Must specify an forwarding address for each divert. This one will not be deployed.");

            return;
         }

         SimpleString sName = new SimpleString(config.getName());

         if (postOffice.getBinding(sName) != null)
         {
            HornetQServerImpl.log.warn("Binding already exists with name " + sName + ", divert will not be deployed");

            continue;
         }

         SimpleString sAddress = new SimpleString(config.getAddress());

         Transformer transformer = instantiateTransformer(config.getTransformerClassName());

         Filter filter = FilterImpl.createFilter(config.getFilterString());

         Divert divert = new DivertImpl(new SimpleString(config.getForwardingAddress()),
                                        sName,
                                        new SimpleString(config.getRoutingName()),
                                        config.isExclusive(),
                                        filter,
                                        transformer,
                                        postOffice,
                                        storageManager);
         // pagingManager,
         // storageManager);

         Binding binding = new DivertBinding(storageManager.generateUniqueID(), sAddress, divert);

         postOffice.addBinding(binding);

         managementService.registerDivert(divert, config);
      }
   }

   private synchronized void deployGroupingHandlerConfiguration(final GroupingHandlerConfiguration config) throws Exception
   {
      if (config != null)
      {
         GroupingHandler groupingHandler;
         if (config.getType() == GroupingHandlerConfiguration.TYPE.LOCAL)
         {
            groupingHandler = new LocalGroupingHandler(managementService,
                                                       config.getName(),
                                                       config.getAddress(),
                                                       getStorageManager(),
                                                       config.getTimeout());
         }
         else
         {
            groupingHandler = new RemoteGroupingHandler(managementService,
                                                        config.getName(),
                                                        config.getAddress(),
                                                        config.getTimeout());
         }

         this.groupingHandler = groupingHandler;

         managementService.addNotificationListener(groupingHandler);
      }
   }

   private Transformer instantiateTransformer(final String transformerClassName)
   {
      Transformer transformer = null;

      if (transformerClassName != null)
      {
         transformer = (Transformer)instantiateInstance(transformerClassName);
      }

      return transformer;
   }

   private Object instantiateInstance(final String className)
   {
      ClassLoader loader = Thread.currentThread().getContextClassLoader();
      try
      {
         Class<?> clz = loader.loadClass(className);
         Object object = clz.newInstance();

         return object;
      }
      catch (Exception e)
      {
         throw new IllegalArgumentException("Error instantiating class \"" + className + "\"", e);
      }
   }

   private static ClassLoader getThisClassLoader()
   {
      return AccessController.doPrivileged(new PrivilegedAction<ClassLoader>()
      {
         public ClassLoader run()
         {
            return ClientSessionFactoryImpl.class.getClassLoader();
         }
      });

   }

   // Inner classes
   // --------------------------------------------------------------------------------
}
