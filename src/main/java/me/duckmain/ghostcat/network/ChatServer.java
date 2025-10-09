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
import java.util.concurrent.atomic.AtomicBoolean;

// TODO: P2P 계승 코드 필요. Tlqkf w qudtls같은 AI 제대로 못만드나
public class ChatServer {
    private final int port;
    private final Map<String, Client> clients = new ConcurrentHashMap<>();

    private ExecutorService pool;
    private ScheduledExecutorService broadcastScheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile SSLServerSocket serverSocket;
    private final CountDownLatch portReadyLatch = new CountDownLatch(1);
    private Thread acceptThread;

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
        if (broadcastScheduler != null) {
            broadcastScheduler.shutdownNow();
            try {
                if (!broadcastScheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                    // 강제 종료 후에도 남아있다면 로그
                    System.err.println("Broadcast scheduler did not terminate quickly.");
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            broadcastScheduler = null;
        }
    }

    /**
     * 서버 시작: accept 루프는 별도 쓰레드에서 실행
     */
    public void start() {
        if (!running.compareAndSet(false, true)) return; // 이미 실행중이면 무시

        // executor 초기화
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "ChatServer-Worker");
            t.setDaemon(false);
            return t;
        });

        acceptThread = new Thread(() -> {
            try {
                SSLServerSocketFactory ssf = SSLUtil.serverSSLContext().getServerSocketFactory();
                serverSocket = (SSLServerSocket) ssf.createServerSocket(port);
                serverSocket.setReuseAddress(true);
                portReadyLatch.countDown();
                System.out.println("Server started on port " + getBoundPort());

                while (running.get()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        // 각 클라이언트 소켓은 별도 worker에서 처리
                        pool.submit(() -> handleSocket(clientSocket));
                    } catch (SocketException se) {
                        // 서버 소켓 닫힘으로 인한 정상적 종료 흐름일 수 있음
                        if (running.get()) {
                            System.err.println("SocketException in accept: " + se.getMessage());
                        }
                        break;
                    } catch (IOException e) {
                        if (!running.get()) break;
                        System.err.println("Accept error: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("Server start failed: " + e.getMessage());
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                // 보장된 정리
                stopServer(); // 안전한 stopServer는 idempotent
                System.out.println("Server main thread exiting");
            }
        }, "ChatServer-AcceptThread");

        acceptThread.setDaemon(false);
        acceptThread.start();
    }

    public void stopServer() {
        if (!running.compareAndSet(true, false))
            return; // 이미 멈춘 상태이면 리턴

        System.out.println("Stopping server...");

        // 중지 신호: broadcast 정지
        stopBroadcast();

        // 닫기 트리거: serverSocket.close() -> accept() 깨움
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}

        // 연결된 클라이언트 전부 닫기
        // 복사본을 만들어 순회 (동시 수정 방지)
        Set<String> nicks = clients.keySet();
        for (String nick : nicks) {
            Client c = clients.remove(nick);
            if (c != null) {
                try {
                    c.closeSafe();
                } catch (Exception ignored) {}
            }
        }

        // executor 종료
        if (pool != null) {
            pool.shutdownNow();
            try {
                if (!pool.awaitTermination(1, TimeUnit.SECONDS)) {
                    System.err.println("Worker pool did not terminate quickly.");
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            pool = null;
        }

        // acceptThread 인터럽트(만약 아직 살아있다면)
        if (acceptThread != null && acceptThread.isAlive()) {
            acceptThread.interrupt();
            acceptThread = null;
        }

        // 최종적으로 serverSocket 닫기(중복 안전)
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}

        serverSocket = null;

        System.out.println("Server stopped");
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
                    if (line.split("\\|").length < 2) continue;
                    String nick = line.split("\\|")[1];
                    Client prev = clients.put(nick, new Client(nick, socket, writer));
                    if (prev != null) {
                        try { prev.closeSafe(); } catch (Exception ignored) {}
                    }
                    sendPeerList();
                    continue;
                }
                if (line.startsWith("KEY|") || line.startsWith("MSG|")) {
                    String[] parts = line.split("\\|",4);
                    if (parts.length < 3) continue;
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
            // 연결 중 에러는 로그로 남김
            if (running.get()) {
                System.err.println("Client socket error: " + e.getMessage());
            }
        } finally {
            removeSocket(socket);
        }
    }

    private void removeSocket(Socket socket) {
        if (socket == null) return;
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

    /**
     * 내부 표현용 Client 레코드 (스트림/소켓 안전 종료 메서드 포함)
     **/
    private record Client(String nick, Socket socket, BufferedWriter writer) {
        synchronized void sendLine(String line) {
            try {
                if (writer != null) {
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                }
            } catch (IOException e) {
                System.err.println("Send error to " + nick + ": " + e.getMessage());
            }
        }
        void closeSafe() {
            try {
                if (writer != null) writer.close();
            } catch (IOException ignored) {}
            try {
                if (socket != null && !socket.isClosed()) socket.close();
            } catch (IOException ignored) {}
        }
    }
}
