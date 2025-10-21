package me.duckmain.ghostcat.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import me.duckmain.ghostcat.crypto.CryptoUtils;
import me.duckmain.ghostcat.network.ChatClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.awt.Desktop;
import java.io.File;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import javafx.stage.FileChooser;
import javafx.stage.Window;

public class ChatController {

    private static final Logger logger = Logger.getLogger(ChatController.class.getName());

    @FXML private TextArea chatArea;
    @FXML private TextField messageField;
    @FXML private Label statusLabel;
    @FXML private ListView<String> peersList;

    private ChatClient client;
    private String nick;
    private final Set<String> pendingKeyRequests = Collections.synchronizedSet(new HashSet<>());

    private static final long MAX_FILE_BYTES = 50L * 1024L * 1024L;

    @FXML
    public void initialize() {
        peersList.setOnMouseClicked(evt -> {
            if (evt.getClickCount() == 2) {
                String sel = peersList.getSelectionModel().getSelectedItem();
                if (sel != null) appendChat("Selected peer: " + sel);
            }
        });
    }

    public void initConnection(String nickname, String host, int port) {
        this.nick = nickname;
        appendChat("Nickname: " + nick);

        try {
            if (CryptoUtils.getStaticPublic() == null)
                CryptoUtils.generateStaticKeypair();
        } catch (Exception e) {
            logError("Static keypair generation failed", e);
            return;
        }

        client = new ChatClient(nick, this::onIncomingLine);

        new Thread(() -> {
            try {
                client.connectToTLS(host, port); // 내부에서 trustFactory 사용
                client.sendRegister(Base64.getEncoder().encodeToString(CryptoUtils.getStaticPublic()));
                Platform.runLater(() -> statusLabel.setText("Connected as " + nick));
            } catch (Exception e) {
                logError("Connection failed", e);
                Platform.runLater(() -> statusLabel.setText("Connect failed: " + e.getMessage()));
            }
        }).start();
    }
    
    // TODO: PEER Refresh시 Stream 닫히는 문제 해결 필요 [자동 Refresh됨 그래서 필요한가 의문임]
    /*
     * 일단 주석처리함
     * @FXML
     * protected void onRefreshPeers() {
     *      if (client == null) return;
     *      try {
     *          client.sendRegister(Base64.getEncoder().encodeToString(CryptoUtils.getStaticPublic()));
     *          appendChat("Requested peer list update.");
     *      } catch (Exception e) {
     *          logError("Refresh peers failed", e);
     *      }
     * }
     */

    private void onIncomingLine(String line) {
        if (line == null || line.isBlank()) return;
        String[] parts = line.split("\\|", 4);
        String type = parts.length > 0 ? parts[0] : "";

        switch (type) {
            case "PEERS" -> handlePeers(parts);
            case "KEY" -> handleKey(parts);
            case "MSG" -> handleMessage(parts);
            default -> appendChat("[RAW] " + line);
        }
    }

    private void handlePeers(String[] parts) {
        if (parts.length < 2) return;
        List<String> peers = Stream.of(parts[1].split(","))
                .filter(s -> !s.isBlank() && !s.equals(nick))
                .collect(Collectors.toList());
        Platform.runLater(() -> peersList.getItems().setAll(peers));
    }

    private void handleKey(String[] parts) {
        if (parts.length < 4) return;
        String fromNick = parts[1];
        String toNick = parts[2];
        if (!toNick.equals(nick)) return;
        try {

            byte[] theirStaticKey = Base64.getDecoder().decode(parts[3]);

            // 중요: 상대방의 키를 저장하기 *전에* 내가 이미 키를 가지고 있는지 확인합니다.
            // 키가 없다면 첫 요청이므로 응답해야 합니다.
            boolean isReplyToMyRequest = pendingKeyRequests.remove(fromNick);

            // 상대방의 키를 저장(또는 최신 키로 업데이트)합니다.
            CryptoUtils.storePeerStatic(fromNick, theirStaticKey);
            appendChat("Stored/Updated static key for " + fromNick);

            // 첫 요청일 경우에만 내 키를 응답으로 보냅니다.
            if (!isReplyToMyRequest) {
                String myStaticKeyB64 = Base64.getEncoder().encodeToString(CryptoUtils.getStaticPublic());
                client.sendKeyExchange(myStaticKeyB64, fromNick);
                appendChat("Replying with my key to " + fromNick);
            }

        } catch (Exception e) {
            logError("KEY processing failed from " + parts[1], e);
        }
    }

