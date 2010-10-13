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

package org.hornetq.tests.integration.paging;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executors;

import junit.framework.Assert;

import org.hornetq.api.core.SimpleString;
import org.hornetq.api.core.client.ClientConsumer;
import org.hornetq.api.core.client.ClientMessage;
import org.hornetq.api.core.client.ClientProducer;
import org.hornetq.api.core.client.ClientSession;
import org.hornetq.api.core.client.ClientSessionFactory;
import org.hornetq.core.config.Configuration;
import org.hornetq.core.paging.Page;
import org.hornetq.core.paging.PagedMessage;
import org.hornetq.core.paging.PagingManager;
import org.hornetq.core.paging.PagingStore;
import org.hornetq.core.paging.cursor.LivePageCache;
import org.hornetq.core.paging.impl.PagingManagerImpl;
import org.hornetq.core.paging.impl.PagingStoreFactoryNIO;
import org.hornetq.core.paging.impl.PagingStoreImpl;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.core.server.impl.HornetQServerImpl;
import org.hornetq.core.settings.impl.AddressSettings;
import org.hornetq.spi.core.security.HornetQSecurityManager;
import org.hornetq.spi.core.security.HornetQSecurityManagerImpl;
import org.hornetq.tests.util.ServiceTestBase;
import org.hornetq.utils.ExecutorFactory;
import org.hornetq.utils.OrderedExecutorFactory;

/**
 * This test will make sure that a failing depage won't cause duplicated messages
 *
 * @author <a href="mailto:clebert.suconic@jboss.org">Clebert Suconic</a>
 * 
 * Created Jan 7, 2009 6:19:43 PM
 *
 *
 */
public class PageCrashTest extends ServiceTestBase
{

   // Constants -----------------------------------------------------

   public static final SimpleString ADDRESS = new SimpleString("SimpleAddress");

   // Attributes ----------------------------------------------------

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   // Public --------------------------------------------------------

   public void testCrashDuringDeleteFile() throws Exception
   {
      doTestCrashDuringDeleteFile(false);
   }

   public void testCrashDuringDeleteFileTransacted() throws Exception
   {
      doTestCrashDuringDeleteFile(true);
   }

   public void doTestCrashDuringDeleteFile(final boolean transacted) throws Exception
   {
      pageAndFail(transacted);

      File pageDir = new File(getPageDir());

      File directories[] = pageDir.listFiles();

      Assert.assertEquals(1, directories.length);

      if (!transacted)
      {
         // When depage happened, a new empty page was supposed to be opened, what will create 3 files
         Assert.assertEquals("Missing a file, supposed to have address.txt, 1st page and 2nd page",
                             3,
                             directories[0].list().length);
      }

      Configuration config = createDefaultConfig();

      HornetQServer messagingService = createServer(true,
                                                    config,
                                                    10 * 1024,
                                                    100 * 1024,
                                                    new HashMap<String, AddressSettings>());

      messagingService.start();

      try
      {
         ClientSessionFactory sf = createInVMFactory();

         ClientSession session = sf.createSession(null, null, false, true, true, false, 0);

         session.start();

         ClientConsumer consumer = session.createConsumer(PageCrashTest.ADDRESS);

         Assert.assertNull(consumer.receiveImmediate());

         session.close();
      }
      finally
      {
         messagingService.stop();
      }

   }

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   /** This method will leave garbage on paging. 
    *  It will not delete page files as if the server crashed right after commit, 
    *  and before removing the file*/
   private void pageAndFail(final boolean transacted) throws Exception
   {
      clearData();
      Configuration config = createDefaultConfig();

      HornetQServer server = newHornetQServer(config);

      server.start();

      try
      {
         ClientSessionFactory sf = createInVMFactory();

         // Making it synchronous, just because we want to stop sending messages as soon as the page-store becomes in
         // page mode
         // and we could only guarantee that by setting it to synchronous
         sf.setBlockOnNonDurableSend(true);
         sf.setBlockOnDurableSend(true);
         sf.setBlockOnAcknowledge(true);

         ClientSession session = sf.createSession(null, null, false, !transacted, !transacted, false, 0);

         session.createQueue(PageCrashTest.ADDRESS, PageCrashTest.ADDRESS, null, true);

         ClientProducer producer = session.createProducer(PageCrashTest.ADDRESS);

         ClientMessage message = session.createMessage(true);
         message.getBodyBuffer().writeBytes(new byte[1024]);

         PagingStore store = server.getPostOffice().getPagingManager().getPageStore(PageCrashTest.ADDRESS);

         int messages = 0;
         while (!store.isPaging())
         {
            producer.send(message);
            messages++;
            if (transacted && messages % 100 == 0)
            {
               session.commit();
            }
         }

         for (int i = 0; i < 2; i++)
         {
            messages++;
            producer.send(message);
         }

         session.commit();

         session.close();

         session = sf.createSession(null, null, false, true, true, false, 0);

         ClientConsumer consumer = session.createConsumer(PageCrashTest.ADDRESS);

         session.start();

         for (int i = 0; i < messages; i++)
         {
            ClientMessage message2 = consumer.receive(10000);

            Assert.assertNotNull(message2);

            message2.acknowledge();
         }

         consumer.close();

         session.close();
      }
      finally
      {
         try
         {
            server.stop();
         }
         catch (Throwable ignored)
         {
         }
      }
   }

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   private HornetQServer newHornetQServer(final Configuration configuration)
   {
      HornetQSecurityManager securityManager = new HornetQSecurityManagerImpl();

      HornetQServer server = new FailingHornetQServer(configuration, securityManager);

      AddressSettings defaultSetting = new AddressSettings();
      defaultSetting.setPageSizeBytes(10 * 1024);
      defaultSetting.setMaxSizeBytes(100 * 1024);

      server.getAddressSettingsRepository().addMatch("#", defaultSetting);

      return server;
   }

