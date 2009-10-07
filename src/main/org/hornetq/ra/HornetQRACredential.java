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

package org.hornetq.ra;

import java.io.Serializable;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Set;

import javax.resource.spi.ConnectionRequestInfo;
import javax.resource.spi.ManagedConnectionFactory;
import javax.resource.spi.SecurityException;
import javax.resource.spi.security.PasswordCredential;
import javax.security.auth.Subject;

import org.hornetq.core.logging.Logger;

/**
 * Credential information
 * 
 * @author <a href="mailto:adrian@jboss.com">Adrian Brock</a>
 * @author <a href="mailto:jesper.pedersen@jboss.org">Jesper Pedersen</a>
 * @version $Revision: 71554 $
 */
public class HornetQRACredential implements Serializable
{
   /** Serial version UID */
   static final long serialVersionUID = 210476602237497193L;

   /** The logger */
   private static final Logger log = Logger.getLogger(HornetQRACredential.class);

   /** Trace enabled */
   private static boolean trace = log.isTraceEnabled();

   /** The user name */
   private String userName;

   /** The password */
   private String password;

   /**
    * Private constructor
    */
   private HornetQRACredential()
   {
      if (trace)
      {
         log.trace("constructor()");
      }
   }

   /**
    * Get the user name
    * @return The value
    */
   public String getUserName()
   {
      if (trace)
      {
         log.trace("getUserName()");
      }

      return userName;
   }

   /**
    * Set the user name
    * @param userName The value
    */
   private void setUserName(final String userName)
   {
      if (trace)
      {
         log.trace("setUserName(" + userName + ")");
      }

      this.userName = userName;
   }

   /**
    * Get the password
    * @return The value
    */
   public String getPassword()
   {
      if (trace)
      {
         log.trace("getPassword()");
      }

      return password;
   }

   /**
    * Set the password
    * @param password The value
    */
   private void setPassword(final String password)
   {
      if (trace)
      {
         log.trace("setPassword(****)");
      }

      this.password = password;
   }

   /**
    * Get credentials
    * @param mcf The managed connection factory
    * @param subject The subject
    * @param info The connection request info
    * @return The credentials
    * @exception SecurityException Thrown if the credentials cant be retrieved
    */
   public static HornetQRACredential getCredential(final ManagedConnectionFactory mcf,
                                             final Subject subject,
                                             final ConnectionRequestInfo info) throws SecurityException
   {
      if (trace)
      {
         log.trace("getCredential(" + mcf + ", " + subject + ", " + info + ")");
      }

      HornetQRACredential jc = new HornetQRACredential();
      if (subject == null && info != null)
      {
         jc.setUserName(((HornetQRAConnectionRequestInfo)info).getUserName());
         jc.setPassword(((HornetQRAConnectionRequestInfo)info).getPassword());
      }
      else if (subject != null)
      {
         PasswordCredential pwdc = GetCredentialAction.getCredential(subject, mcf);

         if (pwdc == null)
         {
            throw new SecurityException("No password credentials found");
         }

         jc.setUserName(pwdc.getUserName());
         jc.setPassword(new String(pwdc.getPassword()));
      }
      else
      {
         throw new SecurityException("No Subject or ConnectionRequestInfo set, could not get credentials");
      }

      return jc;
   }

   /**
    * String representation
    * @return The representation
    */
   @Override
   public String toString()
   {
      if (trace)
      {
         log.trace("toString()");
      }

      return super.toString() + "{ username=" + userName + ", password=**** }";
   }

   /**
    * Privileged class to get credentials
    */
   private static class GetCredentialAction implements PrivilegedAction<PasswordCredential>
   {
      /** The subject */
      private final Subject subject;

      /** The managed connection factory */
      private final ManagedConnectionFactory mcf;

      /**
       * Constructor
       * @param subject The subject
       * @param mcf The managed connection factory
       */
      GetCredentialAction(final Subject subject, final ManagedConnectionFactory mcf)
      {
         if (trace)
         {
            log.trace("constructor(" + subject + ", " + mcf + ")");
         }

         this.subject = subject;
         this.mcf = mcf;
      }

      /**
       * Run
       * @return The credential
       */
      public PasswordCredential run()
      {
         if (trace)
         {
            log.trace("run()");
         }

         Set<PasswordCredential> creds = subject.getPrivateCredentials(PasswordCredential.class);
         PasswordCredential pwdc = null;

         for (PasswordCredential curCred : creds)
         {
            if (curCred.getManagedConnectionFactory().equals(mcf))
            {
               pwdc = curCred;
               break;
            }
         }
         return pwdc;
      }

      /**
       * Get credentials
       * @param subject The subject
       * @param mcf The managed connection factory
       * @return The credential
       */
      static PasswordCredential getCredential(final Subject subject, final ManagedConnectionFactory mcf)
      {
         if (trace)
         {
            log.trace("getCredential(" + subject + ", " + mcf + ")");
         }

         GetCredentialAction action = new GetCredentialAction(subject, mcf);
         return AccessController.doPrivileged(action);
      }
   }
}