    private void handleMessage(String[] parts) {
        if (parts.length < 4) return;
        String from = parts[1];
        String to = parts[2];
        String payload = parts[3];

        if (!(to.equals(nick) || to.equals("*"))) return;

        try {
            String[] pcs = payload.split(":", 3);
            if (pcs.length != 3) {
                appendChat("Invalid MSG payload from " + from);
                return;
            }

            byte[] ephPub = Base64.getDecoder().decode(pcs[0]);
            byte[] iv = Base64.getDecoder().decode(pcs[1]);
            byte[] ct = Base64.getDecoder().decode(pcs[2]);

            byte[] shared = CryptoUtils.sharedStaticEphemeral(ephPub);
            byte[] key = CryptoUtils.hkdf(shared, null, 32);
            String plain = CryptoUtils.decryptAESGCM(ct, key, iv);

            // 파일인지 텍스트인지 판별
            if (plain.startsWith("FILE:")) {
                // 형식: FILE:<filename>:<base64data>
                String[] fileParts = plain.split(":", 3);
                if (fileParts.length == 3) {
                    String filename = fileParts[1];
                    String b64 = fileParts[2];
                    handleIncomingFile(from, filename, b64);
                } else {
                    appendChat(from + " >> " + "(invalid file payload)");
                }
            } else {
                appendChat(from + " >> " + plain);
            }
        } catch (Exception e) {
            logError("Message decryption failed from " + from, e);
        }
    }

    /**
     * 파일 수신 처리: 임시 파일로 저장 후 OS 기본 앱으로 열기 시도.
     * (JavaFX에 이미지/비디오 뷰를 추가하기보다, 기존 UI 구조 유지하면서 즉시 확인 가능하게 구현)
     */
    private void handleIncomingFile(String from, String filename, String base64data) {
        try {
            byte[] fileBytes = Base64.getDecoder().decode(base64data);
            Path tmp = Files.createTempDirectory("chatfile_");
            Path out = tmp.resolve(sanitizeFilename(filename));
            Files.write(out, fileBytes);
            appendChat(from + " >> 파일 수신: " + out);
            // OS 기본앱으로 열기 시도 (이미지/비디오 모두 가능)
            if (Desktop.isDesktopSupported()) {
                try {
                    Desktop.getDesktop().open(out.toFile());
                } catch (IOException e) {
                    appendChat("파일 열기 실패: " + e.getMessage());
                    logger.log(Level.WARNING, "Failed to open file " + out, e);
                }
            } else {
                appendChat("파일이 저장되었습니다: " + out);
            }
        } catch (IllegalArgumentException iae) {
            appendChat("파일 디코드 실패 (base64 invalid)");
            logger.log(Level.WARNING, "Base64 decode failed", iae);
        } catch (IOException e) {
            appendChat("파일 저장 실패: " + e.getMessage());
            logger.log(Level.SEVERE, "File save failed", e);
        }
    }

