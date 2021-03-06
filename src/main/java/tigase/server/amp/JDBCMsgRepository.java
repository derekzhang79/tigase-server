/*
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2012 "Artur Hefczyc" <artur.hefczyc@tigase.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 *
 * $Rev$
 * Last modified by $Author$
 * $Date$
 */
package tigase.server.amp;

//~--- non-JDK imports --------------------------------------------------------

import java.security.NoSuchAlgorithmException;
import java.sql.DataTruncation;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import tigase.db.DBInitException;
import tigase.db.DataRepository;
import tigase.db.DataRepository.dbTypes;
import tigase.db.MsgRepositoryIfc;
import tigase.db.Repository;
import tigase.db.RepositoryFactory;
import tigase.db.TigaseDBException;
import tigase.db.UserNotFoundException;
import tigase.server.Packet;
import tigase.util.Algorithms;
import tigase.util.SimpleCache;
import tigase.xml.DomBuilderHandler;
import tigase.xml.Element;
import tigase.xml.SimpleParser;
import tigase.xml.SingletonFactory;
import tigase.xmpp.BareJID;
import tigase.xmpp.JID;

//~--- classes ----------------------------------------------------------------

/**
 * Created: May 3, 2010 5:28:02 PM
 * 
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
@Repository.Meta( isDefault=true, supportedUris = { "jdbc:[^:]+:.*" } )
public class JDBCMsgRepository extends MsgRepository<Long> {
	private static final Logger log = Logger.getLogger(JDBCMsgRepository.class.getName());
	private static final String MSG_TABLE = "msg_history";
	private static final String MSG_ID_COLUMN = "msg_id";
	private static final String MSG_TIMESTAMP_COLUMN = "ts";
	private static final String MSG_EXPIRED_COLUMN = "expired";
	private static final String MSG_FROM_UID_COLUMN = "sender_uid";
	private static final String MSG_TO_UID_COLUMN = "receiver_uid";
	private static final String MSG_BODY_COLUMN = "message";
	private static final String HISTORY_FLAG_COLUMN = "history_enabled";
	private static final String JID_TABLE = "user_jid";
	private static final String JID_ID_COLUMN = "jid_id";
	private static final String JID_SHA_COLUMN = "jid_sha";
	private static final String JID_COLUMN = "jid";
	/* @formatter:off */
	private static final String MYSQL_CREATE_MSG_TABLE =
							"create table " + MSG_TABLE + " ( " + "  "
							+ MSG_ID_COLUMN + " serial," + "  "
							+ MSG_TIMESTAMP_COLUMN + " TIMESTAMP DEFAULT CURRENT_TIMESTAMP," + "  "
							+ MSG_EXPIRED_COLUMN + " DATETIME," + "  "
							+ MSG_FROM_UID_COLUMN + " bigint unsigned," + "  "
							+ MSG_TO_UID_COLUMN + " bigint unsigned NOT NULL," + "  "
							+ MSG_BODY_COLUMN + " varchar(4096) NOT NULL," + "  "
							+ " key (" + MSG_EXPIRED_COLUMN + "), "
							+ " key (" + MSG_FROM_UID_COLUMN + ", " + MSG_TO_UID_COLUMN + "),"
							+ " key (" + MSG_TO_UID_COLUMN + ", " + MSG_FROM_UID_COLUMN + "))"
							+ " ENGINE=InnoDB default character set utf8 ROW_FORMAT=DYNAMIC;";
	private static final String PGSQL_CREATE_MSG_TABLE =
							"create table " + MSG_TABLE + " ( " + "  "
							+ MSG_ID_COLUMN + " serial," + "  "
							+ MSG_TIMESTAMP_COLUMN + " TIMESTAMP DEFAULT CURRENT_TIMESTAMP," + "  "
							+ MSG_EXPIRED_COLUMN + " TIMESTAMP," + "  "
							+ MSG_FROM_UID_COLUMN + " bigint," + "  "
							+ MSG_TO_UID_COLUMN + " bigint NOT NULL," + "  "
							+ MSG_BODY_COLUMN + " varchar(4096) NOT NULL);"
							+ "create index index_" + MSG_EXPIRED_COLUMN + " on " + MSG_TABLE
							+ " (" + MSG_EXPIRED_COLUMN + ");"
							+ "create index index_" + MSG_FROM_UID_COLUMN + "_" + MSG_TO_UID_COLUMN
							+ " on " + MSG_TABLE + "(" + MSG_FROM_UID_COLUMN + "," + MSG_TO_UID_COLUMN + ");"
							+ "create index index_" + MSG_TO_UID_COLUMN + "_" + MSG_FROM_UID_COLUMN
							+ " on " + MSG_TABLE + "(" + MSG_TO_UID_COLUMN + "," + MSG_FROM_UID_COLUMN + ");";
	private static final String SQLSERVER_CREATE_MSG_TABLE =
							"create table " + MSG_TABLE + " ( " + "  "
							+ MSG_ID_COLUMN + " [bigint] IDENTITY(1,1)," + "  "
							+ MSG_TIMESTAMP_COLUMN + " [datetime] DEFAULT getdate() ," + "  "
							+ MSG_EXPIRED_COLUMN + " [datetime] ," + "  "
							+ MSG_FROM_UID_COLUMN + " bigint," + "  "
							+ MSG_TO_UID_COLUMN + " bigint NOT NULL," + "  "
							+ MSG_BODY_COLUMN + " nvarchar(4000) NOT NULL);"
							+ "create index index_" + MSG_EXPIRED_COLUMN + " on " + MSG_TABLE
							+ " (" + MSG_EXPIRED_COLUMN + ");"
							+ "create index index_" + MSG_FROM_UID_COLUMN + "_" + MSG_TO_UID_COLUMN
							+ " on " + MSG_TABLE + "(" + MSG_FROM_UID_COLUMN + "," + MSG_TO_UID_COLUMN + ");"
							+ "create index index_" + MSG_TO_UID_COLUMN + "_" + MSG_FROM_UID_COLUMN
							+ " on " + MSG_TABLE + "(" + MSG_TO_UID_COLUMN + "," + MSG_FROM_UID_COLUMN + ");";
	private static final String DERBY_CREATE_MSG_TABLE =
							"create table " + MSG_TABLE + " ( " + "  "
							+ MSG_ID_COLUMN + " bigint generated by default as identity not null," + "  "
							+ MSG_TIMESTAMP_COLUMN + " TIMESTAMP DEFAULT CURRENT_TIMESTAMP," + "  "
							+ MSG_EXPIRED_COLUMN + " TIMESTAMP," + "  "
							+ MSG_FROM_UID_COLUMN + " bigint," + "  "
							+ MSG_TO_UID_COLUMN + " bigint NOT NULL," + "  "
							+ MSG_BODY_COLUMN + " varchar(4096) NOT NULL);"
							+ "create index index_" + MSG_EXPIRED_COLUMN + " on " + MSG_TABLE
							+ " (" + MSG_EXPIRED_COLUMN + ");"
							+ "create index index_" + MSG_FROM_UID_COLUMN + "_" + MSG_TO_UID_COLUMN
							+ " on " + MSG_TABLE + "(" + MSG_FROM_UID_COLUMN + "," + MSG_TO_UID_COLUMN + ");"
							+ "create index index_" + MSG_TO_UID_COLUMN + "_" + MSG_FROM_UID_COLUMN
							+ " on " + MSG_TABLE + "(" + MSG_TO_UID_COLUMN + "," + MSG_FROM_UID_COLUMN + ");";
	private static final String MYSQL_CREATE_JID_TABLE =
							"create table " + JID_TABLE + " ( " + "  "
							+ JID_ID_COLUMN + " serial," + "  "
							+ JID_SHA_COLUMN + " char(128) NOT NULL," + "  "
							+ JID_COLUMN + " varchar(2049) NOT NULL," + "  "
							+ HISTORY_FLAG_COLUMN + " int default 0,"
							+ " primary key (" + JID_ID_COLUMN + "),"
							+ " unique key " + JID_SHA_COLUMN + " (" + JID_SHA_COLUMN + "),"
							+ " key " + JID_COLUMN + " (" + JID_COLUMN + "(255)))";
	private static final String PGSQL_CREATE_JID_TABLE =
							"create table " + JID_TABLE + " ( " + "  "
							+ JID_ID_COLUMN + " serial," + "  "
							+ JID_SHA_COLUMN + " char(128) NOT NULL," + "  "
							+ JID_COLUMN + " varchar(2049) NOT NULL," + "  "
							+ HISTORY_FLAG_COLUMN + " int default 0,"
							+ " primary key (" + JID_ID_COLUMN + ")); "
							+ "create unique index index_" + JID_SHA_COLUMN + " on " + JID_TABLE
							+ " (" + JID_SHA_COLUMN + "); "
							+ "create unique index index_" + JID_COLUMN + " on " + JID_TABLE
							+ " (" + JID_COLUMN + "); ";
	private static final String SQLSERVER_CREATE_JID_TABLE =
							"create table " + JID_TABLE + " ( " + "  "
							+ JID_ID_COLUMN + " [bigint] IDENTITY(1,1)," + "  "
							+ JID_SHA_COLUMN + " char(128) NOT NULL," + "  "
							+ JID_COLUMN + " nvarchar(2049) NOT NULL," + "  "
							+ HISTORY_FLAG_COLUMN + " int default 0,"
							+ " primary key (" + JID_ID_COLUMN + ")); "
							+ "create unique index index_" + JID_SHA_COLUMN + " on " + JID_TABLE
							+ " (" + JID_SHA_COLUMN + "); "
							+ "create unique index index_" + JID_COLUMN + " on " + JID_TABLE
							+ " (" + JID_COLUMN + "); ";
	private static final String DERBY_CREATE_JID_TABLE =
							"create table " + JID_TABLE + " ( " + "  "
							+ JID_ID_COLUMN + " bigint generated by default as identity not null," + "  "
							+ JID_SHA_COLUMN + " char(128) NOT NULL," + "  "
							+ JID_COLUMN + " varchar(2049) NOT NULL," + "  "
							+ HISTORY_FLAG_COLUMN + " int default 0,"
							+ " primary key (" + JID_ID_COLUMN + ")); "
							+ "create unique index index_" + JID_SHA_COLUMN + " on " + JID_TABLE
							+ " (" + JID_SHA_COLUMN + "); "
							+ "create unique index index_" + JID_COLUMN + " on " + JID_TABLE
							+ " (" + JID_COLUMN + "); ";
	private static final String MSG_INSERT_QUERY =
							"insert into " + MSG_TABLE + " ( "
							+ MSG_EXPIRED_COLUMN + ", "
							+ MSG_FROM_UID_COLUMN + ", "
							+ MSG_TO_UID_COLUMN + ", "
							+ MSG_BODY_COLUMN + ") values (?, ?, ?, ?)";
	private static final String MSG_SELECT_TO_JID_QUERY =
															"select * from " + MSG_TABLE + " where " + MSG_TO_UID_COLUMN + " = ?";
	private static final String MSG_DELETE_TO_JID_QUERY =
															"delete from " + MSG_TABLE + " where " + MSG_TO_UID_COLUMN + " = ?";
	private static final String MSG_DELETE_ID_QUERY =
															"delete from " + MSG_TABLE + " where " + MSG_ID_COLUMN + " = ?";
	private static final String MSG_SELECT_EXPIRED_QUERY =
															"select * from " + MSG_TABLE + " where expired is not null order by expired";
	private static final String MSG_SELECT_EXPIRED_BEFORE_QUERY =
															"select * from " + MSG_TABLE + " where expired is not null and expired <= ? order by expired";

	private static final String MYSQL_CREATE_BROADCAST_MSGS_TABLE =
							"create table broadcast_msgs ( " + "  "
							+ "id varchar(128) NOT NULL,  "
							+ "expired datetime NOT NULL,  "
							+ "msg varchar(4096) NOT NULL, "
							+ " primary key (id))";
	private static final String PGSQL_CREATE_BROADCAST_MSGS_TABLE =
							"create table broadcast_msgs ( " + "  "
							+ "id varchar(128) NOT NULL,  "
							+ "expired timestamp NOT NULL,  "
							+ "msg varchar(4096) NOT NULL, "
							+ " primary key (id))";
	private static final String SQLSERVER_CREATE_BROADCAST_MSGS_TABLE =
							"create table broadcast_msgs ( " + "  "
							+ "id varchar(128) NOT NULL,  "
							+ "expired datetime NOT NULL,  "
							+ "msg nvarchar(4000) NOT NULL, "
							+ " primary key (id))";
	private static final String DERBY_CREATE_BROADCAST_MSGS_TABLE =
							"create table broadcast_msgs ( " + "  "
							+ "id varchar(128) NOT NULL,  "
							+ "expired timestamp NOT NULL,  "
							+ "msg varchar(4096) NOT NULL, "
							+ " primary key (id))";
	
	private static final String MYSQL_CREATE_BROADCAST_MSGS_RECIPIENTS_TABLE =
							"create table broadcast_msgs_recipients ( " + "  "
							+ "msg_id varchar(128) NOT NULL,  "
							+ "jid_id bigint unsigned NOT NULL,  "
							+ " primary key (msg_id, jid_id))";
	private static final String PGSQL_CREATE_BROADCAST_MSGS_RECIPIENTS_TABLE =
							"create table broadcast_msgs_recipients ( " + "  "
							+ "msg_id varchar(128) NOT NULL,  "
							+ "jid_id bigint NOT NULL,  "
							+ " primary key (msg_id, jid_id))";
	private static final String SQLSERVER_CREATE_BROADCAST_MSGS_RECIPIENTS_TABLE =
							"create table broadcast_msgs_recipients ( " + "  "
							+ "msg_id varchar(128) NOT NULL,  "
							+ "jid_id bigint NOT NULL,  "
							+ " primary key (msg_id, jid_id))";
	private static final String DERBY_CREATE_BROADCAST_MSGS_RECIPIENTS_TABLE =
							"create table broadcast_msgs_recipients ( " + "  "
							+ "msg_id varchar(128) NOT NULL,  "
							+ "jid_id bigint NOT NULL,  "
							+ " primary key (msg_id, jid_id))";	
	
	private static final String MSG_SELECT_MESSAGES_TO_BROADCAST = 
			"select id, expired, msg from broadcast_msgs where expired >= ?";
	private static final String SQLSERVER_MSG_INSERT_MESSAGE_TO_BROADCAST =
			"insert into broadcast_msgs (id, expired, msg) values (?, ?, ?) where not exists (select 1 from broadcast_msgs where id = ?)";
	private static final String SQL_MSG_INSERT_MESSAGE_TO_BROADCAST =
			"insert into broadcast_msgs (id, expired, msg) select ?, ?, ? from (select 1) x where not exists (select 1 from broadcast_msgs where id = ?)";
	private static final String DERBY_MSG_INSERT_MESSAGE_TO_BROADCAST1 = 
			"select id from broadcast_msgs where id = ?";
	private static final String DERBY_MSG_INSERT_MESSAGE_TO_BROADCAST2 = 
			"insert into broadcast_msgs (id, expired, msg) values (?,?,?)";
	private static final String MSG_SELECT_BROADCAST_RECIPIENTS = 
			"select j." + JID_COLUMN + " from broadcast_msgs_recipients r join " + JID_TABLE + " j on j." + JID_ID_COLUMN + " = r.jid_id where r.msg_id = ?";
	private static final String SQLSERVER_MSG_ENSURE_BROADCAT_RECIPIETN = 
			"insert into broadcast_msgs_recipients (msg_id, jid_id) values (?, ?) where not exists (select 1 from broadcast_msgs_recipients where msg_id = ? and jid_id = ?)";
	private static final String SQL_MSG_ENSURE_BROADCAT_RECIPIETN = 
			"insert into broadcast_msgs_recipients (msg_id, jid_id) select ?, ? from (select 1) x where not exists (select 1 from broadcast_msgs_recipients where msg_id = ? and jid_id = ?)";
	private static final String DERBY_MSG_ENSURE_BROADCAT_RECIPIETN1 = 
			"select 1 from broadcast_msgs_recipients where msg_id = ? and jid_id = ?";
	private static final String DERBY_MSG_ENSURE_BROADCAT_RECIPIETN2 = 
			"insert into broadcast_msgs_recipients (msg_id, jid_id) values (?, ?)";
	
	private static final String GET_USER_UID_DEF_QUERY =
		"select " + 
		  JID_ID_COLUMN + ", " + 
		  JID_COLUMN + 
		" from " + JID_TABLE + " where " + JID_SHA_COLUMN + " = ?";
        private static final String MSG_COUNT_FOR_TO_AND_FROM_QUERY_DEF =
                "select count(*) from " + MSG_TABLE + " where " + MSG_TO_UID_COLUMN + " = ? and " + MSG_FROM_UID_COLUMN + " = ?";
	private static final String ADD_USER_JID_ID_QUERY = 
		"insert into " + JID_TABLE + " ( " + JID_SHA_COLUMN + ", " + JID_COLUMN + ") values (?, ?)";
	/* @formatter:on */
	private static final String GET_USER_UID_PROP_KEY = "user-uid-query";
	private static final String MSGS_COUNT_LIMIT_PROP_KEY = "count-limit-query";
	private static final int MAX_UID_CACHE_SIZE = 100000;
	private static final long MAX_UID_CACHE_TIME = 3600000;
