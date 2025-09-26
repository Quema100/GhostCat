package me.duckmain.ghostcat.network;

import me.duckmain.ghostcat.tls.SSLUtil;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.*;
import java.util.function.Consumer;

public class ChatClient {
    private BufferedReader in;
    private BufferedWriter out;
    private SSLSocket socket;
    private final Consumer<String> onLine;
    private final String nick;
    private volatile boolean running = true;

    public ChatClient(String nick, Consumer<String> onLine) {
        this.nick = nick;
        this.onLine = onLine;
    }

    // AUTO discovery -> uses trustAllFactory for the connection it establishes
    public void autoDiscoverAndConnect(int defaultPort) throws Exception {
        // create socket factory that trusts all certs for this client (POC)
        SSLSocketFactory trustFactory = SSLUtil.trustAllFactory();

        try (DatagramSocket listen = new DatagramSocket(9999)) {
            listen.setSoTimeout(2500);
            byte[] buf = new byte[512];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                listen.receive(p);
                String s = new String(p.getData(), 0, p.getLength());
                if (s.startsWith("E2EE-SERVER:")) {
                    String[] sp = s.split(":");
                    // sp[1]=ip, sp[2]=port
                    connectToTLSWithFactory(trustFactory, sp[1], Integer.parseInt(sp[2]));
                    return;
                }
            } catch (SocketTimeoutException ignored) {
                // timed out -> fallback to localhost
            }
        }

        connectToTLSWithFactory(trustFactory, "127.0.0.1", defaultPort);
    }

    // Public connect that uses default factory (kept for compatibility) - but we will prefer explicit factory
    public void connectToTLS(String host, int port) throws Exception {
        SSLSocketFactory trustFactory = SSLUtil.trustAllFactory();
        connectToTLSWithFactory(trustFactory, host, port);
    }

    // Core connect using a provided SSLSocketFactory (ensures we actually use trustAllFactory)
    public void connectToTLSWithFactory(SSLSocketFactory ssf, String host, int port) throws Exception {
        // create socket with the provided factory (so trust-all is honored)
        socket = (SSLSocket) ssf.createSocket();
        socket.connect(new InetSocketAddress(host, port), 4000);

        /*
         * Important: startHandshake() blocks until handshake finishes (or fails).
         * Only after successful handshake we open streams and start reader.
         */
        socket.startHandshake();

        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

        // Start reader thread after streams exist and handshake is done
        new Thread(this::readerLoop, "ChatClient-Reader-" + nick).start();
    }

    private void readerLoop() {
        try {
            String line;
            while (running && socket != null && !socket.isClosed() && (line = in.readLine()) != null) {
                onLine.accept(line);
            }
        } catch (IOException e) {
            onLine.accept("Client socket error: " + e.getMessage());
        } finally {
            closeConnection();
        }
    }

    public void sendRegister(String pubB64) { sendLine("REGISTER|" + nick + "|" + pubB64); }
    public void sendKeyExchange(String pubB64, String to) { sendLine("KEY|" + nick + "|" + to + "|" + pubB64); }
    public void sendMessageToPeer(String to, String payload) { sendLine("MSG|" + nick + "|" + to + "|" + payload); }

    private synchronized void sendLine(String line) {
        try {
            if (out != null) {
                out.write(line);
                out.newLine();
                out.flush();
            } else {
                onLine.accept("send fail: output stream not ready");
            }
        } catch (IOException e) {
            onLine.accept("send fail: " + e.getMessage());
        }
    }

    public void closeConnection() {
        running = false;
        try { if (in != null) in.close(); } catch (IOException ignored) {}
        try { if (out != null) out.close(); } catch (IOException ignored) {}
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException ignored) {}
    }
}