    // 안전한 파일명으로 정리 (간단한 정리)
    private String sanitizeFilename(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    @FXML
    protected void onSendClick() {
        if (client == null) {
            appendChat("Not connected.");
            return;
        }
        String text = messageField.getText();
        if (text == null || text.trim().isEmpty()) return;

        String target = peersList.getSelectionModel().getSelectedItem();
        if (target == null) {
            appendChat("Select a peer first."); return;
        }

        try {
            byte[] peerStatic = CryptoUtils.getPeerStatic(target);

            // 키가 없으면 먼저 요청
            if (peerStatic == null) {
                pendingKeyRequests.add(target);
                client.sendKeyExchange(Base64.getEncoder().encodeToString(CryptoUtils.getStaticPublic()), target);
                appendChat("Requested static key from " + target);
                return;
            }

            KeyPair ephKP = CryptoUtils.generateEphemeral();
            byte[] shared = CryptoUtils.sharedEphemeralStatic(ephKP.getPrivate(), peerStatic);
            byte[] key = CryptoUtils.hkdf(shared, null, 32);
            byte[] iv = CryptoUtils.randomIV();
            byte[] ct = CryptoUtils.encryptAESGCM(text, key, iv);

            String payload = Base64.getEncoder().encodeToString(ephKP.getPublic().getEncoded()) + ":" +
                    Base64.getEncoder().encodeToString(iv) + ":" +
                    Base64.getEncoder().encodeToString(ct);

            client.sendMessageToPeer(target, payload);
            appendChat("Me -> " + target + ": " + text);
            messageField.clear();

        } catch (Exception e) {
            logError("Send failed to " + target, e);
        }
    }

    /**
     * 파일 전송 핸들러 — 기존 암호화/송신 플로우와 동일하게 처리
     * (UI: FileChooser 사용)
     */
    @FXML
    protected void onSendFileClick() {
        if (client == null) {
            appendChat("Not connected.");
            return;
        }

        String target = peersList.getSelectionModel().getSelectedItem();
        if (target == null) {
            appendChat("Select a peer first."); return;
        }

        Window window = peersList.getScene().getWindow();
        FileChooser chooser = new FileChooser();
        chooser.setTitle("파일 선택 (이미지/동영상)");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Images & Videos", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.mp4", "*.mov", "*.m4v")
        );
        File file = chooser.showOpenDialog(window);
        if (file == null) return;

        try {
            long size = Files.size(file.toPath());
            if (size > MAX_FILE_BYTES) {
                appendChat("파일 크기 초과 (최대 " + (MAX_FILE_BYTES / (1024 * 1024)) + "MB)");
                return;
            }
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            byte[] peerStatic = CryptoUtils.getPeerStatic(target);
            if (peerStatic == null) {
                pendingKeyRequests.add(target);
                client.sendKeyExchange(Base64.getEncoder().encodeToString(CryptoUtils.getStaticPublic()), target);
                appendChat("Requested static key from " + target + " (파일 전송을 위해)");
                return;
            }

            // 평문 포맷: FILE:<filename>:<base64data>
            String payloadPlain = "FILE:" + file.getName() + ":" + Base64.getEncoder().encodeToString(fileBytes);

            KeyPair ephKP = CryptoUtils.generateEphemeral();
            byte[] shared = CryptoUtils.sharedEphemeralStatic(ephKP.getPrivate(), peerStatic);
            byte[] key = CryptoUtils.hkdf(shared, null, 32);
            byte[] iv = CryptoUtils.randomIV();
            byte[] ct = CryptoUtils.encryptAESGCM(payloadPlain, key, iv);

            String payload = Base64.getEncoder().encodeToString(ephKP.getPublic().getEncoded()) + ":" +
                    Base64.getEncoder().encodeToString(iv) + ":" +
                    Base64.getEncoder().encodeToString(ct);

            client.sendMessageToPeer(target, payload);
            appendChat("Me -> " + target + ": 파일 전송 - " + file.getName());
        } catch (IOException e) {
            appendChat("파일 읽기/전송 실패: " + e.getMessage());
            logger.log(Level.SEVERE, "File send failed", e);
        } catch (Exception e) {
            logError("File send failed to " + target, e);
        }
    }

    public void closeConnection() {
        try {
            if (client != null) {
                client.closeConnection();
                client = null;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "chatClient close error", e);
        }
        Platform.runLater(() -> statusLabel.setText("Disconnected"));
        appendChat("Connection closed.");
    }

    private void appendChat(String message) {
        Platform.runLater(() -> chatArea.appendText(message + "\n"));
        logger.info(message);
    }

    private void logError(String message, Exception e) {
        appendChat(message + ": " + e.getMessage());
        logger.log(Level.SEVERE, message, e);
    }
}