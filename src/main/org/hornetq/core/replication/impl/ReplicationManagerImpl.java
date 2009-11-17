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

package org.hornetq.core.replication.impl;

import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.hornetq.core.client.SessionFailureListener;
import org.hornetq.core.client.impl.FailoverManager;
import org.hornetq.core.completion.CompletionContext;
import org.hornetq.core.completion.impl.CompletionContextImpl;
import org.hornetq.core.exception.HornetQException;
import org.hornetq.core.journal.EncodingSupport;
import org.hornetq.core.journal.JournalLoadInformation;
import org.hornetq.core.logging.Logger;
import org.hornetq.core.paging.PagedMessage;
import org.hornetq.core.remoting.Channel;
import org.hornetq.core.remoting.ChannelHandler;
import org.hornetq.core.remoting.Packet;
import org.hornetq.core.remoting.RemotingConnection;
import org.hornetq.core.remoting.impl.wireformat.CreateReplicationSessionMessage;
import org.hornetq.core.remoting.impl.wireformat.PacketImpl;
import org.hornetq.core.remoting.impl.wireformat.ReplicationAddMessage;
import org.hornetq.core.remoting.impl.wireformat.ReplicationAddTXMessage;
import org.hornetq.core.remoting.impl.wireformat.ReplicationCommitMessage;
import org.hornetq.core.remoting.impl.wireformat.ReplicationCompareDataMessage;
import org.hornetq.core.remoting.impl.wireformat.ReplicationDeleteMessage;
import org.hornetq.core.remoting.impl.wireformat.ReplicationDeleteTXMessage;
import org.hornetq.core.remoting.impl.wireformat.ReplicationLargeMessageBeingMessage;
import org.hornetq.core.remoting.impl.wireformat.ReplicationLargeMessageWriteMessage;
import org.hornetq.core.remoting.impl.wireformat.ReplicationLargemessageEndMessage;
import org.hornetq.core.remoting.impl.wireformat.ReplicationPageEventMessage;
import org.hornetq.core.remoting.impl.wireformat.ReplicationPageWriteMessage;
import org.hornetq.core.remoting.impl.wireformat.ReplicationPrepareMessage;
import org.hornetq.core.remoting.spi.HornetQBuffer;
import org.hornetq.core.replication.ReplicationManager;
import org.hornetq.utils.SimpleString;

/**
 * A RepplicationManagerImpl
 *
 * @author <mailto:clebert.suconic@jboss.org">Clebert Suconic</a>
 *
 *
 */
public class ReplicationManagerImpl implements ReplicationManager
{

   // Constants -----------------------------------------------------
   private static final Logger log = Logger.getLogger(ReplicationManagerImpl.class);

   // Attributes ----------------------------------------------------

   private final int backupWindowSize;

   private final ResponseHandler responseHandler = new ResponseHandler();

   private final FailoverManager failoverManager;

   private RemotingConnection connection;

   private Channel replicatingChannel;

   private boolean started;

   private volatile boolean enabled;

   private final Object replicationLock = new Object();

   private final Queue<CompletionContext> pendingTokens = new ConcurrentLinkedQueue<CompletionContext>();

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   /**
    * @param replicationConnectionManager
    */
   public ReplicationManagerImpl(final FailoverManager failoverManager, final int backupWindowSize)
   {
      super();
      this.failoverManager = failoverManager;
      this.backupWindowSize = backupWindowSize;
   }

   // Public --------------------------------------------------------

   /* (non-Javadoc)
    * @see org.hornetq.core.replication.ReplicationManager#replicate(byte[], org.hornetq.core.replication.ReplicationToken)
    */

