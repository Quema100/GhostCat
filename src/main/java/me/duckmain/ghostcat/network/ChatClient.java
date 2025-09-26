package me.duckmain.ghostcat.network;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.*;
import java.net.*;
import java.util.function.Consumer;

public class ChatClient {
    private BufferedReader in;
    private BufferedWriter out;
    private final Consumer<String> onLine;
    private final String nick;
    private volatile boolean running = true;

    public ChatClient(String nick, Consumer<String> onLine) {
        this.nick = nick;
        this.onLine = onLine;
    }

    public void autoDiscoverAndConnect(int defaultPort) {
        try (DatagramSocket listen = new DatagramSocket(9999)) {
            listen.setSoTimeout(2500);
            byte[] buf = new byte[512];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                listen.receive(p);
                String s = new String(p.getData(), 0, p.getLength());
                if (s.startsWith("E2EE-SERVER:")) {
                    String[] sp = s.split(":");
                    connectToTLS(sp[1], Integer.parseInt(sp[2]));
                    return;
                }
            } catch (SocketTimeoutException ignored) {}
        } catch (IOException ignored) {}

        connectToTLS("127.0.0.1", defaultPort);
    }

    public void connectToTLS(String host, int port) {
        try {
            SSLSocketFactory ssf = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket socket = (SSLSocket) ssf.createSocket();
            socket.connect(new InetSocketAddress(host, port), 4000);

            try (socket) {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                new Thread(() -> readerLoop(socket)).start();
            }
        } catch (IOException e) {
            throw new RuntimeException("TLS 연결 실패", e);
        }
    }

    private void readerLoop(SSLSocket socket) {
        try {
            String line;
            while (running && !socket.isClosed() && (line = in.readLine()) != null) {
                onLine.accept(line);
            }
        } catch (IOException ignored) {}
        finally {
            closeConnection();
        }
    }

    public void sendRegister(String pubB64) {
        sendLine("REGISTER|" + nick + "|" + pubB64);
    }

    public void sendKeyExchange(String pubB64, String to) {
        sendLine("KEY|" + nick + "|" + to + "|" + pubB64);
    }

    public void sendMessageToPeer(String to, String payload) {
        sendLine("MSG|" + nick + "|" + to + "|" + payload);
    }

    private void sendLine(String line) {
        try {
            out.write(line);
            out.newLine();
            out.flush();
        } catch (IOException e) {
            onLine.accept("send fail: " + e.getMessage());
        }
    }

    public void closeConnection() {
        running = false;
        try {
            if (in != null) in.close();
            if (out != null) out.close();
        } catch (IOException ignored) {}
    }
}
