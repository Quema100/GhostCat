package me.duckmain.ghostcat.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import me.duckmain.ghostcat.crypto.CryptoUtils;
import me.duckmain.ghostcat.network.ChatClient;
import me.duckmain.ghostcat.tls.SSLUtil;

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
                if (sel != null) {
                    appendChat("Selected peer: " + sel);
                }
            }
        });
    }

    public void initConnection(String nickname, String host, int port) {
        this.nick = nickname;
        appendChat("닉네임: " + nick);

        try {
            if (CryptoUtils.getStaticPublic() == null) {
                CryptoUtils.generateStaticKeypair();
            }
        } catch (Exception e) {
            logError("Static keypair generation failed", e);
            return;
        }

        client = new ChatClient(nick, this::onIncomingLine);

        if ("AUTO".equals(host)) {
            new Thread(() -> {
                client.autoDiscoverAndConnect(port);
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                try { client.sendRegister(Base64.getEncoder().encodeToString(CryptoUtils.getStaticPublic())); } catch (Exception ignored) {}
                Platform.runLater(() -> statusLabel.setText("Mode: AUTO (discovering local server)"));
            }).start();
        } else {
            final String targetHost = host;
            final int targetPort = port;
            new Thread(() -> {
                try {
                    SSLUtil.ensureTrustAll();
                    client.connectToTLS(targetHost, targetPort);
                    client.sendRegister(Base64.getEncoder().encodeToString(CryptoUtils.getStaticPublic()));
                    Platform.runLater(() -> statusLabel.setText("Connected to " + targetHost + ":" + targetPort));
                } catch (Exception e) {
                    logError("Connection failed to " + targetHost + ":" + targetPort, e);
                    Platform.runLater(() -> statusLabel.setText("Connect failed: " + e.getMessage()));
                }
            }).start();
        }
    }

    @FXML
    protected void onRefreshPeers() {
        try {
            client.sendRegister(Base64.getEncoder().encodeToString(CryptoUtils.getStaticPublic()));
        } catch (Exception e) {
            logError("Refresh peers failed", e);
        }
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
                if (parts.length >= 4) {
                    String from = parts[1];
                    String to = parts[2];
                    String pubb64 = parts[3];
                    if (to.equals(nick)) {
                        try {
                            byte[] theirStatic = Base64.getDecoder().decode(pubb64);
                            CryptoUtils.storePeerStatic(from, theirStatic);
                            appendChat("Stored static key for " + from);
                        } catch (Exception e) {
                            logError("KEY processing failed from " + from, e);
                        }
                    }
                }
                break;

            case "MSG":
                if (parts.length >= 4) {
                    String from = parts[1];
                    String to = parts[2];
                    String payload = parts[3];
                    if (!to.equals(nick) && !to.equals("*")) break;

                    try {
                        String[] pcs = payload.split(":", 3);
                        if (pcs.length != 3) {
                            appendChat("Invalid MSG payload from " + from);
                            break;
                        }
                        byte[] ephPub = Base64.getDecoder().decode(pcs[0]);
                        byte[] iv = Base64.getDecoder().decode(pcs[1]);
                        byte[] ct = Base64.getDecoder().decode(pcs[2]);

                        byte[] shared = CryptoUtils.sharedStaticEphemeral(ephPub);
                        byte[] key = CryptoUtils.hkdf(shared, null, 32);
                        String plain = CryptoUtils.decryptAESGCM(ct, key, iv);

                        appendChat(from + " >> " + plain);
                    } catch (Exception e) {
                        logError("MSG decryption failed from " + from, e);
                    }
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
        if (target == null) {
            appendChat("Peer를 선택하세요.");
            return;
        }

        try {
            byte[] peerStatic = CryptoUtils.getPeerStatic(target);
            if (peerStatic == null) {
                String ourStaticB64 = Base64.getEncoder().encodeToString(CryptoUtils.getStaticPublic());
                client.sendKeyExchange(ourStaticB64, target);
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

    private void appendChat(String message) {
        Platform.runLater(() -> chatArea.appendText(message + "\n"));
        logger.info(message);
    }

    private void logError(String message, Exception e) {
        appendChat(message + ": " + e.getMessage());
        logger.log(Level.SEVERE, message, e);
    }
}