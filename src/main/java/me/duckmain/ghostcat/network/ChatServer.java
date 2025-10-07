package me.duckmain.ghostcat.network;

import me.duckmain.ghostcat.tls.SSLUtil;

import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLServerSocket;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

// TODO: P2P 계승 코드 필요. Tlqkf w qudtls같은 AI 제대로 못만드나
public class ChatServer {
    private final int port;
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ScheduledExecutorService broadcastScheduler;
    private volatile boolean running = true;
    private volatile SSLServerSocket serverSocket;
    private final CountDownLatch portReadyLatch = new CountDownLatch(1);

    public ChatServer(int port, boolean enableBroadcast) {
        this.port = port;
        if (enableBroadcast) startBroadcast();
    }

    // LAN 브로드캐스트
    private void startBroadcast() {
        broadcastScheduler = Executors.newSingleThreadScheduledExecutor();
        broadcastScheduler.scheduleAtFixedRate(() -> {
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setBroadcast(true);
                String msg = "E2EE-SERVER:" + InetAddress.getLocalHost().getHostAddress() + ":" + getBoundPort();
                byte[] data = msg.getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet = new DatagramPacket(data, data.length,
                        InetAddress.getByName("255.255.255.255"), 9999);
                ds.send(packet);
            } catch (IOException e) {
                System.err.println("Broadcast error: " + e.getMessage());
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    public void stopBroadcast() {
        if (broadcastScheduler != null) broadcastScheduler.shutdownNow();
    }

    // 서버 시작
    public void start() {
        try {
            SSLServerSocketFactory ssf = SSLUtil.serverSSLContext().getServerSocketFactory();
            serverSocket = (SSLServerSocket) ssf.createServerSocket(port);
            serverSocket.setReuseAddress(true);
            portReadyLatch.countDown();
            System.out.println("Server started on port " + getBoundPort());

            while (running) {
                Socket clientSocket = serverSocket.accept();
                pool.submit(() -> handleSocket(clientSocket));
            }
        } catch (IOException e) {
            System.err.println("Server start failed: " + e.getMessage());
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            stopServer();
        }
    }

    public void stopServer() {
        running = false;
        stopBroadcast();
        pool.shutdownNow();
        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (IOException ignored) {}
        for (Client c : clients.values()) {
            try { c.socket().close(); } catch (IOException ignored) {}
        }
        clients.clear();
    }

    public int waitForPort() throws InterruptedException {
        portReadyLatch.await();
        return getBoundPort();
    }

    public int getBoundPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : -1;
    }

    private void handleSocket(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("REGISTER|")) {
                    String nick = line.split("\\|")[1];
                    clients.put(nick, new Client(nick, socket, writer));
                    sendPeerList();
                    continue;
                }
                if (line.startsWith("KEY|") || line.startsWith("MSG|")) {
                    String[] parts = line.split("\\|",4);
                    String to = parts[2];
                    if ("*".equals(to)) {
                        for (Client c : clients.values())
                            if (!c.nick().equals(parts[1]))
                                c.sendLine(line);
                    } else {
                        Client dest = clients.get(to);
                        if (dest != null) dest.sendLine(line);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Client socket error: " + e.getMessage());
        } finally {
            removeSocket(socket);
        }
    }

    private void removeSocket(Socket socket) {
        for (Iterator<Client> it = clients.values().iterator(); it.hasNext(); ) {
            Client c = it.next();
            Socket s = c.socket();
            if (s.isClosed() || s.equals(socket)) {
                try { if (!s.isClosed()) s.close(); } catch (IOException ignored) {}
                it.remove();
            }
        }
        sendPeerList();

        if (clients.isEmpty()) {
            System.out.println("No clients connected, shutting down.");
            stopServer();
        }
    }

    private void sendPeerList() {
        StringBuilder sb = new StringBuilder();
        for (String n : clients.keySet()) {
            if (!sb.isEmpty()) sb.append(",");
            sb.append(n);
        }
        String line = "PEERS|" + sb;
        for (Client client : clients.values()) client.sendLine(line);
    }

    private record Client(String nick, Socket socket, BufferedWriter writer) {
        synchronized void sendLine(String line) {
            try {
                writer.write(line);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                System.err.println("Send error to " + nick + ": " + e.getMessage());
            }
        }
    }
}
