/*
 * Copyright 2010 Red Hat, Inc.
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

package org.hornetq.tests.integration.twitter;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;

import junit.framework.Assert;
import junit.framework.TestSuite;

import org.hornetq.api.core.TransportConfiguration;
import org.hornetq.api.core.client.ClientConsumer;
import org.hornetq.api.core.client.ClientMessage;
import org.hornetq.api.core.client.ClientProducer;
import org.hornetq.api.core.client.ClientSession;
import org.hornetq.api.core.client.ClientSessionFactory;
import org.hornetq.api.core.client.HornetQClient;
import org.hornetq.api.core.client.ServerLocator;
import org.hornetq.core.config.Configuration;
import org.hornetq.core.config.ConnectorServiceConfiguration;
import org.hornetq.core.config.CoreQueueConfiguration;
import org.hornetq.core.journal.impl.AIOSequentialFileFactory;
import org.hornetq.core.logging.Logger;
import org.hornetq.core.server.ConnectorService;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.integration.twitter.TwitterConstants;
import org.hornetq.integration.twitter.TwitterIncomingConnectorServiceFactory;
import org.hornetq.integration.twitter.TwitterOutgoingConnectorServiceFactory;
import org.hornetq.tests.util.ServiceTestBase;
import org.hornetq.tests.util.UnitTestCase;
import twitter4j.*;
import twitter4j.http.AccessToken;

/**
 * A TwitterTest
 *
 * @author tm.igarashi@gmail.com
 *
 *
 */
public class TwitterTest extends ServiceTestBase
{
   private static final Logger log = Logger.getLogger(TwitterTest.class);
   private static final String KEY_CONNECTOR_NAME = "connector.name";
   private static final String KEY_CONSUMER_KEY = "consumerKey";
   private static final String KEY_CONSUMER_SECRET = "consumerSecret";
   private static final String KEY_ACCESS_TOKEN = "accessToken";
   private static final String KEY_ACCESS_TOKEN_SECRET = "accessTokenSecret";
   private static final String KEY_QUEUE_NAME = "queue.name";
   
   private static final String TWITTER_CONSUMER_KEY = System.getProperty("twitter.consumerKey");
   private static final String TWITTER_CONSUMER_SECRET = System.getProperty("twitter.consumerSecret");
   private static final String TWITTER_ACCESS_TOKEN = System.getProperty("twitter.accessToken");
   private static final String TWITTER_ACCESS_TOKEN_SECRET = System.getProperty("twitter.accessTokenSecret");

   // incoming
   
   

   public static TestSuite suite()
   {
      TestSuite suite = new TestSuite(TwitterTest.class.getName() + " testsuite");

      if (TWITTER_CONSUMER_KEY != null)
      {
         suite.addTestSuite(TwitterTest.class);
      }
      else
      {
         // System.out goes towards JUnit report
         String errorMsg = "Test " + TwitterTest.class.getName() +
                           " ignored as twitter.consumerKey, twitter.consumerSecret, twitter.accessToken and twitter.accessTokenSecuret is not set in system property  * * *";
         System.out.println(errorMsg);
         log.warn(errorMsg);
      }

      return suite;
   }

   public void testSimpleIncoming() throws Exception
   {
      internalTestIncoming(true,false);
   }

   public void testIncomingNoQueue() throws Exception
   {
      internalTestIncoming(false,false);
   }

   public void testIncomingWithRestart() throws Exception
   {
      internalTestIncoming(true,true);
   }
   
   public void testIncomingWithEmptyConnectorName() throws Exception
   {
      HashMap<String,String> params = new HashMap<String,String>();
      params.put(KEY_CONNECTOR_NAME, "");
      internalTestIncomingFailedToInitialize(params);
   }

   public void testIncomingWithEmptyQueueName() throws Exception
   {
      HashMap<String,String> params = new HashMap<String,String>();
      params.put(KEY_QUEUE_NAME, "");
      internalTestIncomingFailedToInitialize(params);
   }

   public void testIncomingWithInvalidCredentials() throws Exception
   {
      HashMap<String,String> params = new HashMap<String,String>();
      params.put(KEY_CONSUMER_KEY, "invalidConsumerKey");
      params.put(KEY_CONSUMER_SECRET, "invalidConsumerSecret");
      params.put(KEY_ACCESS_TOKEN, "invalidAccessToken");
      params.put(KEY_ACCESS_TOKEN_SECRET, "invalidAcccessTokenSecret");
      internalTestIncomingFailedToInitialize(params);
   }