   public void appendAddRecord(final byte journalID, final long id, final byte recordType, final EncodingSupport record)
   {
      if (enabled)
      {
         sendReplicatePacket(new ReplicationAddMessage(journalID, false, id, recordType, record));
      }
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.replication.ReplicationManager#appendUpdateRecord(byte, long, byte, org.hornetq.core.journal.EncodingSupport, boolean)
    */
   public void appendUpdateRecord(final byte journalID,
                                  final long id,
                                  final byte recordType,
                                  final EncodingSupport record) throws Exception
   {
      if (enabled)
      {
         sendReplicatePacket(new ReplicationAddMessage(journalID, true, id, recordType, record));
      }
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.replication.ReplicationManager#appendDeleteRecord(byte, long, boolean)
    */
   public void appendDeleteRecord(final byte journalID, final long id) throws Exception
   {
      if (enabled)
      {
         sendReplicatePacket(new ReplicationDeleteMessage(journalID, id));
      }
   }

   public void appendAddRecordTransactional(final byte journalID,
                                            final long txID,
                                            final long id,
                                            final byte recordType,
                                            final EncodingSupport record) throws Exception
   {
      if (enabled)
      {
         sendReplicatePacket(new ReplicationAddTXMessage(journalID, false, txID, id, recordType, record));
      }
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.replication.ReplicationManager#appendUpdateRecordTransactional(byte, long, long, byte, org.hornetq.core.journal.EncodingSupport)
    */
   public void appendUpdateRecordTransactional(final byte journalID,
                                               final long txID,
                                               final long id,
                                               final byte recordType,
                                               final EncodingSupport record) throws Exception
   {
      if (enabled)
      {
         sendReplicatePacket(new ReplicationAddTXMessage(journalID, true, txID, id, recordType, record));
      }
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.replication.ReplicationManager#appendCommitRecord(byte, long, boolean)
    */
   public void appendCommitRecord(final byte journalID, final long txID) throws Exception
   {
      if (enabled)
      {
         sendReplicatePacket(new ReplicationCommitMessage(journalID, false, txID));
      }
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.replication.ReplicationManager#appendDeleteRecordTransactional(byte, long, long, org.hornetq.core.journal.EncodingSupport)
    */
   public void appendDeleteRecordTransactional(final byte journalID,
                                               final long txID,
                                               final long id,
                                               final EncodingSupport record) throws Exception
   {
      if (enabled)
      {
         sendReplicatePacket(new ReplicationDeleteTXMessage(journalID, txID, id, record));
      }
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.replication.ReplicationManager#appendDeleteRecordTransactional(byte, long, long)
    */
   public void appendDeleteRecordTransactional(final byte journalID, final long txID, final long id) throws Exception
   {
      if (enabled)
      {
         sendReplicatePacket(new ReplicationDeleteTXMessage(journalID, txID, id, NullEncoding.instance));
      }
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.replication.ReplicationManager#appendPrepareRecord(byte, long, org.hornetq.core.journal.EncodingSupport, boolean)
    */
   public void appendPrepareRecord(final byte journalID, final long txID, final EncodingSupport transactionData) throws Exception
   {
      if (enabled)
      {
         sendReplicatePacket(new ReplicationPrepareMessage(journalID, txID, transactionData));
      }
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.replication.ReplicationManager#appendRollbackRecord(byte, long, boolean)
    */
   public void appendRollbackRecord(final byte journalID, final long txID) throws Exception
   {
      if (enabled)
      {
         sendReplicatePacket(new ReplicationCommitMessage(journalID, false, txID));
      }
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.replication.ReplicationManager#pageClosed(org.hornetq.utils.SimpleString, int)
    */
   public void pageClosed(final SimpleString storeName, final int pageNumber)
   {
      if (enabled)
      {
         sendReplicatePacket(new ReplicationPageEventMessage(storeName, pageNumber, false));
      }
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.replication.ReplicationManager#pageDeleted(org.hornetq.utils.SimpleString, int)
    */
   public void pageDeleted(final SimpleString storeName, final int pageNumber)
   {
      if (enabled)
      {
         sendReplicatePacket(new ReplicationPageEventMessage(storeName, pageNumber, true));
      }
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.replication.ReplicationManager#pageWrite(org.hornetq.utils.SimpleString, int)
    */
   public void pageWrite(final PagedMessage message, final int pageNumber)
   {
      if (enabled)
      {
         sendReplicatePacket(new ReplicationPageWriteMessage(message, pageNumber));
      }
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.replication.ReplicationManager#largeMessageBegin(byte[])
    */
   public void largeMessageBegin(long messageId)
   {
      if (enabled)
      {
         sendReplicatePacket(new ReplicationLargeMessageBeingMessage(messageId));
      }
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.replication.ReplicationManager#largeMessageDelete(long)
    */
   public void largeMessageDelete(long messageId)
   {
      if (enabled)
      {
         sendReplicatePacket(new ReplicationLargemessageEndMessage(messageId));
      }
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.replication.ReplicationManager#largeMessageWrite(long, byte[])
    */
   public void largeMessageWrite(long messageId, byte[] body)
   {
      if (enabled)
      {
         sendReplicatePacket(new ReplicationLargeMessageWriteMessage(messageId, body));
      }
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.server.HornetQComponent#isStarted()
    */
   public synchronized boolean isStarted()
   {
      return started;
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.server.HornetQComponent#start()
    */
   public synchronized void start() throws Exception
   {
      if (started)
      {
         throw new IllegalStateException("ReplicationManager is already started");
      }
      connection = failoverManager.getConnection();

      if (connection == null)
      {
         log.warn("Backup server MUST be started before live server. Initialisation will not proceed.");
         throw new HornetQException(HornetQException.ILLEGAL_STATE,
                                    "Backup server MUST be started before live server. Initialisation will not proceed.");
      }

      long channelID = connection.generateChannelID();

      Channel mainChannel = connection.getChannel(1, -1);

      replicatingChannel = connection.getChannel(channelID, backupWindowSize);

      replicatingChannel.setHandler(responseHandler);

      CreateReplicationSessionMessage replicationStartPackage = new CreateReplicationSessionMessage(channelID,
                                                                                                    backupWindowSize);

      mainChannel.sendBlocking(replicationStartPackage);

      failoverManager.addFailureListener(new SessionFailureListener()
      {
         public void connectionFailed(HornetQException me)
         {
            log.warn("Connection to the backup node failed, removing replication now");
            try
            {
               stop();
            }
            catch (Exception e)
            {
               log.warn(e.getMessage(), e);
            }
         }

         public void beforeReconnect(HornetQException me)
         {
         }
      });

      started = true;

      enabled = true;
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.server.HornetQComponent#stop()
    */
   public void stop() throws Exception
   {
      enabled = false;
      
      LinkedHashSet<CompletionContext> activeContexts = new LinkedHashSet<CompletionContext>();
      
      // The same context will be replicated on the pending tokens...
      // as the multiple operations will be replicated on the same context
      while (!pendingTokens.isEmpty())
      {
         CompletionContext ctx = pendingTokens.poll();
         activeContexts.add(ctx);
      }

      for (CompletionContext ctx : activeContexts)
      {
         ctx.complete();
         ctx.flush();
      }

      if (replicatingChannel != null)
      {
         replicatingChannel.close();
      }

      started = false;

      if (connection != null)
      {
         connection.destroy();
      }

      connection = null;

      started = false;
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.replication.ReplicationManager#completeToken()
    */
   public void closeContext()
   {
      final CompletionContext token = getContext();

      if (token != null)
      {
         // Remove from pending tokens as soon as this is complete
         if (!token.hasData())
         {
            sync(token);
         }
         token.complete();
      }
   }


   /* method for testcases only
    * @see org.hornetq.core.replication.ReplicationManager#getPendingTokens()
    */
   public Set<CompletionContext> getActiveTokens()
   {
      
      LinkedHashSet<CompletionContext> activeContexts = new LinkedHashSet<CompletionContext>();
      
      // The same context will be replicated on the pending tokens...
      // as the multiple operations will be replicated on the same context
      
      for (CompletionContext ctx : pendingTokens)
      {
         activeContexts.add(ctx);
      }
      
      return activeContexts;

   }

   private void sendReplicatePacket(final Packet packet)
   {
      boolean runItNow = false;

      CompletionContext repliToken = getContext();
      repliToken.linedUp();

      synchronized (replicationLock)
      {
         if (!enabled)
         {
            // Already replicating channel failed, so just play the action now

            runItNow = true;
         }
         else
         {
            pendingTokens.add(repliToken);

            replicatingChannel.send(packet);
         }
      }

      // Execute outside lock

      if (runItNow)
      {
         repliToken.replicated();
      }
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.replication.ReplicationManager#compareJournals(org.hornetq.core.journal.JournalLoadInformation[])
    */
   public void compareJournals(JournalLoadInformation[] journalInfo) throws HornetQException
   {
      replicatingChannel.sendBlocking(new ReplicationCompareDataMessage(journalInfo));
   }

   private void replicated()
   {
      List<CompletionContext> tokensToExecute = getTokens();

      for (CompletionContext ctx : tokensToExecute)
      {
         ctx.replicated();
      }
   }

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   private void sync(CompletionContext context)
   {
      boolean executeNow = false;
      synchronized (replicationLock)
      {
         context.linedUp();
         context.setEmpty(true);
         if (pendingTokens.isEmpty())
         {
            // this means the list is empty and we should process it now
            executeNow = true;
         }
         else
         {
            // adding the sync to be executed in order
            // as soon as the reponses are back from the backup
            this.pendingTokens.add(context);
         }
      }
      if (executeNow)
      {
         context.replicated();
      }
   }

   
   public CompletionContext getContext()
   {
      return CompletionContextImpl.getContext();
   }

   /**
    * This method will first get all the sync tokens (that won't go to the backup node)
    * Then it will get the round trip tokens.
    * At last, if the list is empty, it will verify if there are any future tokens that are sync tokens, to avoid a case where no more replication is done due to inactivity.
    * @return
    */
   private List<CompletionContext> getTokens()
   {
      List<CompletionContext> retList = new LinkedList<CompletionContext>();

      CompletionContext tokenPolled = null;

      // First will get all the non replicated tokens up to the first one that is not replicated
      do
      {
         tokenPolled = pendingTokens.poll();

         if (tokenPolled == null)
         {
            throw new IllegalStateException("Missing replication token on the queue.");
         }

         retList.add(tokenPolled);

      }
      while (tokenPolled.isEmpty());

      // This is to avoid a situation where we won't have more replicated packets
      // We need to make sure we process any pending sync packet up to the next non empty packet
      synchronized (replicationLock)
      {
         while (!pendingTokens.isEmpty() && pendingTokens.peek().isEmpty())
         {
            tokenPolled = pendingTokens.poll();
            if (!tokenPolled.isEmpty())
            {
               throw new IllegalStateException("Replicatoin context is not a roundtrip token as expected");
            }

            retList.add(tokenPolled);

         }
      }

      return retList;
   }


   // Inner classes -------------------------------------------------

   protected class ResponseHandler implements ChannelHandler
   {
      /* (non-Javadoc)
       * @see org.hornetq.core.remoting.ChannelHandler#handlePacket(org.hornetq.core.remoting.Packet)
       */
      public void handlePacket(final Packet packet)
      {
         if (packet.getType() == PacketImpl.REPLICATION_RESPONSE)
         {
            replicated();
         }
      }

   }

   private static class NullEncoding implements EncodingSupport
   {

      static NullEncoding instance = new NullEncoding();

      public void decode(final HornetQBuffer buffer)
      {
      }

      public void encode(final HornetQBuffer buffer)
      {
      }

      public int getEncodeSize()
      {
         return 0;
      }

   }

}
