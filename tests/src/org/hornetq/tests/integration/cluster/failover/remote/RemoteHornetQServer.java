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

package org.hornetq.tests.integration.cluster.failover.remote;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

import org.hornetq.core.server.HornetQServer;
import org.hornetq.core.server.HornetQServers;
import org.hornetq.tests.util.SpawnedVMSupport;

/**
 * A RemoteHornetQServer
 *
 * @author jmesnil
 *
 *
 */
public class RemoteHornetQServer
{

   // Constants -----------------------------------------------------

   // Attributes ----------------------------------------------------

   // Static --------------------------------------------------------

   public static void main(final String[] args) throws Exception
   {
      try
      {
         String serverClass = args[0];
         System.out.println("Instantiate " + serverClass);
         RemoteServerConfiguration spawnedServer = (RemoteServerConfiguration)Class.forName(serverClass).newInstance();
         
         HornetQServer server = HornetQServers.newHornetQServer(spawnedServer.getConfiguration());
         server.start();

         System.out.println("Server started, ready to start client test");

         // create the reader before printing OK so that if the test is quick
         // we will still capture the STOP message sent by the client
         InputStreamReader isr = new InputStreamReader(System.in);
         BufferedReader br = new BufferedReader(isr);

         System.out.println("OK");

         String line = null;
         while ((line = br.readLine()) != null)
         {
            if ("STOP".equals(line.trim()))
            {
               //server.stop();
               System.out.println("Server stopped");
               System.exit(0);
            }
            else
            {
               // stop anyway but with a error status
               System.out.println("Server crashed");
               System.exit(1);
            }
         }
      }
      catch (Throwable t)
      {
         t.printStackTrace();
         String allStack = t.getCause().getMessage() + "|";
         StackTraceElement[] stackTrace = t.getCause().getStackTrace();
         for (StackTraceElement stackTraceElement : stackTrace)
         {
            allStack += stackTraceElement.toString() + "|";
         }
         System.out.println(allStack);
         System.out.println("KO");
         System.exit(1);
      }
   }

   
   public static Process start(String serverClassName) throws Exception
   {
      String[] vmArgs = new String[] { "-Dorg.hornetq.logger-delegate-factory-class-name=org.hornetq.jms.SysoutLoggerDelegateFactory" };
      Process serverProcess = SpawnedVMSupport.spawnVM(RemoteHornetQServer.class.getName(), vmArgs, false, serverClassName);
      InputStreamReader isr = new InputStreamReader(serverProcess.getInputStream());

      final BufferedReader br = new BufferedReader(isr);
      String line = null;
      while ((line = br.readLine()) != null)
      {
         System.out.println("SERVER: " + line);
         line.replace('|', '\n');
         if ("OK".equals(line.trim()))
         {
            new Thread()
            {
               @Override
               public void run()
               {
                  try
                  {
                     String line = null;
                     while ((line = br.readLine()) != null)
                     {
                        System.out.println("SERVER: " + line);
                     }
                  }
                  catch (Exception e)
                  {
                     e.printStackTrace();
                  }
               }
            }.start();
            return serverProcess;
         }
         else if ("KO".equals(line.trim()))
         {
            // something went wrong with the server, destroy it:
            serverProcess.destroy();
            throw new IllegalStateException("Unable to start the spawned server :" + line);
         }
      }
      return serverProcess;
   }

   public static void stop(Process serverProcess) throws Exception
   {
      OutputStreamWriter osw = new OutputStreamWriter(serverProcess.getOutputStream());
      osw.write("STOP\n");
      osw.flush();
      int exitValue = serverProcess.waitFor();
      if (exitValue != 0)
      {
         serverProcess.destroy();
      }
   }
   
   public static void crash(Process serverProcess) throws Exception
   {
      OutputStreamWriter osw = new OutputStreamWriter(serverProcess.getOutputStream());
      osw.write("KILL\n");
      osw.flush();
      int exitValue = serverProcess.waitFor();
      if (exitValue != 0)
      {
         serverProcess.destroy();
      }
   }
   // Constructors --------------------------------------------------

   // Public --------------------------------------------------------

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------

}