//	private static final Map<String, JDBCMsgRepository> repos =
//			new ConcurrentSkipListMap<String, JDBCMsgRepository>();

	// ~--- fields ---------------------------------------------------------------

	private DataRepository data_repo = null;
	private String uid_query = GET_USER_UID_DEF_QUERY;
	private String msg_count_for_limit_query = MSG_COUNT_FOR_TO_AND_FROM_QUERY_DEF;
	private String msg_insert_message_to_broadcast = SQL_MSG_INSERT_MESSAGE_TO_BROADCAST;
	private String msg_ensure_broadcast_recipient = SQL_MSG_ENSURE_BROADCAT_RECIPIETN;
	private long msgs_store_limit = MSGS_STORE_LIMIT_VAL;
	private boolean initialized = false;
	private Map<BareJID, Long> uids_cache = Collections
			.synchronizedMap(new SimpleCache<BareJID, Long>(MAX_UID_CACHE_SIZE,
					MAX_UID_CACHE_TIME));

	// ~--- get methods ----------------------------------------------------------

	// Moved to MsgRepository class
//	public static JDBCMsgRepository getInstance(String id_string) {
//	}

	// ~--- methods --------------------------------------------------------------

	@Override
	public void initRepository(String conn_str, Map<String, String> map)
			throws DBInitException {
		if (initialized) {
			return;
		}

		initialized = true;
		log.log(Level.INFO, "Initializing dbAccess for db connection url: {0}", conn_str);

		if (map != null) {
			String query = map.get(GET_USER_UID_PROP_KEY);

			if (query != null) {
				uid_query = query;
			}

			query = map.get(JDBCMsgRepository.MSGS_COUNT_LIMIT_PROP_KEY);

			if (query != null) {
				msg_count_for_limit_query = query;
			}

			String msgs_store_limit_str = map.get(MSGS_STORE_LIMIT_KEY);

			if (msgs_store_limit_str != null) {
				msgs_store_limit = Long.parseLong(msgs_store_limit_str);
			}
		}

		try {
			data_repo = RepositoryFactory.getDataRepository(null, conn_str, map);
			switch (data_repo.getDatabaseType()) {
				case sqlserver:
					msg_ensure_broadcast_recipient = SQLSERVER_MSG_ENSURE_BROADCAT_RECIPIETN;
					msg_insert_message_to_broadcast = SQLSERVER_MSG_INSERT_MESSAGE_TO_BROADCAST;
					break;
				default:
					msg_ensure_broadcast_recipient = SQL_MSG_ENSURE_BROADCAT_RECIPIETN;
					msg_insert_message_to_broadcast = SQL_MSG_INSERT_MESSAGE_TO_BROADCAST;
					break;
			}

			// Check if DB is correctly setup and contains all required tables.
			checkDB();
			data_repo.initPreparedStatement(uid_query, uid_query);
			data_repo.initPreparedStatement(MSG_INSERT_QUERY, MSG_INSERT_QUERY);
			data_repo.initPreparedStatement(MSG_SELECT_TO_JID_QUERY, MSG_SELECT_TO_JID_QUERY);
			data_repo.initPreparedStatement(MSG_DELETE_TO_JID_QUERY, MSG_DELETE_TO_JID_QUERY);
			data_repo.initPreparedStatement(MSG_DELETE_ID_QUERY, MSG_DELETE_ID_QUERY);
			data_repo.initPreparedStatement(MSG_SELECT_EXPIRED_QUERY, MSG_SELECT_EXPIRED_QUERY);
			data_repo.initPreparedStatement(MSG_SELECT_EXPIRED_BEFORE_QUERY,
					MSG_SELECT_EXPIRED_BEFORE_QUERY);
			data_repo.initPreparedStatement(msg_count_for_limit_query,
					msg_count_for_limit_query);
			data_repo.initPreparedStatement(ADD_USER_JID_ID_QUERY, ADD_USER_JID_ID_QUERY);
			data_repo.initPreparedStatement(MSG_SELECT_BROADCAST_RECIPIENTS, MSG_SELECT_BROADCAST_RECIPIENTS);
			data_repo.initPreparedStatement(MSG_SELECT_MESSAGES_TO_BROADCAST, MSG_SELECT_MESSAGES_TO_BROADCAST);
			if (data_repo.getDatabaseType() == dbTypes.derby) {
				data_repo.initPreparedStatement(DERBY_MSG_ENSURE_BROADCAT_RECIPIETN1, DERBY_MSG_ENSURE_BROADCAT_RECIPIETN1);
				data_repo.initPreparedStatement(DERBY_MSG_ENSURE_BROADCAT_RECIPIETN2, DERBY_MSG_ENSURE_BROADCAT_RECIPIETN2);
				data_repo.initPreparedStatement(DERBY_MSG_INSERT_MESSAGE_TO_BROADCAST1, DERBY_MSG_INSERT_MESSAGE_TO_BROADCAST1);
				data_repo.initPreparedStatement(DERBY_MSG_INSERT_MESSAGE_TO_BROADCAST2, DERBY_MSG_INSERT_MESSAGE_TO_BROADCAST2);
			} else {
				data_repo.initPreparedStatement(msg_ensure_broadcast_recipient, msg_ensure_broadcast_recipient);
				data_repo.initPreparedStatement(msg_insert_message_to_broadcast, msg_insert_message_to_broadcast);
			}
		} catch (Exception e) {
			log.log(Level.WARNING, "MsgRepository not initialized due to exception", e);
			// Ignore for now....
		}
	}

	@Override
	public Queue<Element> loadMessagesToJID(JID to, boolean delete)
			throws UserNotFoundException {
		Queue<Element> result = null;
		ResultSet rs = null;

		try {
			long to_uid = getUserUID(to.getBareJID());

			if (to_uid < 0) {
				throw new UserNotFoundException("User: " + to + " was not found in database.");
			}

			PreparedStatement select_to_jid_st =
					data_repo.getPreparedStatement(to.getBareJID(), MSG_SELECT_TO_JID_QUERY);

			synchronized (select_to_jid_st) {
				select_to_jid_st.setLong(1, to_uid);
				rs = select_to_jid_st.executeQuery();

				StringBuilder sb = new StringBuilder(1000);

				while (rs.next()) {
					sb.append(rs.getString(MSG_BODY_COLUMN));
				}

				if (sb.length() > 0) {
					DomBuilderHandler domHandler = new DomBuilderHandler();

					parser.parse(domHandler, sb.toString().toCharArray(), 0, sb.length());
					result = domHandler.getParsedElements();
				}
			}

			if (delete) {
				PreparedStatement delete_to_jid_st =
						data_repo.getPreparedStatement(to.getBareJID(), MSG_DELETE_TO_JID_QUERY);

				synchronized (delete_to_jid_st) {
					delete_to_jid_st.setLong(1, to_uid);
					delete_to_jid_st.executeUpdate();
				}
			}
		} catch (SQLException e) {
			log.log(Level.WARNING, "Problem getting offline messages for user: " + to, e);
		} finally {
			data_repo.release(null, rs);
		}

		return result;
	}

	@Override
	public void storeMessage(JID from, JID to, Date expired, Element msg)
			throws UserNotFoundException {
		if (log.isLoggable(Level.FINEST)) {
			log.log(Level.FINEST, "Storring expired: {0} message: {1}", new Object[] { expired,
					Packet.elemToString(msg) });
		}

		ResultSet rs = null;
		try {
			long from_uid = getUserUID(from.getBareJID());

			if (from_uid < 0) {
				from_uid = addUserJID(from.getBareJID());
			}

			long to_uid = getUserUID(to.getBareJID());

			if (to_uid < 0) {
				to_uid = addUserJID(to.getBareJID());
			}

			long count = 0;
			PreparedStatement count_msgs_st =
					data_repo.getPreparedStatement(to.getBareJID(), msg_count_for_limit_query);

			synchronized (count_msgs_st) {
				count_msgs_st.setLong(1, to_uid);
				count_msgs_st.setLong(2, from_uid);

				rs = count_msgs_st.executeQuery();

				if (rs.next()) {
					count = rs.getLong(1);
				}
			}

			if (msgs_store_limit <= count) {
				if (log.isLoggable(Level.FINEST)) {
					log.log(Level.FINEST, "Message store limit ({0}) exceeded for message: {1}",
							new Object[] { msgs_store_limit, Packet.elemToString(msg) });
				}
				return;
			}

			PreparedStatement insert_msg_st =
					data_repo.getPreparedStatement(to.getBareJID(), MSG_INSERT_QUERY);

			synchronized (insert_msg_st) {
				if (expired == null) {
					insert_msg_st.setNull(1, Types.TIMESTAMP);
				} else {
					Timestamp time = new Timestamp(expired.getTime());

					insert_msg_st.setTimestamp(1, time);
				}

				if (from_uid <= 0) {
					insert_msg_st.setNull(2, Types.BIGINT);
				} else {
					insert_msg_st.setLong(2, from_uid);
				}

				insert_msg_st.setLong(3, to_uid);
				// TODO: deal with messages bigger than the database can fit....
				insert_msg_st.setString(4, msg.toString());
				insert_msg_st.executeUpdate();
			}

			if (expired != null) {
				if (expired.getTime() < earliestOffline) {
					earliestOffline = expired.getTime();
				}

				if (expiredQueue.size() == 0) {
					loadExpiredQueue(1);
				}
			}
		} catch (DataTruncation dte) {
			log.log(Level.FINE, "Data truncated for message from {0} to {1}", new Object[] {
					from, to });

			data_repo.release(null, rs);
		} catch (SQLException e) {
			log.log(Level.WARNING, "Problem adding new entry to DB: ", e);
		}
	}
	
	@Override
	public void loadMessagesToBroadcast() {
		ResultSet rs = null;
		try {
			Set<String> oldMessages = new HashSet<String>(broadcastMessages.keySet());

			PreparedStatement stmt = data_repo.getPreparedStatement(null, MSG_SELECT_MESSAGES_TO_BROADCAST);

			synchronized (stmt) {
				stmt.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
				rs = stmt.executeQuery();
				
				DomBuilderHandler domHandler = new DomBuilderHandler();
				while (rs.next()) {
					String msgId = rs.getString(1);
					oldMessages.remove(msgId);
					if (broadcastMessages.containsKey(msgId))
						continue;
					
					Date expire = rs.getTimestamp(2);
					char[] msgChars = rs.getString(3).toCharArray();
					
					parser.parse(domHandler, msgChars, 0, msgChars.length);
					
					Queue<Element> elems = domHandler.getParsedElements();
					Element msg = elems.poll();
					if (msg == null)
						continue;
					
					broadcastMessages.put(msgId, new BroadcastMsg(null, msg, expire));
				}
			}
			
			for (String id : oldMessages) {
				broadcastMessages.remove(id);
			}
			
			data_repo.release(null, rs);
			rs = null;
			
			for (String id : broadcastMessages.keySet()) {
				BroadcastMsg bmsg = broadcastMessages.get(id);
				stmt = data_repo.getPreparedStatement(null, MSG_SELECT_BROADCAST_RECIPIENTS);
				synchronized (stmt) {
					stmt.setString(1, id);
					rs = stmt.executeQuery();
					while (rs.next()) {
						BareJID jid = BareJID.bareJIDInstanceNS(rs.getString(1));
						bmsg.addRecipient(jid);
					}
				}
				data_repo.release(null, rs);
			}
		} catch (SQLException ex) {
			log.log(Level.WARNING, "Problem with retrieving broadcast messages", ex);
		} finally {
			data_repo.release(null, rs);
		}
	}
	
	@Override
	protected void insertBroadcastMessage(String id, Element msg, Date expire, BareJID recipient) {
		try {
			if (data_repo.getDatabaseType() == dbTypes.derby) {
				boolean exists = false;
				PreparedStatement stmt = data_repo.getPreparedStatement(recipient, DERBY_MSG_INSERT_MESSAGE_TO_BROADCAST1);
				synchronized (stmt) {
					stmt.setString(1, id);
					ResultSet rs = stmt.executeQuery();
					exists = rs.next();
					data_repo.release(null, rs);
				}
				if (!exists) {
					stmt = data_repo.getPreparedStatement(recipient, DERBY_MSG_INSERT_MESSAGE_TO_BROADCAST2);
					synchronized (stmt) {
						stmt.setString(1, id);
						stmt.setTimestamp(2, new Timestamp(expire.getTime()));
						stmt.setString(3, msg.toString());
						stmt.executeUpdate();
					}					
				}
			} else {			
				PreparedStatement stmt = data_repo.getPreparedStatement(recipient, msg_insert_message_to_broadcast);
				synchronized (stmt) {
					stmt.setString(1, id);
					stmt.setTimestamp(2, new Timestamp(expire.getTime()));
					stmt.setString(3, msg.toString());
					stmt.setString(4, id);
					stmt.executeUpdate();
				}
			}
		} catch (Exception ex) {
			log.log(Level.WARNING, "Problem with updating broadcast message", ex);
		}
	}
	
	@Override
	protected void ensureBroadcastMessageRecipient(String id, BareJID recipient) {
		try {
			long uid = getUserUID(recipient);
			if (uid == -1) {
				uid = addUserJID(recipient);
			}
			
			if (data_repo.getDatabaseType() == dbTypes.derby) {
				boolean exists = false;
				PreparedStatement stmt = data_repo.getPreparedStatement(recipient, DERBY_MSG_ENSURE_BROADCAT_RECIPIETN1);
				synchronized (stmt) {
					stmt.setString(1, id);
					stmt.setLong(2, uid);
					ResultSet rs = stmt.executeQuery();
					exists = rs.next();
					data_repo.release(null, rs);
				}
				if (!exists) {
					stmt = data_repo.getPreparedStatement(recipient, DERBY_MSG_ENSURE_BROADCAT_RECIPIETN2);
					synchronized (stmt) {
						stmt.setString(1, id);
						stmt.setLong(2, uid);
						stmt.executeUpdate();
					}					
				}
			} else {
				PreparedStatement stmt = data_repo.getPreparedStatement(recipient, msg_ensure_broadcast_recipient);
				synchronized (stmt) {
					stmt.setString(1, id);
					stmt.setLong(2, uid);
					stmt.setString(3, id);
					stmt.setLong(4, uid);
					stmt.executeUpdate();
				}
			}
		} catch (Exception ex) {
			log.log(Level.WARNING, "Problem with updating broadcast message", ex);
		}
	}

	private long addUserJID(BareJID bareJID) throws SQLException, UserNotFoundException {
		try {
			String jid_sha = Algorithms.hexDigest(bareJID.toString(), "", "SHA");
			PreparedStatement add_jid_id_st =
					data_repo.getPreparedStatement(bareJID, ADD_USER_JID_ID_QUERY);

			synchronized (add_jid_id_st) {
				add_jid_id_st.setString(1, jid_sha);
				add_jid_id_st.setString(2, bareJID.toString());
				add_jid_id_st.executeUpdate();
			}

		} catch (NoSuchAlgorithmException ex) {
			log.log(Level.WARNING, "Configuration error or code bug: ", ex);

			return -1;
		}

		return getUserUID(bareJID);
	}

	/**
	 * Performs database check, creates missing schema if necessary
	 *
	 * @throws SQLException
	 */
	private void checkDB() throws SQLException {
		DataRepository.dbTypes databaseType = data_repo.getDatabaseType();
		switch ( databaseType ) {
			case mysql:
				data_repo.checkTable( JID_TABLE, MYSQL_CREATE_JID_TABLE );
				data_repo.checkTable( MSG_TABLE, MYSQL_CREATE_MSG_TABLE );
				
				data_repo.checkTable( "broadcast_msgs", MYSQL_CREATE_BROADCAST_MSGS_TABLE);
				data_repo.checkTable( "broadcast_msgs_recipients", MYSQL_CREATE_BROADCAST_MSGS_RECIPIENTS_TABLE);
				break;
			case postgresql:
				data_repo.checkTable( JID_TABLE, PGSQL_CREATE_JID_TABLE );
				data_repo.checkTable( MSG_TABLE, PGSQL_CREATE_MSG_TABLE );

				data_repo.checkTable( "broadcast_msgs", PGSQL_CREATE_BROADCAST_MSGS_TABLE);
				data_repo.checkTable( "broadcast_msgs_recipients", PGSQL_CREATE_BROADCAST_MSGS_RECIPIENTS_TABLE);
				break;
			case derby:
				data_repo.checkTable( JID_TABLE, DERBY_CREATE_JID_TABLE );
				data_repo.checkTable( MSG_TABLE, DERBY_CREATE_MSG_TABLE );

				data_repo.checkTable( "broadcast_msgs", DERBY_CREATE_BROADCAST_MSGS_TABLE);
				data_repo.checkTable( "broadcast_msgs_recipients", DERBY_CREATE_BROADCAST_MSGS_RECIPIENTS_TABLE);
				break;
			case jtds:
			case sqlserver:
				data_repo.checkTable( JID_TABLE, SQLSERVER_CREATE_JID_TABLE );
				data_repo.checkTable( MSG_TABLE, SQLSERVER_CREATE_MSG_TABLE );

				data_repo.checkTable( "broadcast_msgs", SQLSERVER_CREATE_BROADCAST_MSGS_TABLE);
				data_repo.checkTable( "broadcast_msgs_recipients", SQLSERVER_CREATE_BROADCAST_MSGS_RECIPIENTS_TABLE);
				break;
		}
	}

	@Override
	protected void deleteMessage(Long msg_id) {
		try {
			PreparedStatement delete_id_st =
					data_repo.getPreparedStatement(null, MSG_DELETE_ID_QUERY);

			synchronized (delete_id_st) {
				delete_id_st.setLong(1, msg_id);
				delete_id_st.executeUpdate();
			}
		} catch (SQLException e) {
			log.log(Level.WARNING, "Problem removing entry from DB: ", e);
		}
	}

	// ~--- get methods ----------------------------------------------------------

	private long getUserUID(BareJID user_id) throws SQLException, UserNotFoundException {
		Long cache_res = uids_cache.get(user_id);

		if (cache_res != null) {
			return cache_res.longValue();
		} // end of if (result != null)

		ResultSet rs = null;
		long result = -1;
		String jid_sha;

		try {
			jid_sha = Algorithms.hexDigest(user_id.toString(), "", "SHA");
		} catch (NoSuchAlgorithmException ex) {
			log.log(Level.WARNING, "Configuration error or code bug: ", ex);

			return -1;
		}

		try {
			PreparedStatement uid_st = data_repo.getPreparedStatement(user_id, uid_query);

			synchronized (uid_st) {
				uid_st.setString(1, jid_sha);
				rs = uid_st.executeQuery();

				if (rs.next()) {
					BareJID res_jid = BareJID.bareJIDInstanceNS(rs.getString(JID_COLUMN));

					if (log.isLoggable(Level.FINEST)) {
						log.log(Level.FINEST, "Found entry for JID: {0}, DB JID: {1}", new Object[] {
								user_id, res_jid });
					}

					// There is a slight chance that there is the same SHA for 2 different
					// JIDs.
					// Even though it is impossible to store messages for both JIDs right
					// now
					// we have to make sure we don't send offline messages to incorrect
					// person
					if (user_id.equals(res_jid)) {
						result = rs.getLong(JID_ID_COLUMN);
					} else {
						if (log.isLoggable(Level.FINEST)) {
							log.log(Level.FINEST,
									"JIDs don't match, SHA conflict? JID: {0}, DB JID: {1}", new Object[] {
											user_id, res_jid });
						}
					}
				} else {
					if (log.isLoggable(Level.FINEST)) {
						log.log(Level.FINEST, "No entry for JID: {0}", user_id);
					}
				}
			}

			// if (result <= 0) {
			// throw new UserNotFoundException("User does not exist: " + user_id);
			// } // end of if (isnext) else
		} finally {
			data_repo.release(null, rs);
		}

		if (result > 0) {
			uids_cache.put(user_id, result);
		}

		return result;
	}

	// ~--- methods --------------------------------------------------------------

	@Override
	protected void loadExpiredQueue(int min_elements) {
		ResultSet rs = null;

		try {
			PreparedStatement select_expired_st =
					data_repo.getPreparedStatement(null, MSG_SELECT_EXPIRED_QUERY);

			synchronized (select_expired_st) {
				rs = select_expired_st.executeQuery();

				DomBuilderHandler domHandler = new DomBuilderHandler();
				int counter = 0;

				while (rs.next()
						&& ((expiredQueue.size() < MAX_QUEUE_SIZE) || (counter++ < min_elements))) {
					String msg_str = rs.getString(MSG_BODY_COLUMN);

					parser.parse(domHandler, msg_str.toCharArray(), 0, msg_str.length());

					Queue<Element> elems = domHandler.getParsedElements();
					Element msg = elems.poll();

					if (msg == null) {
						log.log(Level.INFO,
								"Something wrong, loaded offline message from DB but parsed no "
										+ "XML elements: {0}", msg_str);
					} else {
						Timestamp ts = rs.getTimestamp(MSG_EXPIRED_COLUMN);
						MsgDBItem item = new MsgDBItem(rs.getLong(MSG_ID_COLUMN), msg, ts);

						expiredQueue.offer(item);
					}
				}
			}
		} catch (SQLException e) {
			log.log(Level.WARNING, "Problem getting offline messages from db: ", e);
		} finally {
			data_repo.release(null, rs);
		}

		earliestOffline = Long.MAX_VALUE;
	}

	@Override
	protected void loadExpiredQueue(Date expired) {
		ResultSet rs = null;

		try {
			if (expiredQueue.size() > 100 * MAX_QUEUE_SIZE) {
				expiredQueue.clear();
			}

			PreparedStatement select_expired_before_st =
					data_repo.getPreparedStatement(null, MSG_SELECT_EXPIRED_BEFORE_QUERY);

			synchronized (select_expired_before_st) {
				select_expired_before_st.setTimestamp(1, new Timestamp(expired.getTime()));
				rs = select_expired_before_st.executeQuery();

				DomBuilderHandler domHandler = new DomBuilderHandler();
				int counter = 0;

				while (rs.next() && (counter++ < MAX_QUEUE_SIZE)) {
					String msg_str = rs.getString(MSG_BODY_COLUMN);

					parser.parse(domHandler, msg_str.toCharArray(), 0, msg_str.length());

					Queue<Element> elems = domHandler.getParsedElements();
					Element msg = elems.poll();

					if (msg == null) {
						log.log(Level.INFO,
								"Something wrong, loaded offline message from DB but parsed no "
										+ "XML elements: {0}", msg_str);
					} else {
						Timestamp ts = rs.getTimestamp(MSG_EXPIRED_COLUMN);
						MsgDBItem item = new MsgDBItem(rs.getLong(MSG_ID_COLUMN), msg, ts);

						expiredQueue.offer(item);
					}
				}
			}
		} catch (SQLException e) {
			log.log(Level.WARNING, "Problem getting offline messages from db: ", e);
		} finally {
			data_repo.release(null, rs);
		}

		earliestOffline = Long.MAX_VALUE;
	}

}

// ~ Formatted in Sun Code Convention

// ~ Formatted by Jindent --- http://www.jindent.com
