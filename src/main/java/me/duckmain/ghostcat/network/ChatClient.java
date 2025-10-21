package me.duckmain.ghostcat.network;

import me.duckmain.ghostcat.tls.SSLUtil;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class ChatClient {
    private BufferedReader in;
    private BufferedWriter out;
    private SSLSocket socket;
    private final Consumer<String> onLine;
    private final String nick;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread readerThread;

    public ChatClient(String nick, Consumer<String> onLine) {
        this.nick = nick;
        this.onLine = onLine;
    }

    // 기본 팩토리를 사용하는 공개 연결 메서드
    public void connectToTLS(String host, int port) throws Exception {
        SSLSocketFactory trustFactory = SSLUtil.trustAllFactory();
        connectToTLSWithFactory(trustFactory, host, port);
    }

    // 제공된 SSLSocketFactory를 사용하는 핵심 연결 — 실제로 trustAllFactory를 사용함을 보장함
    public void connectToTLSWithFactory(SSLSocketFactory ssf, String host, int port) throws Exception {
        // create socket with the provided factory (so trust-all is honored)
        socket = (SSLSocket) ssf.createSocket();
        socket.connect(new InetSocketAddress(host, port), 4000);

        /*
         * 중요: startHandshake()는 핸드셰이크가 완료(또는 실패)될 때까지 블로킹됩니다.
         * 핸드셰이크가 성공적으로 완료된 이후에만 스트림을 열고 리더를 시작합니다.
         */
        socket.startHandshake();

        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

        running.set(true);
        readerThread = new Thread(this::readerLoop, "ChatClient-Reader-" + nick);
        readerThread.setDaemon(true); // <- 프로그램 종료시 자동 종료
        readerThread.start();
    }

    private void readerLoop() {
        try {
            String line;
            while (running.get() && socket != null && !socket.isClosed() && (line = in.readLine()) != null) {
                onLine.accept(line);
            }
        } catch (IOException e) {
            if (running.get()) {
                onLine.accept("Client socket error: " + e.getMessage());
            }
        } finally {
            cleanup();
        }
    }

    public void sendRegister(String pubB64) { sendLine("REGISTER|" + nick + "|" + pubB64); }
    public void sendKeyExchange(String pubB64, String to) { sendLine("KEY|" + nick + "|" + to + "|" + pubB64); }
    public void sendMessageToPeer(String to, String payload) { sendLine("MSG|" + nick + "|" + to + "|" + payload); }


    public void sendImageToPeer(String to, File imageFile) { sendFile(to, imageFile, "IMG"); }

    public void sendVideoToPeer(String to, File videoFile) { sendFile(to, videoFile, "VID"); }

    private void sendFile(String to, File file, String type) {
        if (file == null || !file.exists() || !file.isFile()) {
            onLine.accept("File not found: " + file);
            return;
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = fis.readAllBytes();
            String encoded = Base64.getEncoder().encodeToString(data);
            String fileName = file.getName();
            sendLine(type + "|" + nick + "|" + to + "|" + fileName + "|" + encoded);
            onLine.accept("Sent " + type + " file: " + fileName + " (" + data.length + " bytes)");
        } catch (IOException e) {
            onLine.accept("File send failed: " + e.getMessage());
        }
    }

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
        if (!running.getAndSet(false)) return;

        running.set(false);

        System.out.println("close Connection");

        try {
            if (socket != null && !socket.isClosed()) {
                try { socket.shutdownInput(); } catch (IOException ignored) {}
                try { socket.shutdownOutput(); } catch (IOException ignored) {}
            }
        } catch (Exception ignored) {}

        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        try { if (in != null) in.close(); } catch (IOException ignored) {}
        try { if (out != null) out.close(); } catch (IOException ignored) {}

        // Reader thread join (안전하게 완전 종료 보장)
        if (readerThread != null && readerThread.isAlive()) {
            try {
                readerThread.join(500); // 최대 0.5초 대기
            } catch (InterruptedException ignored) {}
        }
    }

    private void cleanup() {
        if (!running.getAndSet(false)) return;

        System.out.println("clean up");

        running.set(false);

        try { if (in != null) in.close(); } catch (IOException ignored) {}
        try { if (out != null) out.close(); } catch (IOException ignored) {}
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException ignored) {}
    }
}
