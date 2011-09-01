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

import java.io.InputStream;

import org.hornetq.api.core.Message;
import org.hornetq.api.core.SimpleString;
import org.hornetq.core.logging.Logger;
import org.hornetq.core.message.impl.MessageImpl;
import org.hornetq.core.paging.PagingStore;
import org.hornetq.core.server.MessageReference;
import org.hornetq.core.server.Queue;
import org.hornetq.core.server.ServerMessage;
import org.hornetq.utils.DataConstants;
import org.hornetq.utils.MemorySize;
import org.hornetq.utils.TypedProperties;

/**
 * 
 * A ServerMessageImpl
 * 
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 * @author <a href="mailto:ataylor@redhat.com">Andy Taylor</a>
 * @author <a href="mailto:clebert.suconic@jboss.com">Clebert Suconic</a>
 *
 */
public class ServerMessageImpl extends MessageImpl implements ServerMessage
{
   private static final Logger log = Logger.getLogger(ServerMessageImpl.class);

   private int durableRefCount;

   private int refCount;

   private PagingStore pagingStore;

   private static final int memoryOffset;

   static
   {
      // This is an estimate of how much memory a ServerMessageImpl takes up, exclusing body and properties
      // Note, it is only an estimate, it's not possible to be entirely sure with Java
      // This figure is calculated using the test utilities in org.hornetq.tests.unit.util.sizeof
      // The value is somewhat higher on 64 bit architectures, probably due to different alignment

      if (MemorySize.is64bitArch())
      {
         memoryOffset = 352;
      }
      else
      {
         memoryOffset = 232;
      }
   }

   /*
    * Constructor for when reading from network
    */
   public ServerMessageImpl()
   {
   }

   /*
    * Construct a MessageImpl from storage, or notification, or before routing
    */
   public ServerMessageImpl(final long messageID, final int initialMessageBufferSize)
   {
      super(initialMessageBufferSize);

      this.messageID = messageID;
   }

   /*
    * Copy constructor
    */
   protected ServerMessageImpl(final ServerMessageImpl other)
   {
      super(other);
   }

   /*
    * Copy constructor
    */
   protected ServerMessageImpl(final ServerMessageImpl other, TypedProperties properties)
   {
      super(other, properties);
   }

   public boolean isServerMessage()
   {
      return true;
   }

   public void setMessageID(final long id)
   {
      messageID = id;
   }

   public MessageReference createReference(final Queue queue)
   {
      MessageReference ref = new MessageReferenceImpl(this, queue);

      return ref;
   }

   
   public boolean hasInternalProperties()
   {
      return properties.hasInternalProperties();
   }
   
   public synchronized int incrementRefCount() throws Exception
   {
      refCount++;

      if (pagingStore != null)
      {
         if (refCount == 1)
         {
            pagingStore.addSize(getMemoryEstimate() + MessageReferenceImpl.getMemoryEstimate());
         }
         else
         {
            pagingStore.addSize(MessageReferenceImpl.getMemoryEstimate());
         }
      }

      return refCount;
   }

   public synchronized int decrementRefCount() throws Exception
   {
      int count = --refCount;

      if (pagingStore != null)
      {
         if (count == 0)
         {
            pagingStore.addSize(-getMemoryEstimate() - MessageReferenceImpl.getMemoryEstimate());
         }
         else
         {
            pagingStore.addSize(-MessageReferenceImpl.getMemoryEstimate());
         }
      }

      return count;
   }

   public synchronized int incrementDurableRefCount()
   {
      return ++durableRefCount;
   }

   public synchronized int decrementDurableRefCount()
   {
      return --durableRefCount;
   }

   public synchronized int getRefCount()
   {
      return refCount;
   }

   public boolean isLargeMessage()
   {
      return false;
   }

   private volatile int memoryEstimate = -1;

   public int getMemoryEstimate()
   {
      if (memoryEstimate == -1)
      {
         memoryEstimate = ServerMessageImpl.memoryOffset + buffer.capacity() + properties.getMemoryOffset();
      }

      return memoryEstimate;
   }

   public ServerMessage copy(final long newID)
   {
      ServerMessage m = new ServerMessageImpl(this);

      m.setMessageID(newID);

      return m;
   }

   public ServerMessage copy()
   {
      // This is a simple copy, used only to avoid changing original properties
      return new ServerMessageImpl(this);
   }

   public ServerMessage makeCopyForExpiryOrDLA(final long newID, final boolean expiry) throws Exception
   {
      /*
       We copy the message and send that to the dla/expiry queue - this is
       because otherwise we may end up with a ref with the same message id in the
       queue more than once which would barf - this might happen if the same message had been
       expire from multiple subscriptions of a topic for example
       We set headers that hold the original message address, expiry time
       and original message id
      */

      ServerMessage copy = copy(newID);

      copy.setOriginalHeaders(this, expiry);

      return copy;
   }

   public void setOriginalHeaders(final ServerMessage other, final boolean expiry)
   {
      if (other.containsProperty(Message.HDR_ORIG_MESSAGE_ID))
      {
         putStringProperty(Message.HDR_ORIGINAL_ADDRESS, other.getSimpleStringProperty(Message.HDR_ORIGINAL_ADDRESS));

         putLongProperty(Message.HDR_ORIG_MESSAGE_ID, other.getLongProperty(Message.HDR_ORIG_MESSAGE_ID));
      }
      else
      {
         SimpleString originalQueue = other.getAddress();

         putStringProperty(Message.HDR_ORIGINAL_ADDRESS, originalQueue);

         putLongProperty(Message.HDR_ORIG_MESSAGE_ID, other.getMessageID());
      }

      // reset expiry
      setExpiration(0);

      if (expiry)
      {
         long actualExpiryTime = System.currentTimeMillis();

         putLongProperty(Message.HDR_ACTUAL_EXPIRY_TIME, actualExpiryTime);
      }

      bufferValid = false;
   }

   public void setPagingStore(final PagingStore pagingStore)
   {
      this.pagingStore = pagingStore;

      // On the server side, we reset the address to point to the instance of address in the paging store
      // Otherwise each message would have its own copy of the address String which would take up more memory
      address = pagingStore.getAddress();
   }

   public PagingStore getPagingStore()
   {
      return pagingStore;
   }

   public boolean storeIsPaging()
   {
      if (pagingStore != null)
      {
         return pagingStore.isPaging();
      }
      else
      {
         return false;
      }
   }

   @Override
   public String toString()
   {
      return "ServerMessage[messageID=" + messageID + ", durable=" + durable + ", address=" + getAddress()  + ",properties=" + properties.toString() + "]";
   }

   // FIXME - this is stuff that is only used in large messages

   // This is only valid on the client side - why is it here?
   public InputStream getBodyInputStream()
   {
      return null;
   }

   // Encoding stuff

   public void encodeMessageIDToBuffer()
   {
      // We first set the message id - this needs to be set on the buffer since this buffer will be re-used

      buffer.setLong(buffer.getInt(MessageImpl.BUFFER_HEADER_SPACE) + DataConstants.SIZE_INT, messageID);
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.server.ServerMessage#getDuplicateIDBytes()
    */
   public byte[] getDuplicateIDBytes()
   {
      Object duplicateID = getDuplicateProperty();

      if (duplicateID == null)
      {
         return null;
      }
      else
      {
         if (duplicateID instanceof SimpleString)
         {
            return ((SimpleString)duplicateID).getData();
         }
         else
         {
            return (byte[])duplicateID;
         }
      }
   }
   
   public Object getDuplicateProperty()
   {
      return getObjectProperty(Message.HDR_DUPLICATE_DETECTION_ID);
   }

}
