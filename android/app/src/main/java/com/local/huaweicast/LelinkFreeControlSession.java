package com.local.huaweicast;

import java.io.IOException;
import java.util.Arrays;

/** A verified FREE native control connection; this does not start or authorize media. */
public final class LelinkFreeControlSession implements AutoCloseable {
    interface Connector {
        LelinkControlClient connect(LelinkEndpoint endpoint, LelinkControlClient.SocketBinder binder)
                throws IOException;
    }

    private final LelinkControlClient control;
    private final LelinkPlayerInfo playerInfo;
    private final byte[] mediaSeed;
    private boolean closed;

    private LelinkFreeControlSession(LelinkControlClient control, LelinkPlayerInfo playerInfo,
                                     byte[] mediaSeed) {
        this.control = control;
        this.playerInfo = playerInfo;
        this.mediaSeed = mediaSeed.clone();
    }

    /** The binder may retain the socket to cancel a connection or an in-flight handshake. */
    public static LelinkFreeControlSession connect(LelinkEndpoint endpoint,
                                                   LelinkControlClient.SocketBinder binder)
            throws IOException {
        return connect(endpoint, binder, LelinkControlClient::connect);
    }

    static LelinkFreeControlSession connect(LelinkEndpoint endpoint,
                                            LelinkControlClient.SocketBinder binder,
                                            Connector connector) throws IOException {
        if (endpoint == null || binder == null || connector == null) {
            throw new IllegalArgumentException("Receiver, socket binder, and connector are required");
        }
        if (!endpoint.advertisesFreeNativePairing()) {
            throw new IOException("Receiver does not advertise FREE native pairing (htv=1, atv=0)");
        }

        LelinkControlClient client = null;
        byte[] outbound = null;
        byte[] reply = null;
        byte[] mediaSeed = null;
        boolean transferred = false;
        try (LelinkFreePairing pairing = new LelinkFreePairing()) {
            client = connector.connect(endpoint, binder);
            LelinkPlayerInfo info = LelinkPlayerInfo.parse(accepted("player-info", client.readPlayerInfo()));
            if (!info.advertisesFreePairing()) {
                throw new IOException("Receiver no longer offers FREE native pairing (htv=1, atv=0)");
            }

            outbound = pairing.initialRequest();
            reply = accepted("M2 setup", client.setup(outbound));
            wipe(outbound);
            outbound = pairing.acceptSetupReturnVerify(reply);
            wipe(reply);
            reply = accepted("M4 verify", client.verify(outbound));
            wipe(outbound);
            outbound = pairing.acceptVerifyReturnFinish(reply);
            wipe(reply);
            reply = accepted("M6 finish", client.verify(outbound));
            // M6 is still plaintext. Only its authenticated nonce confirmation enables records.
            try (LelinkFreePairing.ControlSecrets secrets = pairing.acceptFinish(reply)) {
                byte[] key = secrets.key();
                byte[] nonce = secrets.nonce();
                try {
                    client.enableEncryption(key, nonce);
                    mediaSeed = secrets.mediaSeed();
                } finally {
                    wipe(key);
                    wipe(nonce);
                }
            }
            LelinkFreeControlSession result = new LelinkFreeControlSession(client, info, mediaSeed);
            transferred = true;
            return result;
        } catch (LelinkFreePairing.PairingException error) {
            throw new IOException("FREE native pairing verification failed", error);
        } finally {
            wipe(outbound);
            wipe(reply);
            wipe(mediaSeed);
            if (!transferred && client != null) client.close();
        }
    }

    private static byte[] accepted(String stage, LelinkControlClient.Response response)
            throws ReceiverRejectedException {
        if (response.status() != 200) throw new ReceiverRejectedException(stage, response.status());
        return response.body();
    }

    public LelinkPlayerInfo playerInfo() { return playerInfo; }

    /** Caller owns and must erase this copy after the subsequent media negotiation. */
    public synchronized byte[] mediaSeed() {
        requireOpen();
        return mediaSeed.clone();
    }

    synchronized LelinkControlClient control() {
        requireOpen();
        return control;
    }

    public synchronized boolean isClosed() { return closed || control.isClosed(); }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        wipe(mediaSeed);
        control.close();
    }

    private void requireOpen() {
        if (closed || control.isClosed()) throw new IllegalStateException("FREE control session is closed");
    }

    private static void wipe(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }

    /** Includes proprietary statuses such as 600 without interpreting them as success. */
    public static final class ReceiverRejectedException extends IOException {
        private final String stage;
        private final int statusCode;

        private ReceiverRejectedException(String stage, int statusCode) {
            super("Receiver rejected FREE native " + stage + " (status " + statusCode + ")");
            this.stage = stage;
            this.statusCode = statusCode;
        }

        public String stage() { return stage; }
        public int statusCode() { return statusCode; }
    }
}
