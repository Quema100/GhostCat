package me.duckmain.ghostcat.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import me.duckmain.ghostcat.crypto.CryptoUtils;
import me.duckmain.ghostcat.network.ChatClient;

import java.security.KeyPair;
import java.util.Base64;
import java.util.List;
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

        try { if (CryptoUtils.getStaticPublic() == null) CryptoUtils.generateStaticKeypair(); }
        catch (Exception e) { logError("Static keypair generation failed", e); return; }

        client = new ChatClient(nick, this::onIncomingLine);

        new Thread(() -> {
            try {
                if ("AUTO".equals(host)) {
                    client.autoDiscoverAndConnect(port); // auto 경로도 내부에서 trustFactory 사용
                } else {
                    client.connectToTLS(host, port); // 내부에서 trustFactory 사용
                }
                client.sendRegister(Base64.getEncoder().encodeToString(CryptoUtils.getStaticPublic()));
                Platform.runLater(() -> statusLabel.setText("Connected as " + nick));
            } catch (Exception e) {
                logError("Connection failed", e);
                Platform.runLater(() -> statusLabel.setText("Connect failed: " + e.getMessage()));
            }
        }).start();
    }

    @FXML
    protected void onRefreshPeers() {
        try { client.sendRegister(Base64.getEncoder().encodeToString(CryptoUtils.getStaticPublic())); }
        catch (Exception e) { logError("Refresh peers failed", e); }
    }

    private void onIncomingLine(String line) {
        if (line == null) return;
        String[] parts = line.split("\\|", 4);
        String type = parts.length > 0 ? parts[0] : "";

        switch (type) {
            case "PEERS":
                if (parts.length >= 2) {
                    List<String> peers = Stream.of(parts[1].split(","))
                            .filter(s -> !s.isBlank() && !s.equals(nick))
                            .collect(Collectors.toList());
                    Platform.runLater(() -> peersList.getItems().setAll(peers));
                }
                break;

            case "KEY":
                if (parts.length >= 4 && parts[2].equals(nick)) {
                    try { CryptoUtils.storePeerStatic(parts[1], Base64.getDecoder().decode(parts[3]));
                        appendChat("Stored static key for " + parts[1]);
                    } catch (Exception e) { logError("KEY processing failed from " + parts[1], e); }
                }
                break;

            case "MSG":
                if (parts.length >= 4 && (parts[2].equals(nick) || parts[2].equals("*"))) {
                    try {
                        String[] pcs = parts[3].split(":", 3);
                        if (pcs.length != 3) { appendChat("Invalid MSG payload from " + parts[1]); break; }
                        byte[] ephPub = Base64.getDecoder().decode(pcs[0]);
                        byte[] iv = Base64.getDecoder().decode(pcs[1]);
                        byte[] ct = Base64.getDecoder().decode(pcs[2]);

                        byte[] shared = CryptoUtils.sharedStaticEphemeral(ephPub);
                        byte[] key = CryptoUtils.hkdf(shared, null, 32);
                        String plain = CryptoUtils.decryptAESGCM(ct, key, iv);
                        appendChat(parts[1] + " >> " + plain);
                    } catch (Exception e) { logError("MSG decryption failed from " + parts[1], e); }
                }
                break;

            default:
                appendChat("RAW: " + line);
        }
    }

    @FXML
    protected void onSendClick() {
        String text = messageField.getText();
        if (text == null || text.trim().isEmpty()) return;

        String target = peersList.getSelectionModel().getSelectedItem();
        if (target == null) { appendChat("Peer를 선택하세요."); return; }

        try {
            byte[] peerStatic = CryptoUtils.getPeerStatic(target);
            if (peerStatic == null) {
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
        } catch (Exception e) { logError("Send failed to " + target, e); }
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