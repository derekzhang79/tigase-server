/*
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2008 "Artur Hefczyc" <artur.hefczyc@tigase.org>
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 * 
 * $Rev$
 * Last modified by $Author$
 * $Date$
 */

package tigase.server.ext.handlers;

import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.logging.Logger;
import tigase.server.Packet;
import tigase.server.ext.ComponentConnection;
import tigase.server.ext.ComponentProtocolHandler;
import tigase.server.ext.ExtProcessor;
import tigase.server.ext.StreamOpenHandler;
import tigase.xml.Element;
import tigase.xmpp.XMPPIOService;

/**
 * Created: Oct 31, 2009 4:54:39 PM
 *
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
public class StartTLSProcessor implements ExtProcessor {

  /**
   * Variable <code>log</code> is a class logger.
   */
  private static final Logger log =
    Logger.getLogger(StartTLSProcessor.class.getName());

	private static final String EL_NAME = "starttls";
	private static final String ID = EL_NAME;
  private static final Element FEATURES =
			new Element(EL_NAME,
			new Element[]{new Element("required")},
			new String[]{"xmlns"}, new String[]{"urn:ietf:params:xml:ns:xmpp-tls"});

	@Override
	public String getId() {
		return ID;
	}

	private void initTLS(XMPPIOService<ComponentConnection> serv, String data,
			boolean client) {
		try {
			serv.writeRawData(data);
			Thread.sleep(10);
			while (serv.waitingToSend()) {
				serv.writeRawData(null);
				Thread.sleep(10);
			}
			serv.startTLS(client);
		} catch (Exception e) {

		}
	}

	@Override
	public boolean process(Packet p, XMPPIOService<ComponentConnection> serv,
			ComponentProtocolHandler handler,
			Queue<Packet> results) {
		if (p.getElemName() == EL_NAME) {
			serv.getSessionData().put(ID, ID);
			String data = "<proceed xmlns='urn:ietf:params:xml:ns:xmpp-tls'/>";
			initTLS(serv, data, false);
			log.fine("Started server side TLS.");
			return true;
		}
		if (p.getElemName() == "proceed") {
			serv.getSessionData().put(ID, ID);
			initTLS(serv, null, true);
			log.fine("Started client side TLS.");
			StreamOpenHandler soh = handler.getStreamOpenHandler("jabber:client");
			String data = soh.serviceStarted(serv);
			serv.xmppStreamOpen(data);
			log.fine("New stream opened: " + data);
			return true;
		}
		return false;
	}

	@Override
	public void startProcessing(Packet p, XMPPIOService<ComponentConnection> serv,
			ComponentProtocolHandler handler,	Queue<Packet> results) {
			results.offer(new Packet(new Element(EL_NAME,
					new String[] {"xmlns"},
					new String[] {"urn:ietf:params:xml:ns:xmpp-tls"})));
	}

	@Override
	public List<Element> getStreamFeatures(XMPPIOService<ComponentConnection> serv,
			ComponentProtocolHandler handler) {
		if (serv.getSessionData().get(ID) != null) {
			return null;
		} else {
			return Arrays.asList(FEATURES);
		}
	}

}