   //outgoing
   
   public void testSimpleOutgoing() throws Exception
   {
      internalTestOutgoing(true,false);
   }

   public void testOutgoingNoQueue() throws Exception
   {
      internalTestOutgoing(false,false);
   }
   public void testOutgoingWithRestart() throws Exception
   {
      internalTestOutgoing(true,true);
   }
   
   public void testOutgoingWithEmptyConnectorName() throws Exception
   {
      HashMap<String,String> params = new HashMap<String,String>();
      params.put(KEY_CONNECTOR_NAME, "");
      internalTestOutgoingFailedToInitialize(params);
   }

   public void testOutgoingWithEmptyQueueName() throws Exception
   {
      HashMap<String,String> params = new HashMap<String,String>();
      params.put(KEY_QUEUE_NAME, "");
      internalTestOutgoingFailedToInitialize(params);
   }

   public void testOutgoingWithInvalidCredentials() throws Exception
   {
      HashMap<String,String> params = new HashMap<String,String>();
      params.put(KEY_CONSUMER_KEY, "invalidConsumerKey");
      params.put(KEY_CONSUMER_SECRET, "invalidConsumerSecret");
      params.put(KEY_ACCESS_TOKEN, "invalidAccessToken");
      params.put(KEY_ACCESS_TOKEN_SECRET, "invalidAcccessTokenSecret");
      internalTestOutgoingFailedToInitialize(params);
   }
   
   public void testOutgoingWithInReplyTo() throws Exception
   {
      internalTestOutgoingWithInReplyTo();
   }
   
   protected void internalTestIncoming(boolean createQueue, boolean restart) throws Exception
   {
      HornetQServer server0 = null;
      ClientSession session = null;
      ServerLocator locator = null;
      String queue = "TwitterTestQueue";
      int interval = 5;
      Twitter twitter = new TwitterFactory().getOAuthAuthorizedInstance(TWITTER_CONSUMER_KEY,
                                                                        TWITTER_CONSUMER_SECRET,
                                                                        new AccessToken(TWITTER_ACCESS_TOKEN,
                                                                                        TWITTER_ACCESS_TOKEN_SECRET));
      String testMessage = "TwitterTest/incoming: " + System.currentTimeMillis();
      log.debug("test incoming: " + testMessage);
      
      try
      {
         Configuration configuration = createDefaultConfig(false);
         HashMap<String, Object> config = new HashMap<String, Object>();
         config.put(TwitterConstants.INCOMING_INTERVAL, interval);
         config.put(TwitterConstants.QUEUE_NAME, queue);
         config.put(TwitterConstants.CONSUMER_KEY, TWITTER_CONSUMER_KEY);
         config.put(TwitterConstants.CONSUMER_SECRET, TWITTER_CONSUMER_SECRET);
         config.put(TwitterConstants.ACCESS_TOKEN, TWITTER_ACCESS_TOKEN);
         config.put(TwitterConstants.ACCESS_TOKEN_SECRET, TWITTER_ACCESS_TOKEN_SECRET);
         ConnectorServiceConfiguration inconf =
               new ConnectorServiceConfiguration(
               TwitterIncomingConnectorServiceFactory.class.getName(),
                     config,"test-incoming-connector");
         configuration.getConnectorServiceConfigurations().add(inconf);

         if(createQueue)
         {
            CoreQueueConfiguration qc = new CoreQueueConfiguration(queue, queue, null, true);
            configuration.getQueueConfigurations().add(qc);
         }

         server0 = createServer(false,configuration);
         server0.start();
         
         if(restart)
         {
            server0.getConnectorsService().stop();
            server0.getConnectorsService().start();
         }

         assertEquals(1, server0.getConnectorsService().getConnectors().size());
         Iterator<ConnectorService> connectorServiceIterator = server0.getConnectorsService().getConnectors().iterator();
         if(createQueue)
         {
            Assert.assertTrue(connectorServiceIterator.next().isStarted());
         }
         else
         {
            Assert.assertFalse(connectorServiceIterator.next().isStarted());
            return;
         }

         twitter.updateStatus(testMessage);

         TransportConfiguration tpconf = new TransportConfiguration(UnitTestCase.INVM_CONNECTOR_FACTORY);
         locator = HornetQClient.createServerLocatorWithoutHA(tpconf);
         ClientSessionFactory sf = locator.createSessionFactory();
         session = sf.createSession(false, true, true);
         ClientConsumer consumer = session.createConsumer(queue);
         session.start();
         ClientMessage msg = consumer.receive(60*1000);
         
         Assert.assertNotNull(msg);
         Assert.assertEquals(testMessage, msg.getBodyBuffer().readString());
         
         msg.acknowledge();
      }
      finally
      {
         try
         {
            session.close();
         }
         catch(Throwable t)
         {
         }
         
         try
         {
            locator.close();
         }
         catch(Throwable ignored)
         {
         }
         
         try
         {
            server0.stop();
         }
         catch(Throwable ignored)
         {
         }
      }
   }