   // Inner classes -------------------------------------------------

   /** This is hacking HornetQServerImpl, 
    *  to make sure the server will fail right 
    *  before the page-file was removed */
   class FailingHornetQServer extends HornetQServerImpl
   {
      FailingHornetQServer(final Configuration config, final HornetQSecurityManager securityManager)
      {
         super(config, ManagementFactory.getPlatformMBeanServer(), securityManager);
      }

      @Override
      protected PagingManager createPagingManager()
      {
         return new PagingManagerImpl(new FailurePagingStoreFactoryNIO(super.getConfiguration().getPagingDirectory(),
                                                                       super.getConfiguration()
                                                                            .isJournalSyncNonTransactional()),
                                      super.getStorageManager(),
                                      super.getAddressSettingsRepository());
      }

      class FailurePagingStoreFactoryNIO extends PagingStoreFactoryNIO

      {
         /**
          * @param directory
          * @param maxThreads
          */
         public FailurePagingStoreFactoryNIO(final String directory, final boolean syncNonTransactional)
         {
            super(directory, new OrderedExecutorFactory(Executors.newCachedThreadPool()), syncNonTransactional);
         }

         // Constants -----------------------------------------------------

         // Attributes ----------------------------------------------------

         // Static --------------------------------------------------------

         // Constructors --------------------------------------------------

         // Public --------------------------------------------------------

         @Override
         public synchronized PagingStore newStore(final SimpleString destinationName, final AddressSettings settings) throws Exception
         {
            Field factoryField = PagingStoreFactoryNIO.class.getDeclaredField("executorFactory");
            factoryField.setAccessible(true);

            OrderedExecutorFactory factory = (org.hornetq.utils.OrderedExecutorFactory)factoryField.get(this);
            return new FailingPagingStore(destinationName, settings, factory, syncNonTransactional);
         }

         // Package protected ---------------------------------------------

         // Protected -----------------------------------------------------

         // Private -------------------------------------------------------

         // Inner classes -------------------------------------------------
         class FailingPagingStore extends PagingStoreImpl
         {

            /**
             * @param storeName
             * @param addressSettings
             * @param executor
             */
            public FailingPagingStore(final SimpleString storeName,
                                      final AddressSettings addressSettings,
                                      final ExecutorFactory executor,
                                      final boolean syncNonTransactional)
            {
               super(storeName,
                     getPostOffice().getPagingManager(),
                     getStorageManager(),
                     getPostOffice(),
                     null,
                     FailurePagingStoreFactoryNIO.this,
                     storeName,
                     addressSettings,
                     executor,
                     syncNonTransactional);
            }

            @Override
            public Page createPage(final int page) throws Exception
            {

               Page originalPage = super.createPage(page);

               return new FailingPage(originalPage);
            }

         }

      }

      class FailingPage implements Page
      {
         Page delegatedPage;

         /**
          * @throws Exception
          * @see org.hornetq.core.paging.Page#close()
          */
         public void close() throws Exception
         {
            delegatedPage.close();
         }

         /**
          * @throws Exception
          * @see org.hornetq.core.paging.Page#delete()
          */
         public boolean delete() throws Exception
         {
            
            System.out.println("Won't delete");
            return false;
         }

         /**
          * @return
          * @see org.hornetq.core.paging.Page#getNumberOfMessages()
          */
         public int getNumberOfMessages()
         {
            return delegatedPage.getNumberOfMessages();
         }

         /**
          * @return
          * @see org.hornetq.core.paging.Page#getPageId()
          */
         public int getPageId()
         {
            return delegatedPage.getPageId();
         }

         /**
          * @return
          * @see org.hornetq.core.paging.Page#getSize()
          */
         public int getSize()
         {
            return delegatedPage.getSize();
         }

         /**
          * @throws Exception
          * @see org.hornetq.core.paging.Page#open()
          */
         public void open() throws Exception
         {
            delegatedPage.open();
         }

         /**
          * @return
          * @throws Exception
          * @see org.hornetq.core.paging.Page#read()
          */
         public List<PagedMessage> read() throws Exception
         {
            return delegatedPage.read();
         }

         /**
          * @throws Exception
          * @see org.hornetq.core.paging.Page#sync()
          */
         public void sync() throws Exception
         {
            delegatedPage.sync();
         }

         /**
          * @param message
          * @throws Exception
          * @see org.hornetq.core.paging.Page#write(org.hornetq.core.paging.PagedMessage)
          */
         public void write(final PagedMessage message) throws Exception
         {
            delegatedPage.write(message);
         }

         public FailingPage(final Page delegatePage)
         {
            delegatedPage = delegatePage;
         }

         /* (non-Javadoc)
          * @see org.hornetq.core.paging.Page#setLiveCache(org.hornetq.core.paging.cursor.LivePageCache)
          */
         public void setLiveCache(LivePageCache pageCache)
         {
         }
      }

   }

   // Inner classes -------------------------------------------------

}
