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

package org.hornetq.tests.integration.cluster.distribution;

import org.hornetq.core.logging.Logger;

/**
 * A OnewayTwoNodeClusterTest
 *
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 * 
 * Created 30 Jan 2009 18:03:28
 *
 *
 */
public class TwoWayTwoNodeClusterTest extends ClusterTestBase
{
   private static final Logger log = Logger.getLogger(OnewayTwoNodeClusterTest.class);

   @Override
   protected void setUp() throws Exception
   {
      super.setUp();

      setupServers();
      setupClusters();
   }
   
   protected void setupServers()
   {
      setupServer(0, isFileStorage(), isNetty());
      setupServer(1, isFileStorage(), isNetty());      
   }

   protected void setupClusters()
   {
      setupClusterConnection("cluster0", 0, 1, "queues", false, 1, isNetty(), false);
      setupClusterConnection("cluster1", 1, 0, "queues", false, 1, isNetty(), false);
   }

   @Override
   protected void tearDown() throws Exception
   {
      log.info("#test tearDown");
      closeAllConsumers();

      closeAllSessionFactories();

      closeAllServerLocatorsFactories();

      stopServers(0, 1);

      super.tearDown();
   }

   protected boolean isNetty()
   {
      return false;
   }

   public void testStartStop() throws Exception
   {

      startServers(0, 1);

      setupSessionFactory(0, isNetty());
      setupSessionFactory(1, isNetty());

      createQueue(0, "queues", "queue0", null, false);
      createQueue(1, "queues", "queue0", null, false);

      addConsumer(0, 0, "queue0", null);
      addConsumer(1, 1, "queue0", null);

      waitForBindings(0, "queues", 1, 1, true);
      waitForBindings(1, "queues", 1, 1, true);

      waitForBindings(0, "queues", 1, 1, false);
      waitForBindings(1, "queues", 1, 1, false);

      send(0, "queues", 10, false, null);
      verifyReceiveRoundRobin(10, 0, 1);
      verifyNotReceive(0, 1);

      stopServers(0, 1);
   }
   
   public void testStartPauseStartOther() throws Exception
   {

      startServers(0);

      setupSessionFactory(0, isNetty());
      createQueue(0, "queues", "queue0", null, false);
      addConsumer(0, 0, "queue0", null);
      
      // we let the discovery initial timeout expire, 
      // #0 will be alone in the cluster
      Thread.sleep(12000);
      
      startServers(1);
      setupSessionFactory(1, isNetty());
      createQueue(1, "queues", "queue0", null, false);

      addConsumer(1, 1, "queue0", null);

      waitForBindings(0, "queues", 1, 1, true);
      waitForBindings(1, "queues", 1, 1, true);

      waitForBindings(0, "queues", 1, 1, false);
      waitForBindings(1, "queues", 1, 1, false);

      send(0, "queues", 10, false, null);
      verifyReceiveRoundRobin(10, 0, 1);
      verifyNotReceive(0, 1);

      stopServers(0, 1);
   }
   
   public void testLoop() throws Exception
   {
      for (int i = 0 ; i < 100; i++)
      {
         log.info("#test " + i);
         testStopStart();
         tearDown();
         setUp();
      }
   }

   public void testStopStart() throws Exception
   {
      startServers(0, 1);

      setupSessionFactory(0, isNetty());
      setupSessionFactory(1, isNetty());

      createQueue(0, "queues", "queue0", null, false);
      createQueue(1, "queues", "queue0", null, false);

      addConsumer(0, 0, "queue0", null);
      addConsumer(1, 1, "queue0", null);

      waitForBindings(0, "queues", 1, 1, true);
      waitForBindings(1, "queues", 1, 1, true);

      waitForBindings(0, "queues", 1, 1, false);
      waitForBindings(1, "queues", 1, 1, false);

      send(0, "queues", 10, false, null);
      verifyReceiveRoundRobin(10, 0, 1);
      verifyNotReceive(0, 1);

      removeConsumer(1);
      
      closeSessionFactory(1);
      
      log.info("*********** Stopping server 1");
      stopServers(1);
      log.info("*********** Stopped server 1");

      System.out.println(clusterDescription(servers[0]));
      
      Thread.sleep(5000);

      log.info ("********* Starting server 1");
      startServers(1);

      log.info ("********* Describing servers");
      log.info(servers[0].describe());
      log.info(servers[1].describe());

      setupSessionFactory(1, isNetty());

      createQueue(1, "queues", "queue0", null, false);

      addConsumer(1, 1, "queue0", null);

      waitForBindings(0, "queues", 1, 1, true);
      waitForBindings(1, "queues", 1, 1, true);

      waitForBindings(1, "queues", 1, 1, false);
      waitForBindings(0, "queues", 1, 1, false);

      send(0, "queues", 10, false, null);
      verifyReceiveRoundRobin(10, 0, 1);
      verifyNotReceive(0, 1);

       stopServers(0, 1);
   }
}