   protected void internalTestIncomingFailedToInitialize(HashMap<String,String> params) throws Exception
   {
      HornetQServer server0 = null;
      String connectorName = "test-incoming-connector"; 
      String queue = "TwitterTestQueue";
      String consumerKey = "invalidConsumerKey";
      String consumerSecret = "invalidConsumerSecret";
      String accessToken = "invalidAccessToken";
      String accessTokenSecret = "invalidAccessTokenSecret";
      int interval = 5;
      
      if(params.containsKey(KEY_CONNECTOR_NAME))
      {
         connectorName = params.get(KEY_CONNECTOR_NAME);
      }
      if(params.containsKey(KEY_CONSUMER_KEY))
      {
         consumerKey = params.get(KEY_CONSUMER_KEY);
      }
      if(params.containsKey(KEY_CONSUMER_SECRET))
      {
         consumerSecret = params.get(KEY_CONSUMER_SECRET);
      }
      if(params.containsKey(KEY_ACCESS_TOKEN))
      {
         accessToken = params.get(KEY_ACCESS_TOKEN);
      }
      if(params.containsKey(KEY_ACCESS_TOKEN_SECRET))
      {
         accessTokenSecret = params.get(KEY_ACCESS_TOKEN_SECRET);
      }
      if(params.containsKey(KEY_QUEUE_NAME))
      {
         queue = params.get(KEY_QUEUE_NAME);
      }
      
      try
      {
         Configuration configuration = createDefaultConfig(false);
         HashMap<String, Object> config = new HashMap<String, Object>();
         config.put(TwitterConstants.INCOMING_INTERVAL, interval);
         config.put(TwitterConstants.QUEUE_NAME, queue);
         config.put(TwitterConstants.CONSUMER_KEY, consumerKey);
         config.put(TwitterConstants.CONSUMER_SECRET, consumerSecret);
         config.put(TwitterConstants.ACCESS_TOKEN, accessToken);
         config.put(TwitterConstants.ACCESS_TOKEN_SECRET, accessTokenSecret);
         ConnectorServiceConfiguration inconf =
               new ConnectorServiceConfiguration(TwitterIncomingConnectorServiceFactory.class.getName(),
                     config,
               connectorName);
         configuration.getConnectorServiceConfigurations().add(inconf);
         CoreQueueConfiguration qc = new CoreQueueConfiguration(queue, queue, null, true);
         configuration.getQueueConfigurations().add(qc);

         server0 = createServer(false,configuration);
         server0.start();

         Set<ConnectorService> conns = server0.getConnectorsService().getConnectors();
         Assert.assertEquals(1, conns.size());
         Iterator<ConnectorService> it = conns.iterator();
         Assert.assertFalse(it.next().isStarted());
      }
      finally
      {
         try
         {
            server0.stop();
         }
         catch(Throwable ignored)
         {
         }
      }
   }

