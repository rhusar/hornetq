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

package org.hornetq.integration.transports.netty;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import org.hornetq.integration.stomp.StompFrameDelimiter;
import org.hornetq.integration.stomp.StompMarshaller;
import org.hornetq.integration.stomp.StompPacketDecoder;
import org.hornetq.spi.core.remoting.BufferHandler;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.handler.ssl.SslHandler;

/**
 * @author <a href="mailto:jmesnil@redhat.com">Jeff Mesnil</a>
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 * @author <a href="mailto:clebert.suconic@jboss.com">Clebert Suconic</a>
 * @author <a href="mailto:tlee@redhat.com">Trustin Lee</a>
 * @version $Rev$, $Date$
 */
public class ChannelPipelineSupport
{
   // Constants -----------------------------------------------------

   // Attributes ----------------------------------------------------

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   private ChannelPipelineSupport()
   {
      // Unused
   }

   // Public --------------------------------------------------------

   public static void addStompStack(final ChannelPipeline pipeline, final ServerHolder serverHandler)
   {
      assert pipeline != null;
      StompMarshaller marshaller = new StompMarshaller();
      pipeline.addLast("delimiter", new StompFrameDelimiter());
      pipeline.addLast("codec", new StompPacketDecoder(marshaller));
   }

   public static void addHornetQCodecFilter(final ChannelPipeline pipeline, final BufferHandler handler)
   {
      assert pipeline != null;
      pipeline.addLast("decoder", new HornetQFrameDecoder2());
   }

   public static void addSSLFilter(final ChannelPipeline pipeline, final SSLContext context, final boolean client) throws Exception
   {
      SSLEngine engine = context.createSSLEngine();
      engine.setUseClientMode(client);
      if (client)
      {
         engine.setWantClientAuth(true);
      }

      SslHandler handler = new SslHandler(engine);
      pipeline.addLast("ssl", handler);
   }

   // Package protected ---------------------------------------------

   // Inner classes -------------------------------------------------
}
