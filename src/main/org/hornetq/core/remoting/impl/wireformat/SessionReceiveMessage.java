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

import org.hornetq.core.buffers.HornetQBuffer;
import org.hornetq.core.client.impl.ClientMessageImpl;
import org.hornetq.core.logging.Logger;
import org.hornetq.core.message.Message;
import org.hornetq.utils.DataConstants;

/**
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 *
 */
public class SessionReceiveMessage extends MessagePacket
{
   // Constants -----------------------------------------------------

   private static final Logger log = Logger.getLogger(SessionReceiveMessage.class);

   // Attributes ----------------------------------------------------

   private long consumerID;

   private int deliveryCount;

   public SessionReceiveMessage(final long consumerID, final Message message, final int deliveryCount)
   {
      super(SESS_RECEIVE_MSG, message);
      
      this.consumerID = consumerID;

      this.deliveryCount = deliveryCount;
      
      message.forceCopy();
   }

   public SessionReceiveMessage()
   {
      super(SESS_RECEIVE_MSG, new ClientMessageImpl());
   }

   // Public --------------------------------------------------------

   public long getConsumerID()
   {
      return consumerID;
   }
   
   public int getDeliveryCount()
   {
      return deliveryCount;
   }

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------
   
   protected void encodeExtraData(HornetQBuffer buffer)
   {
      buffer.writeLong(consumerID);
      buffer.writeInt(deliveryCount);
   }
   
   protected void decodeExtraData(HornetQBuffer buffer)
   {
      consumerID = buffer.readLong();
      deliveryCount = buffer.readInt();
   }
   @Override
   public void decodeRest(HornetQBuffer buffer)
   {
      //Buffer comes in after having read standard headers and positioned at Beginning of body part
      
      message.decodeFromBuffer(buffer);
      
      decodeExtraData(buffer);      
      
      //Need to position buffer for reading
      
      buffer.setIndex(PacketImpl.PACKET_HEADERS_SIZE + DataConstants.SIZE_INT, message.getEndOfBodyPosition());
   }

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------
}