   protected void internalTestOutgoing(boolean createQueue, boolean restart) throws Exception
   {
      HornetQServer server0 = null;
      ServerLocator locator = null;
      ClientSession session = null;
      String queue = "TwitterTestQueue";
      Twitter twitter = new TwitterFactory().getOAuthAuthorizedInstance(TWITTER_CONSUMER_KEY,
                                                                        TWITTER_CONSUMER_SECRET,
                                                                        new AccessToken(TWITTER_ACCESS_TOKEN,
                                                                                        TWITTER_ACCESS_TOKEN_SECRET));
      String testMessage = "TwitterTest/outgoing: " + System.currentTimeMillis();
      log.debug("test outgoing: " + testMessage);

      try
      {
         Configuration configuration = createDefaultConfig(false);
         HashMap<String, Object> config = new HashMap<String, Object>();
         config.put(TwitterConstants.QUEUE_NAME, queue);
         config.put(TwitterConstants.CONSUMER_KEY, TWITTER_CONSUMER_KEY);
         config.put(TwitterConstants.CONSUMER_SECRET, TWITTER_CONSUMER_SECRET);
         config.put(TwitterConstants.ACCESS_TOKEN, TWITTER_ACCESS_TOKEN);
         config.put(TwitterConstants.ACCESS_TOKEN_SECRET, TWITTER_ACCESS_TOKEN_SECRET);
         ConnectorServiceConfiguration outconf =
               new ConnectorServiceConfiguration(TwitterOutgoingConnectorServiceFactory.class.getName(),
                     config,
               "test-outgoing-connector");
         configuration.getConnectorServiceConfigurations().add(outconf);
         if(createQueue)
         {
            CoreQueueConfiguration qc = new CoreQueueConfiguration(queue, queue, null, false);
            configuration.getQueueConfigurations().add(qc);
         }

         server0 = createServer(false,configuration);
         server0.start();

         if(restart)
         {
            server0.getConnectorsService().stop();
            server0.getConnectorsService().start();
         }

         assertEquals(1, server0.getConnectorsService().getConnectors().size());
         Iterator<ConnectorService> connectorServiceIterator = server0.getConnectorsService().getConnectors().iterator();
         if(createQueue)
         {
            Assert.assertTrue(connectorServiceIterator.next().isStarted());
         }
         else
         {
            Assert.assertFalse(connectorServiceIterator.next().isStarted());
            return;
         }

         TransportConfiguration tpconf = new TransportConfiguration(UnitTestCase.INVM_CONNECTOR_FACTORY);
         locator = HornetQClient.createServerLocatorWithoutHA(tpconf);
         ClientSessionFactory sf = locator.createSessionFactory();
         session = sf.createSession(false, true, true);
         ClientProducer producer = session.createProducer(queue);
         ClientMessage msg = session.createMessage(false);
         msg.getBodyBuffer().writeString(testMessage);
         session.start();
         producer.send(msg);

         Thread.sleep(3000);

         Paging page = new Paging();
         page.setCount(1);
         ResponseList<Status> res = twitter.getHomeTimeline(page);

         Assert.assertEquals(testMessage, res.get(0).getText());
      }
      finally
      {
         try
         {
            session.close();
         }
         catch(Throwable t)
         {
         }
         
         try
         {
            locator.close();
         }
         catch(Throwable t)
         {
         }
         
         try
         {
            server0.stop();
         }
         catch(Throwable ignored)
         {
         }
      }
   }

   protected void internalTestOutgoingFailedToInitialize(HashMap<String,String> params) throws Exception
   {
      HornetQServer server0 = null;
      String connectorName = "test-outgoing-connector";
      String queue = "TwitterTestQueue";
      String consumerKey = TWITTER_CONSUMER_KEY;
      String consumerSecret = TWITTER_CONSUMER_SECRET;
      String accessToken = TWITTER_ACCESS_TOKEN;
      String accessTokenSecret = TWITTER_ACCESS_TOKEN_SECRET;
      
      if(params.containsKey(KEY_CONNECTOR_NAME))
      {
         connectorName = params.get(KEY_CONNECTOR_NAME);
      }
      if (params.containsKey(KEY_CONSUMER_KEY))
      {
         consumerKey = params.get(KEY_CONSUMER_KEY);
      }
      if (params.containsKey(KEY_CONSUMER_SECRET))
      {
         consumerSecret = params.get(KEY_CONSUMER_SECRET);
      }
      if (params.containsKey(KEY_ACCESS_TOKEN))
      {
         accessToken = params.get(KEY_ACCESS_TOKEN);
      }
      if (params.containsKey(KEY_ACCESS_TOKEN_SECRET))
      {
         accessTokenSecret = params.get(KEY_ACCESS_TOKEN_SECRET);
      }
      if (params.containsKey(KEY_QUEUE_NAME))
      {
         queue = params.get(KEY_QUEUE_NAME);
      }
      
      try
      {
         Configuration configuration = createDefaultConfig(false);
         HashMap<String, Object> config = new HashMap<String, Object>();
         config.put(TwitterConstants.QUEUE_NAME, queue);
         config.put(TwitterConstants.CONSUMER_KEY, consumerKey);
         config.put(TwitterConstants.CONSUMER_SECRET, consumerSecret);
         config.put(TwitterConstants.ACCESS_TOKEN, accessToken);
         config.put(TwitterConstants.ACCESS_TOKEN_SECRET, accessTokenSecret);
         ConnectorServiceConfiguration outconf =
               new ConnectorServiceConfiguration(TwitterOutgoingConnectorServiceFactory.class.getName(),
                     config,
               connectorName);
         configuration.getConnectorServiceConfigurations().add(outconf);
         CoreQueueConfiguration qc = new CoreQueueConfiguration(queue, queue, null, false);
         configuration.getQueueConfigurations().add(qc);
         
         server0 = createServer(false,configuration);
         server0.start();

      }
      finally
      {
         try
         {
            server0.stop();
         }
         catch(Throwable ignored)
         {
         }
      }
   }

