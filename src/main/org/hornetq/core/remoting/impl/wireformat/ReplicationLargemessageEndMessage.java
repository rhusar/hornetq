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

package org.hornetq.core.remoting.impl.wireformat;

import org.hornetq.core.remoting.spi.HornetQBuffer;
import org.hornetq.utils.DataConstants;

/**
 * A ReplicationLargemessageEndMessage
 *
 * @author <mailto:clebert.suconic@jboss.org">Clebert Suconic</a>
 *
 *
 */
public class ReplicationLargemessageEndMessage extends PacketImpl
{
   // Constants -----------------------------------------------------

   // Attributes ----------------------------------------------------

   long messageId;

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   public ReplicationLargemessageEndMessage()
   {
      super(REPLICATION_LARGE_MESSAGE_END);
   }

   public ReplicationLargemessageEndMessage(final long messageId)
   {
      this();
      this.messageId = messageId;
   }

   // Public --------------------------------------------------------

   @Override
   public int getRequiredBufferSize()
   {
      return PACKET_HEADERS_SIZE + DataConstants.SIZE_LONG;
   }

   @Override
   public void encodeRest(final HornetQBuffer buffer)
   {
      buffer.writeLong(messageId);
   }

   @Override
   public void decodeRest(final HornetQBuffer buffer)
   {
      messageId = buffer.readLong();
   }

   /**
    * @return the messageId
    */
   public long getMessageId()
   {
      return messageId;
   }

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------

}
