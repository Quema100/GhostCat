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

import java.util.logging.Logger;
import java.util.logging.Level;
import java.io.IOException;

public class ServerSelectController {

    @FXML private RadioButton autoRadio;
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

        autoRadio.setToggleGroup(modeGroup);
        localRadio.setToggleGroup(modeGroup);
        remoteRadio.setToggleGroup(modeGroup);

        autoRadio.setSelected(true); // 필요시 기본 선택
    }

    @FXML
    protected void onBackClick() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("../LoginView.fxml"));
            Scene scene = new Scene(loader.load(), 480, 260);
            Stage stage = (Stage) hostField.getScene().getWindow();
            stage.setScene(scene);
        } catch (IOException err) {
            infoLabel.setText("이전 화면 로드 실패: " + err.getMessage());
            logger.log(Level.SEVERE, "이전 화면 로드 실패", err);
        }
    }

    @FXML
    protected void onConnectClick() {
        try {
            CryptoUtils.generateStaticKeypair();
        } catch (Exception err) {
            infoLabel.setText("Crypto 초기화 실패: " + err.getMessage());
            logger.log(Level.SEVERE, "Crypto 초기화 실패", err);
            return;
        }

        if (localRadio.isSelected()) {
            // 로컬 TLS 서버 시작 및 접속
            new Thread(() -> {
                try {
                    SSLUtil.ensureServerKeystore();
                    ChatServer server = new ChatServer(7777, true);

                    // 서버 시작 스레드
                    Thread serverThread = new Thread(() -> {
                        try {
                            server.start();
                        } catch (Exception e) {
                            logger.log(Level.SEVERE, "로컬 서버 시작 실패", e);
                        }
                    });
                    serverThread.setDaemon(true);
                    serverThread.start();

                    // 서버 시작 확인 후 채팅 화면으로 이동
                    Thread.sleep(500); // 최소 대기
                    Platform.runLater(() -> moveToChat(nickname, "127.0.0.1", 7777));
                } catch (Exception err) {
                    Platform.runLater(() -> infoLabel.setText("로컬 서버 시작 실패: " + err.getMessage()));
                    logger.log(Level.SEVERE, "로컬 서버 시작 실패", err);
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
            return;
        }

        // AUTO 모드
        moveToChat(nickname, "AUTO", 7777);
    }

    private void moveToChat(String nick, String host, int port) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("../ChatView.fxml"));
            Scene scene = new Scene(loader.load(), 900, 640);
            me.duckmain.ghostcat.controller.ChatController ctrl = loader.getController();
            ctrl.initConnection(nick, host, port);

            Stage stage = (Stage) hostField.getScene().getWindow();
            stage.setScene(scene);
        } catch (IOException err) {
            infoLabel.setText("Chat 화면 로드 실패: " + err.getMessage());
            logger.log(Level.SEVERE, "Chat 화면 로드 실패", err);
        }
    }
}