   protected void internalTestOutgoingWithInReplyTo() throws Exception
   {
      HornetQServer server0 = null;
      ClientSession session = null;
      ServerLocator locator = null;
      String queue = "TwitterTestQueue";
      Twitter twitter = new TwitterFactory().getOAuthAuthorizedInstance(TWITTER_CONSUMER_KEY,
                                                                        TWITTER_CONSUMER_SECRET,
                                                                        new AccessToken(TWITTER_ACCESS_TOKEN,
                                                                                        TWITTER_ACCESS_TOKEN_SECRET));
      String testMessage = "TwitterTest/outgoing with in_reply_to: " + System.currentTimeMillis();
      String replyMessage = "@" + twitter.getScreenName() + " TwitterTest/outgoing reply: " + System.currentTimeMillis();
      try
      {
         Configuration configuration = createDefaultConfig(false);
         HashMap<String, Object> config = new HashMap<String, Object>();
         config.put(TwitterConstants.QUEUE_NAME, queue);
         config.put(TwitterConstants.CONSUMER_KEY, TWITTER_CONSUMER_KEY);
         config.put(TwitterConstants.CONSUMER_SECRET, TWITTER_CONSUMER_SECRET);
         config.put(TwitterConstants.ACCESS_TOKEN, TWITTER_ACCESS_TOKEN);
         config.put(TwitterConstants.ACCESS_TOKEN_SECRET, TWITTER_ACCESS_TOKEN_SECRET);
         ConnectorServiceConfiguration outconf =
               new ConnectorServiceConfiguration(TwitterOutgoingConnectorServiceFactory.class.getName(),
                     config,
               "test-outgoing-with-in-reply-to");
         configuration.getConnectorServiceConfigurations().add(outconf);
         CoreQueueConfiguration qc = new CoreQueueConfiguration(queue, queue, null, false);
         configuration.getQueueConfigurations().add(qc);

         Status s = twitter.updateStatus(testMessage);

         server0 = createServer(false,configuration);
         server0.start();
         
         TransportConfiguration tpconf = new TransportConfiguration(UnitTestCase.INVM_CONNECTOR_FACTORY);
         locator = HornetQClient.createServerLocatorWithoutHA(tpconf);
         
         ClientSessionFactory sf = locator.createSessionFactory();
         session = sf.createSession(false, true, true);
         ClientProducer producer = session.createProducer(queue);
         ClientMessage msg = session.createMessage(false);
         msg.getBodyBuffer().writeString(replyMessage);
         msg.putLongProperty(TwitterConstants.KEY_IN_REPLY_TO_STATUS_ID, s.getId());
         session.start();
         producer.send(msg);

         Thread.sleep(3000);
         
         Paging page = new Paging();
         page.setCount(2);
         ResponseList<Status> res = twitter.getHomeTimeline(page);
         
         Assert.assertEquals(testMessage, res.get(1).getText());
         Assert.assertEquals(-1, res.get(1).getInReplyToStatusId());
         Assert.assertEquals(replyMessage, res.get(0).getText());
         Assert.assertEquals(s.getId(), res.get(0).getInReplyToStatusId());
      }
      finally
      {
         try
         {
            session.close();
         }
         catch(Throwable t)
         {
         }
         try
         {
            locator.close();
         }
         catch(Throwable t)
         {
         }
         try
         {
            server0.stop();
         }
         catch(Throwable ignored)
         {
         }
      }
   }
}
