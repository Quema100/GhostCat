package me.duckmain.ghostcat.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import me.duckmain.ghostcat.network.ChatServer;
import me.duckmain.ghostcat.crypto.CryptoUtils;
import me.duckmain.ghostcat.tls.SSLUtil;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ServerSelectController {

    // @FXML private RadioButton autoRadio;
    @FXML private RadioButton localRadio;
    @FXML private RadioButton remoteRadio;
    @FXML private TextField hostField;
    @FXML private TextField portField;
    @FXML private Label infoLabel;

    private String nickname;
    private static final Logger logger = Logger.getLogger(ServerSelectController.class.getName());

    public void setNickname(String nick) {
        this.nickname = nick;
    }

    @FXML
    public void initialize() {
        ToggleGroup modeGroup = new ToggleGroup();
        localRadio.setToggleGroup(modeGroup);
        remoteRadio.setToggleGroup(modeGroup);
    }

    @FXML
    protected void onBackClick() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/me/duckmain/ghostcat/LoginView.fxml"));
            Scene scene = new Scene(loader.load(), 480, 260);
            Stage stage = (Stage) hostField.getScene().getWindow();
            stage.setScene(scene);
        } catch (IOException err) {
            infoLabel.setText("Login View load fail: " + err.getMessage());
            logger.log(Level.SEVERE, "Login View load fail", err);
        }
    }

    @FXML
    protected void onConnectClick() {
        try {
            CryptoUtils.generateStaticKeypair();
        } catch (Exception err) {
            infoLabel.setText("Crypto init fail: " + err.getMessage());
            logger.log(Level.SEVERE, "Crypto init fail", err);
            return;
        }
        if (localRadio.isSelected()) {
            new Thread(() -> {
                try {
                    SSLUtil.ensureServerKeystore();

                    // 기존 LAN 서버 탐색
                    InetSocketAddress serverAddr = discoverLocalServer(); // 2초 탐색
                    if (serverAddr == null) {
                        // 서버 없으면 새로 생성
                        String lanIp = getLocalNetworkIp();
                        if (lanIp == null) {
                          Platform.runLater(() -> infoLabel.setText("LAN IP를 찾을 수 없습니다."));
                           return;
                        }

                       System.out.println(lanIp);
                        ChatServer server = new ChatServer(0, true);
                        Thread serverThread = new Thread(server::start);
                        serverThread.setDaemon(true);
                        serverThread.start();
                        int assignedPort = server.waitForPort();
                        serverAddr = new InetSocketAddress(getLocalNetworkIp(), assignedPort);
                        System.out.println("Local server created at " + serverAddr);
                    } else {
                        System.out.println("Found existing server at " + serverAddr);
                    }

                    InetSocketAddress finalServerAddress = serverAddr;
                    Platform.runLater(() ->
                            moveToChat(nickname, finalServerAddress.getHostString(), finalServerAddress.getPort()));
                } catch (Exception e) {
                    Platform.runLater(() ->
                            infoLabel.setText("Local server connection fail: " + e.getMessage()));
                    logger.log(Level.SEVERE, "Local server connection fail", e);
                }
            }).start();
            return;
        }

        if (remoteRadio.isSelected()) {
            String host = hostField.getText().trim();
            String portStr = portField.getText().trim();

            if (host.isEmpty() || portStr.isEmpty()) {
                infoLabel.setText("호스트와 포트를 입력하세요.");
                return;
            }

            int port;
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                infoLabel.setText("포트는 숫자여야 합니다.");
                return;
            }

            moveToChat(nickname, host, port);
        }

    }

    private void moveToChat(String nick, String host, int port) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/me/duckmain/ghostcat/ChatView.fxml"));
            Scene scene = new Scene(loader.load(), 900, 640);
            me.duckmain.ghostcat.controller.ChatController ctrl = loader.getController();
            ctrl.initConnection(nick, host, port);

            Stage stage = (Stage) hostField.getScene().getWindow();
            stage.setScene(scene);

            stage.setOnCloseRequest(_ -> {
                ctrl.closeConnection();
            });
        } catch (IOException err) {
            infoLabel.setText("Chat view load fail: " + err.getMessage());
            logger.log(Level.SEVERE, "Chat view load fail", err);
        }
    }

    /**
     * 127.0.0.1이 아닌 LAN IPv4 주소 반환
     */
    private String getLocalNetworkIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkinterface = interfaces.nextElement();
                if (networkinterface.isLoopback() || !networkinterface.isUp()) continue;

                Enumeration<InetAddress> addresses = networkinterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "LAN IP search fail", e);
        }
        return null;
    }

    // UDP 브로드캐스트로 LAN 서버 탐색
    private InetSocketAddress discoverLocalServer() {
        try (DatagramSocket socket = new DatagramSocket(9999)) {
            socket.setBroadcast(true);
            socket.setSoTimeout(2000);
            byte[] buf = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);

            long endTime = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < endTime) {
                try {
                    socket.receive(packet);
                    String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    if (msg.startsWith("E2EE-SERVER:")) {
                        String[] parts = msg.split(":");
                        String ip = parts[1];
                        int port = Integer.parseInt(parts[2]);
                        return new InetSocketAddress(ip, port);
                    }
                } catch (SocketTimeoutException ignored) {}
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Server discovery fail", e);
        }
        return null;
    }
}