package me.duckmain.ghostcat.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import me.duckmain.ghostcat.crypto.CryptoUtils;
import me.duckmain.ghostcat.network.ChatClient;

import java.security.KeyPair;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ChatController {

    private static final Logger logger = Logger.getLogger(ChatController.class.getName());

    @FXML private TextArea chatArea;
    @FXML private TextField messageField;
    @FXML private Label statusLabel;
    @FXML private ListView<String> peersList;

    private ChatClient client;
    private String nick;
    private final Set<String> pendingKeyRequests = Collections.synchronizedSet(new HashSet<>());

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
    /* *
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
     * */

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

            appendChat(from + " >> " + plain);
        } catch (Exception e) {
            logError("Message decryption failed from " + from, e);
        }
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