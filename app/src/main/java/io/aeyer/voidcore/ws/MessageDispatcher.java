package io.aeyer.voidcore.ws;

import org.springframework.web.socket.WebSocketSession;

/**
 * Seam between the transport layer (this package) and the typed protocol
 * vocabulary that lands with #14 (sealed ClientMessage / ServerMessage).
 *
 * <p>Implementations decode the envelope's payload into a typed variant and
 * route to the correct handler. The transport layer does not know about
 * application messages — it only knows about envelopes.
 */
public interface MessageDispatcher {

    /**
     * Handle one inbound envelope. Implementations must not throw; protocol
     * errors should be reported back via {@link VoidCoreSession#sendError}.
     */
    void dispatch(Envelope inbound, VoidCoreSession session);
}
