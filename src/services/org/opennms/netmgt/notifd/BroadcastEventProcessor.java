//
// This file is part of the OpenNMS(R) Application.
//
// OpenNMS(R) is Copyright (C) 2002-2003 Blast Internet Services, Inc.  All rights reserved.
// OpenNMS(R) is a derivative work, containing both original code, included code and modified
// code that was published under the GNU General Public License. Copyrights for modified 
// and included code are below.
//
// OpenNMS(R) is a registered trademark of Blast Internet Services, Inc.
//
// Modifications:
//
// 2004 Aug 26: Added the ability to trigger notifications on an event and a parameter.
// 2003 Sep 30: Added a change to support SNMP Thresholding notices.
// 2003 Jan 31: Cleaned up some unused imports.
// 2002 Nov 09: Added code to allow an event to match more than one notice.
// 2002 Oct 29: Added the ability to include event files in eventconf.xml.
// 2002 Oct 24: Option to auto-acknowledge "up" notifications as well as "down". Bug #544.
// 
// Original code base Copyright (C) 1999-2001 Oculan Corp.  All rights reserved.
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.                                                            
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
//       
// For more information contact: 
//      OpenNMS Licensing       <license@opennms.org>
//      http://www.opennms.org/
//      http://www.blast.com/
//
// Tab Size = 8
//

package org.opennms.netmgt.notifd;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Category;
import org.apache.regexp.RE;
import org.apache.regexp.RESyntaxException;
import org.exolab.castor.xml.MarshalException;
import org.exolab.castor.xml.ValidationException;
import org.opennms.core.utils.ThreadCategory;
import org.opennms.core.utils.TimeConverter;
import org.opennms.netmgt.EventConstants;
import org.opennms.netmgt.config.DatabaseConnectionFactory;
import org.opennms.netmgt.config.DestinationPathFactory;
import org.opennms.netmgt.config.GroupFactory;
import org.opennms.netmgt.config.NotifdConfigFactory;
import org.opennms.netmgt.config.NotificationCommandFactory;
import org.opennms.netmgt.config.NotificationFactory;
import org.opennms.netmgt.config.UserFactory;
import org.opennms.netmgt.config.destinationPaths.Escalate;
import org.opennms.netmgt.config.destinationPaths.Path;
import org.opennms.netmgt.config.destinationPaths.Target;
import org.opennms.netmgt.config.groups.Group;
import org.opennms.netmgt.config.notifd.AutoAcknowledge;
import org.opennms.netmgt.config.notificationCommands.Command;
import org.opennms.netmgt.config.notifications.Notification;
import org.opennms.netmgt.config.notifications.Parameter;
import org.opennms.netmgt.config.PollOutagesConfigFactory;
import org.opennms.netmgt.config.users.Contact;
import org.opennms.netmgt.config.users.User;
import org.opennms.netmgt.eventd.EventIpcManagerFactory;
import org.opennms.netmgt.eventd.EventListener;
import org.opennms.netmgt.eventd.EventUtil;
import org.opennms.netmgt.xml.event.Event;
import org.opennms.netmgt.xml.event.Logmsg;
import org.opennms.netmgt.xml.event.Parm;
import org.opennms.netmgt.xml.event.Parms;
import org.opennms.netmgt.xml.event.Value;


/**
 *
 * @author <a href="mailto:weave@oculan.com">Brian Weaver</a>
 * @author <a href="http://www.opennms.org/">OpenNMS</a>
 */
final class BroadcastEventProcessor
	implements EventListener
{
	/**
	*/
	private Map m_noticeQueues;
	
	/**
         * A regular expression for matching an expansion parameter delimited by percent signs.
         */
        private static final String NOTIFD_EXPANSION_PARM = "%(noticeid)%";
        
        private static RE notifdExpandRE;
        
        /**
         * Initializes the expansion regular expression. The exception is going to be thrown away if the RE can't 
         * be compiled, thus the complilation should be tested prior to runtime.
         */
        static 
        {
                try {
                        notifdExpandRE = new RE(NOTIFD_EXPANSION_PARM);
                } catch (RESyntaxException e)
                {
                        //this shouldn't throw an exception, should be tested prior to runtime
                        ThreadCategory.getInstance(BroadcastEventProcessor.class).error("failed to compile RE " + NOTIFD_EXPANSION_PARM, e);
                }
        }
        
	/**
	 * This constructor is called to initilize the event receiver.
	 * A connection to the message server is opened and this instance
	 * is setup as the endpoint for broadcast events. When a new
	 * event arrives it is processed and the appropriate action
	 * is taken.
	 *
	 */
	BroadcastEventProcessor(Map noticeQueues)
	{
		// set up the exectuable queue first
		//
		m_noticeQueues = noticeQueues;

		//initialize the factory instances
		try
		{
			DatabaseConnectionFactory.init();
			GroupFactory.init();
			UserFactory.init();
			NotificationFactory.init();
			DestinationPathFactory.init();
			NotificationCommandFactory.init();
		}
		catch (Exception e)
		{
			ThreadCategory.getInstance(getClass()).error("Error getting group, user notification or command factory instances: " + e.getMessage(), e);
			return;
		}

		// start to listen for events
		EventIpcManagerFactory.init();
		EventIpcManagerFactory.getInstance().getManager().addEventListener(this);
	}

	/**
	 * Unsubscribe from eventd
	 */
	public void close()
	{
		EventIpcManagerFactory.getInstance().getManager().removeEventListener(this);
	}

	/**
	 * This method is invoked by the EventIpcManager
	 * when a new event is available for processing.
	 *
	 * @param event	The event .
	 */
	public void onEvent(Event event)
	{
		if (event == null)
			return;
		
		String status = "off";
		try
		{
			status = NotifdConfigFactory.getNotificationStatus();
		}
		catch (Exception e)
		{
			ThreadCategory.getInstance(getClass()).error("error getting notifd status, assuming status = 'off' for now: ", e); 
		}
		
		if (status.equals("on"))
		{
			scheduleNoticesForEvent(event);
		}
		else
		{
			Category log = ThreadCategory.getInstance(getClass());
			if (log.isDebugEnabled())
				log.debug("discarding event " + event.getUei() + ", notifd status = " + status);
		}

		automaticAcknowledge(event);

	} // end onEvent()


	/**
	 * Returns true if an auto acknowledgment exists for the specificed event, such that the arrival of some second, different event
	 * will auto acknowledge the event passed as an argument.  E.g. if there is an auto ack set up to acknowledge a nodeDown when a 
	 * nodeUp is received, passing nodeDown to this method will return true.
	 * Should this method be in NotifdConfigFactory?
	 */
	private boolean autoAckExistsForEvent(String eventUei) {
		try {
		Collection ueis = NotifdConfigFactory.getInstance().getConfiguration().getAutoAcknowledgeCollection();
		Iterator i = ueis.iterator();
		while(i.hasNext())
		{
			AutoAcknowledge curAck = (AutoAcknowledge)i.next();
			if(curAck.getAcknowledge().equals(eventUei)) {
				return true;
			}
		}
		return false;
		} catch (Exception e) {
			ThreadCategory.getInstance(getClass()).error("Unable to find if an auto acknowledge exists for event "+eventUei+" due to exception.", e);
			return false;
		}
	}

	/**
	 *
	 */
	private void automaticAcknowledge(Event event)
	{
		try
                {
		Collection ueis = NotifdConfigFactory.getConfiguration().getAutoAcknowledgeCollection();
		
		List origNotifIds = null;		
		//see if this event has an auto acknowledge for a notice
		Iterator i = ueis.iterator();
		while(i.hasNext())
		{
			AutoAcknowledge curAck = (AutoAcknowledge)i.next();
			if (curAck.getUei().equals(event.getUei()))
			{
				try
				{
					ThreadCategory.getInstance(getClass()).debug("Acknowledging event " + curAck.getAcknowledge() + " " + event.getNodeid()+":"+event.getInterface()+":"+event.getService());
					//Get a copy of all unacknowledged messages for the original event - we need them a bit later
					origNotifIds =
						NotificationFactory.getNotifIds(
							event,
							curAck.getAcknowledge(),
							curAck.getMatch());

					NotificationFactory.getInstance().acknowledgeNotice(event, curAck.getAcknowledge(), curAck.getMatch());
				}
				catch (SQLException e)
				{
					ThreadCategory.getInstance(getClass()).error("Failed to auto acknowledge notice.", e);
				}
			}
			// if the clear flag is set, swap the event uei as the ack uei and ack the second notice
			if (curAck.getUei().equals(event.getUei()) && curAck.getClear())
			{
                                try
                                {
                                        ThreadCategory.getInstance(getClass()).debug("Acknowledging source event " + event.getUei() + " " + event.getNodeid()+":"+event.getInterface()+":"+event.getService());
                                        NotificationFactory.getInstance().acknowledgeNotice(event, event.getUei(), curAck.getMatch());
                                }
                                catch (SQLException e)
                                {
                                        ThreadCategory.getInstance(getClass()).error("Failed to auto acknowledge source notice.", e);
                                }
				//Now check for anyone who received the original notice (and who hasn't ack'd their message yet)
				//and send a notice to them so they know things are fixed
				Iterator iter = origNotifIds.iterator();
				while (iter.hasNext()) {
					int thisNotifId = ((Integer) iter.next()).intValue();
					sendMessageToReceiversOfNotice(thisNotifId, event);
				}
                        }

		}
	}
                catch (Exception e)
                {
                        ThreadCategory.getInstance(getClass()).error("Unable to auto acknowledge notice due to exception.", e);
                }
	}

	/**
	 * Sends a standard message about "event" (not a notification) to all who have received the notification
	 * identified by notificationId
	 * 
	 * Used on auto-acked events to send "up" messages to those who received the "down" message, when clear=true
	 * @param notificationId
	 * @param event
	 */
	private void sendMessageToReceiversOfNotice(int origNotifId, Event secondEvent) {

		long now = System.currentTimeMillis();
		try {
			//Find the users who got the original notification and interate over them
			List usersNotified = NotificationFactory.getInstance().getUsersNotified(origNotifId);
			Iterator iter = usersNotified.iterator();
			while (iter.hasNext()) {
				Map userInfo = (Map) iter.next();
				String userId = (String) userInfo.get("userId");
				//We need to know which command was originally used to contact the user in question
				String[] commands = new String[] {(String) userInfo.get("command")};
				//This is a little counter-intuitive.  We need to find all notifications for the *second* event
				// (the one which, when recieved, caused the original notification to be auto-ack).  Typically
				// this will be only one, but we need all of them in order to send all appropriate "up" notification
				// messages.  Much of this next section is duplicated code from elsewhere in this class, but slightly 
				// different - it can probably be rather nicely refactored.
				Notification[] notifications =
					NotificationFactory.getInstance().getNotifForEvent(secondEvent);
				if (notifications != null) {
					for (int i = 0; i < notifications.length; i++) {
						Notification notification = notifications[i];
						String queueID =
							(notification.getNoticeQueue() != null
								? notification.getNoticeQueue()
								: "default");

						NoticeQueue noticeQueue = (NoticeQueue) m_noticeQueues.get(queueID);

						List targetSiblings = new ArrayList();
						NotificationTask newTask = null;
						Map params = buildParameterMap(notification, secondEvent, origNotifId);
						if (GroupFactory.getInstance().hasGroup((userId))) {
							Group group = GroupFactory.getInstance().getGroup(userId);
							String[] users = group.getUser();

							if (users != null && users.length > 0) {
								for (int j = 0; j < users.length; j++) {
									newTask =
										makeUserTask(
											now,
											params,
											origNotifId,
											users[j],
											commands,
											targetSiblings);
									//Always send this, even if the notification has been acked (which it will have been in this case)	
									newTask.setAlwaysSend(true);
									//Nuke this list - no-body cares (it's only used to *not* send messages), we just want the messages sent.
									targetSiblings.clear();
									if (newTask != null) {
										noticeQueue.put(new Long(now), newTask);
									}
								}
							} else {
								ThreadCategory.getInstance(getClass()).debug(
									"Not sending notice, no users specified for group "
										+ group.getName());
							}
						} else if (UserFactory.getInstance().hasUser(userId)) {
							newTask =
								makeUserTask(
									now,
									params,
									origNotifId,
									userId,
									commands,
									targetSiblings);
							//Always send this, even if the notification has been acked (which it will have been in this case)	
							newTask.setAlwaysSend(true);
							//Nuke this list - no-body cares (it's only used to *not* send messages), we just want the messages sent.
							targetSiblings.clear();
							if (newTask != null) {
								noticeQueue.put(new Long(now), newTask);
							}

						} else if (userId.indexOf("@") > -1) {
							newTask =
								makeEmailTask(
									now,
									params,
									origNotifId,
									userId,
									commands,
									targetSiblings);
							//Always send this, even if the notification has been acked (which it will have been in this case)	
							newTask.setAlwaysSend(true);
							//Nuke this list - no-body cares (it's only used to *not* send messages), we just want the messages sent.
							targetSiblings.clear();
							if (newTask != null) {
								noticeQueue.put(new Long(now), newTask);
							}

						}
					}

				}
			}
		} catch (ValidationException e) {
			ThreadCategory.getInstance(getClass()).error(
				"error trying to send auto-ack messages:" + e.getMessage());
			return;
		} catch (MarshalException e) {
			ThreadCategory.getInstance(getClass()).error(
				"error trying to send auto-ack messages:" + e.getMessage());
			return;
		} catch (IOException e) {
			ThreadCategory.getInstance(getClass()).error(
				"error trying to send auto-ack messages:" + e.getMessage());
			return;
		}
	}
	
        /**
	 * This method determines if the notice should continue based on the status of the notify
         */
	private boolean continueWithNotice(Event event)
	{
		String nodeID = String.valueOf(event.getNodeid());
		String ipAddr = event.getInterface();
		String service = event.getService();
		
		boolean continueNotice = false;
		
		//can't check the database if any of these are null, so let the notice continue
		if (nodeID==null || ipAddr==null || service==null)
		{
			ThreadCategory.getInstance(getClass()).debug("nodeID=" + nodeID + " ipAddr=" + ipAddr + " service=" + service + ". Not checking DB, allowing notice to continue.");
			return true;
		}
		
		try
		{
			//check the database to see if notices were turned off for this service
			String notify = NotificationFactory.getServiceNoticeStatus(nodeID,
								          	                 ipAddr,
										                 service);
			if ("Y".equals(notify))
			{
				continueNotice = true;
				ThreadCategory.getInstance(getClass()).debug("notify status for service " + service + " on interface/node " + ipAddr + "/" + nodeID + " is 'Y', continuing...");
			}
			else
			{
				ThreadCategory.getInstance(getClass()).debug("notify status for service " + service + " on interface/node " + ipAddr + "/" + nodeID + " is " + notify + ", not continuing...");
			}
		}
		catch (Exception e)
		{
			continueNotice = true;
			ThreadCategory.getInstance(getClass()).error("Not able to get notify status for service " + service + " on interface/node " + ipAddr + "/" + nodeID + ". Continuing notice... "  + e.getMessage());
		}
		
		//in case of a error we will return false
		return continueNotice;
	}
        
	/**
	*/
	private void scheduleNoticesForEvent(Event event)
	{
		Category log = ThreadCategory.getInstance(getClass());
		
                boolean mapsToNotice = false;
                
                try {
                        mapsToNotice = NotificationFactory.getInstance().hasUei(event.getUei());
                } catch (Exception e)
                {
                        log.error("Couldn't map uei " + event.getUei() + " to a notification entry, not scheduling notice.", e);
                        return;
                }
                
                if (mapsToNotice)
		{
			//check to see if notices are turned on for the interface/service in the event
                        if (continueWithNotice(event))
                        {
                                Notification[] notifications = null;
                                
                                try {
                                        notifications = NotificationFactory.getInstance().getNotifForEvent(event);
                                } catch (Exception e)
                                {
                                        log.error("Couldn't get notification mapping for event " + event.getUei() + ", not scheduling notice.", e);
                                        return;
                                }
                                
                                if (notifications != null)
                                {
                                        for (int i = 0; i < notifications.length; i++) {
                                            
                                                Notification notification = notifications[i];

						boolean parmsmatched = matchNotificationParameters(event, notification);

						if (!parmsmatched)
						{
                                                	log.debug("Event " + event.getUei() + " did not match parameters for notice " + notification.getName());
							return;
						}
                                    
                                                log.debug("Event " + event.getUei() + " matched notice " + notification.getName());

                                                int noticeId = 0;

                                                try {
                                                        noticeId = NotificationFactory.getNoticeId();
                                                } catch (Exception e)
                                                {
                                                        log.error("Failed to get a unique id # for notification, exiting this notification", e);
                                                        return;
                                                }

                                                Map paramMap = buildParameterMap(notification, event, noticeId);
                                                String queueID = (notification.getNoticeQueue()!=null ? notification.getNoticeQueue() : "default");

                                                log.debug("destination : " + notification.getDestinationPath());
                                                log.debug("text message: " + (String)paramMap.get(NotificationFactory.PARAM_TEXT_MSG));
                                                log.debug("num message : " + (String)paramMap.get(NotificationFactory.PARAM_NUM_MSG));
                                                log.debug("subject     : " + (String)paramMap.get(NotificationFactory.PARAM_SUBJECT));
                                                log.debug("node        : " + (String)paramMap.get(NotificationFactory.PARAM_NODE));
                                                log.debug("interface   : " + (String)paramMap.get(NotificationFactory.PARAM_INTERFACE));
                                                log.debug("service     : " + (String)paramMap.get(NotificationFactory.PARAM_SERVICE));

                                                //get the target and escalation information
                                                Path path = null;
                                                try {
                                                        path = DestinationPathFactory.getInstance().getPath(notification.getDestinationPath());
                                                        if (path == null)
                                                        {
                                                                log.warn("Unknown destination path " + notification.getDestinationPath() + 
                                                                         ". Please check the <destinationPath> tag for the notification " + 
                                                                         notification.getName() + " in the notification.xml file.");
                                                                return;
                                                        }
                                                } catch (Exception e)
                                                {
                                                        log.error("Could not get destination path for " + notification.getDestinationPath() + ", please check the destinationPath.xml for errors.", e);
                                                        return;
                                                }
                                                String initialDelay = (path.getInitialDelay() == null ? "0s" : path.getInitialDelay());
                                                Target[] targets = path.getTarget();
                                                Escalate[] escalations = path.getEscalate();

                                                //now check to see if any users are to receive the notification, if none then generate an event a exit
                                                try {
                                                        if (getUserCount(targets, escalations)==0)
                                                        {
                                                                log.warn("The path " + notification.getDestinationPath() + " assigned to notification " + notification.getName() + " has no targets or escalations specified, not sending notice.");
                                                                sendNotifEvent(EventConstants.NOTIFICATION_WITHOUT_USERS,
                                                                               "The path " + notification.getDestinationPath() + " assigned to notification " + notification.getName() + " has no targets or escalations specified.",
                                                                               "The message of the notification is as follows: " + (String)paramMap.get(NotificationFactory.PARAM_TEXT_MSG));
                                                                return;
                                                        }
                                                }catch (Exception e)
                                                {
                                                        log.error("Failed to get count of users in destination path " + notification.getDestinationPath() + ", exiting notification.", e);
                                                        return;
                                                }

                                                try { 
                                                        NotificationFactory.insertNotice(noticeId, paramMap);
                                                } catch(SQLException e)
                                                {
                                                        log.error("Failed to enter notification into database, exiting this notification", e);
                                                        return;
                                                }

                                                long startTime = System.currentTimeMillis()+TimeConverter.convertToMillis(initialDelay);
                                                List targetSiblings = new ArrayList();

                                                try {
                                                        NoticeQueue noticeQueue = (NoticeQueue)m_noticeQueues.get(queueID);
                                                        processTargets(targets, targetSiblings, noticeQueue, startTime, paramMap, noticeId);
                                                        processEscalations(escalations, targetSiblings, noticeQueue, startTime, paramMap, noticeId);
                                                }
                                                catch (Exception e)
                                                {
                                                        log.error("notice not scheduled due to error: ", e);
                                                }
                                                
                                        }
                               	}
                                else
                                {
                                        log.debug("Event doesn't match a notice: " + event.getUei() + " : " + event.getNodeid() + " : " + event.getInterface()  + " : " + event.getService());
                                }
                        }
		}
		else
		{
			log.debug("No notice match for uei: " + event.getUei());
		}
	}
	
        /**
         * Detemines the number of users assigned to a list of Target and Escalate lists. 
         * Group names may be specified in these objects and the users will have to be extracted
         * from those groups
         * @param targets the list of Target objects
         * @param escalations the list of Escalate objects
         * @return the total # of users assigned in each Target and Escalate objecst.
         */
        private int getUserCount(Target[] targets, Escalate[] escalations)
                throws IOException, MarshalException, ValidationException
        {
                int totalUsers = 0;
                
                for (int i = 0; i < targets.length; i++)
                {
                        totalUsers += getUsersInTarget(targets[i]);
                }
                
                for (int j = 0; j < escalations.length; j++)
                {
                        Target[] escalationTargets = escalations[j].getTarget();
                        for (int k = 0; k < escalationTargets.length; k++)
                        {
                                totalUsers += getUsersInTarget(escalationTargets[k]);
                        }
                }
                
                return totalUsers;
        }
        
        /**
         *
         */
        private int getUsersInTarget(Target target)
                throws IOException, MarshalException, ValidationException
        {
                int count = 0;
                String targetName = target.getName();
                
                if (GroupFactory.getInstance().hasGroup(targetName))
		{
                        count = GroupFactory.getInstance().getGroup(targetName).getUserCount();
                }
                else if (UserFactory.getInstance().hasUser(targetName))
		{
                        count = 1;
                }
                else if (targetName.indexOf("@") > -1)
		{
                        count = 1;
                }
                
                return count;
        }
        
        /**
         * Sends and event related to a notification
	 *
         * @param uei the UEI for the event
         */
        private void sendNotifEvent(String uei, String logMessage, String description)
        {
                try
		{
                        Logmsg logMsg = new Logmsg();
                        logMsg.setContent(logMessage);
                        
                        Event event = new Event();
			event.setUei(uei);
                        event.setSource("notifd");
                        event.setLogmsg(logMsg);
                        event.setDescr(description);
			event.setTime(EventConstants.formatToString(new java.util.Date()));
                        
                        EventIpcManagerFactory.getInstance().getManager().sendNow(event);
                }
		catch(Throwable t)
		{
			ThreadCategory.getInstance(getClass()).error("Could not send event " + uei, t);
		}
        }
        
	/**
	 *
	 */
        private Map buildParameterMap(Notification notification, Event event, int noticeId)
	{
		Map paramMap = new HashMap();
		Parameter[] parameters = notification.getParameter();
		for (int i = 0; i < parameters.length; i++)
		{
			paramMap.put(parameters[i].getName(), parameters[i].getValue());
		}
		
		//expand the event parameters for the messages
		String text = (notification.getTextMessage()!=null ? notification.getTextMessage() : "No text message supplied.");
		String numeric = (notification.getNumericMessage()!=null ? notification.getNumericMessage() : "111-" + noticeId);
		String subject = (notification.getSubject()!=null ? notification.getSubject() : "Notice #" + noticeId);
		
		paramMap.put("noticeid", Integer.toString(noticeId));
                paramMap.put(NotificationFactory.PARAM_NODE, String.valueOf(event.getNodeid()) );
		paramMap.put(NotificationFactory.PARAM_INTERFACE, event.getInterface());
		paramMap.put(NotificationFactory.PARAM_SERVICE, event.getService());
		paramMap.put("eventID", String.valueOf(event.getDbid()));
		paramMap.put("eventUEI", event.getUei());
                
                //call the notid expansion method before the event expansion because event expansion will
                //throw away any expanion strings it doesn't recognize!
                String textMessage = expandNotifParms(text, paramMap);
                String numericMessage = expandNotifParms(numeric, paramMap);
                String subjectLine = expandNotifParms(subject, paramMap);
                
                String finalTextMessage = EventUtil.expandParms(textMessage, event);
                if (finalTextMessage==null)
                        paramMap.put(NotificationFactory.PARAM_TEXT_MSG, textMessage);
                else
                        paramMap.put(NotificationFactory.PARAM_TEXT_MSG, finalTextMessage);
                
                String finalNumericMessage = EventUtil.expandParms(numericMessage, event);
                if (finalNumericMessage==null)
                        paramMap.put(NotificationFactory.PARAM_NUM_MSG, numericMessage);
                else
                        paramMap.put(NotificationFactory.PARAM_NUM_MSG, finalNumericMessage);
                
                String finalSubjectLine = EventUtil.expandParms(subjectLine, event);
                if (finalSubjectLine==null)
                        paramMap.put(NotificationFactory.PARAM_SUBJECT, subjectLine);
                else
                        paramMap.put(NotificationFactory.PARAM_SUBJECT, finalSubjectLine);
		
		return paramMap;
	}
        
        /**
         * A parameter expansion algorithm, designed to replace strings delimited by percent signs '%' with
         * a value supplied by a Map object.
         * @param inp the input string
         * @param paramMap a map that will supply the substitution values
         */
        public static String expandNotifParms(String inp, Map paramMap)
	{
                String expanded = new String(inp);

                if (notifdExpandRE.match(expanded))
		{
                        String replace = (String)paramMap.get( notifdExpandRE.getParen(1) );
                        if (replace != null)
				{
                                expanded = notifdExpandRE.subst(expanded, replace);
				}
				}

                return expanded;
	}
        
	/**
         *
         */
	private void processTargets(Target[] targets, List targetSiblings, NoticeQueue noticeQueue, long startTime, Map params, int noticeId)
	        throws IOException, MarshalException, ValidationException
	{
		for (int i = 0; i < targets.length; i++)
		{
			String interval = (targets[i].getInterval()==null ? "0s" : targets[i].getInterval());

                        String targetName = targets[i].getName();
			ThreadCategory.getInstance(getClass()).debug("Processing target " + targetName + ":" + interval);
			
			long curSendTime = 0;
			
			if (GroupFactory.getInstance().hasGroup((targetName)))
			{
				Group group = GroupFactory.getInstance().getGroup(targetName);
				String[] users = group.getUser();
				
                                if (users!=null && users.length > 0)
                                {
				for (int j = 0; j < users.length; j++)
				{
					NotificationTask newTask = makeUserTask(startTime + curSendTime, 
								        	params,
										noticeId, 
										users[j], 
										targets[i].getCommand(),
										targetSiblings);
					
					if (newTask != null)
					{
						noticeQueue.put(new Long(startTime + curSendTime), newTask);
						targetSiblings.add(newTask);
						
						curSendTime += TimeConverter.convertToMillis(interval);
					}
				}
                                }
                                else
                                {
                                        ThreadCategory.getInstance(getClass()).debug("Not sending notice, no users specified for group " + group.getName());
                                }
			}
			else if (UserFactory.getInstance().hasUser(targetName))
			{
				NotificationTask newTask = makeUserTask(startTime + curSendTime, 
							        	params, 
									noticeId, 
									targetName, 
									targets[i].getCommand(),
									targetSiblings);
				
				if (newTask != null)
				{
					noticeQueue.put(new Long(startTime + curSendTime), newTask);
					targetSiblings.add(newTask);
				}
			}
			else if (targetName.indexOf("@") > -1)
			{
				NotificationTask newTask = makeEmailTask(startTime + curSendTime, 
							        	 params, 
									 noticeId,
                                                                         targetName,
									 targets[i].getCommand(),
                                                                         targetSiblings);
				
				if (newTask != null)
				{
					noticeQueue.put(new Long(startTime + curSendTime), newTask);
					targetSiblings.add(newTask);
				}
			}
			else
			{
				Category log = ThreadCategory.getInstance(getClass());
				log.warn("Unrecognized target '" + targetName + "' contained in destinationPaths.xml. Please check the configuration.");
			}
		}
	}
	
        /**
         *
         */
	private void processEscalations(Escalate[] escalations, List targetSiblings, NoticeQueue noticeQueue, long startTime, Map params, int noticeId)
	        throws IOException, MarshalException, ValidationException
	{
		for (int i = 0; i < escalations.length; i++)
		{
			Target[] targets = escalations[i].getTarget();
			startTime += TimeConverter.convertToMillis(escalations[i].getDelay());
			processTargets(targets, targetSiblings, noticeQueue, startTime, params, noticeId);
		}
	}
	
	/**
         *
	 */
	private NotificationTask makeUserTask(long sendTime, Map parameters, int noticeId, String targetName, String[] commandList, List siblings)
	        throws IOException, MarshalException, ValidationException
	{
		NotificationTask task = null;
		
		try
		{
                        task = new NotificationTask(sendTime, parameters, siblings);
			
			User user = UserFactory.getInstance().getUser(targetName);
			
                        Command commands[] = new Command[commandList.length];
			for (int i = 0; i < commandList.length; i++)
			{
				commands[i] = NotificationCommandFactory.getInstance().getCommand(commandList[i]);
			}
                        
			//if either piece of information is missing don't add the task to the notifier
			if (user == null)
			{
				ThreadCategory.getInstance(getClass()).error("user " + targetName + " is not a valid user, not adding this user to escalation thread");
				return null;
			}

			task.setUser(user);
			task.setCommands(commands);
			task.setNoticeId(noticeId);
                }
		catch (SQLException e)
		{
			ThreadCategory.getInstance(getClass()).error("Couldn't create user notification task", e);
		}
		
		return task;
	}
        
        /**
         *
	 */
	private NotificationTask makeEmailTask(long sendTime, Map parameters, int noticeId, String address, String[] commandList, List siblings)
	        throws IOException, MarshalException, ValidationException
        {
		NotificationTask task = null;
		
		try
		{
                        task = new NotificationTask(sendTime, parameters, siblings);
			
			User user = new User();
			user.setUserId(address);
                        Contact contact = new Contact();
                        contact.setType("email");
                        ThreadCategory.getInstance(getClass()).debug("email address = " + address);
                        contact.setInfo(address);
                        user.addContact(contact);
                        
                        Command commands[] = new Command[commandList.length];
			for (int i = 0; i < commandList.length; i++)
			{
				commands[i] = NotificationCommandFactory.getInstance().getCommand(commandList[i]);
			}
			
			task.setUser(user);
			task.setCommands(commands);
			task.setNoticeId(noticeId);
                }
		catch (SQLException e)
		{
			ThreadCategory.getInstance(getClass()).error("Couldn't create email notification task", e);
		}
		
		return task;
	}

	/**
	 * Return an id for this event listener
	 */
	public String getName()
	{
		return "Notifd:BroadcastEventProcessor";
	}

        /**
         * See if the event parameters match the variables in the notification.
         *
	 * The purpose of this method is to match parameters, such as those in
	 * a threshold event, with parameters configured for a notification,
	 * such as ds-name.
         *
         * @param event 	The event to process.
         * @param notification  The notification to process.
         *
         */

	// TODO This change only works for one parameter, need to expand it to many.

        private boolean matchNotificationParameters(Event event, Notification notification)
        {
                Category log = ThreadCategory.getInstance(getClass());
                                       
		boolean parmmatch = false;
                Parms parms = event.getParms();
                if (parms != null && notification.getVarbind() != null && notification.getVarbind().getVbname() != null)
                {
                        String parmName = null;
                        Value parmValue = null;
                        String parmContent = null;
                        String notfValue = null;
                        String notfName = notification.getVarbind().getVbname();

			if (notification.getVarbind().getVbvalue() != null)
			{ 
                        	notfValue = notification.getVarbind().getVbvalue();
			}
			else if (log.isDebugEnabled())
			{
                	        log.debug("BroadcastEventProcessor:matchNotificationParameters:  Null value for varbind, assuming true.");
				parmmatch = true;
			}
				
                
                        Enumeration parmEnum = parms.enumerateParm();
                        while(parmEnum.hasMoreElements())
                        {
                                Parm parm = (Parm)parmEnum.nextElement();
                                parmName  = parm.getParmName();
                                parmValue = parm.getValue();
                                if (parmValue == null)
                                        continue;
                                else
                                        parmContent = parmValue.getContent();
        
                                if (parmName.equals(notfName) && parmContent.equals(notfValue))
                                {
                                        parmmatch = true;
                                }
                                       
                        }
                }
		else if (notification.getVarbind() == null || notification.getVarbind().getVbname() == null)
		{
			parmmatch = true;
		}

		return parmmatch;
	}

} // end class
