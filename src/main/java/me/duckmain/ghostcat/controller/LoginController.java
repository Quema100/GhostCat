package me.duckmain.ghostcat.controller;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import java.io.IOException;


public class LoginController {
    @FXML private TextField nicknameField;
    @FXML private Label statusLabel;

    @FXML
    protected void onNextClick() {
        String nick = nicknameField.getText() == null ? "" : nicknameField.getText().trim();
        if (nick.isEmpty()) {
            statusLabel.setText("닉네임을 입력해야 합니다.");
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/me/duckmain/ghostcat/ServerSelectView.fxml"));
            Scene scene = new Scene(loader.load(), 640, 320);
            ServerSelectController ctrl = loader.getController();
            ctrl.setNickname(nick);
            Stage stage = (Stage) nicknameField.getScene().getWindow();
            stage.setScene(scene);
        } catch (IOException err) {
            statusLabel.setText("화면을 로드하지 못했습니다: " + err.getMessage());
            System.out.println("에러"+ err);
        }
    }


    @FXML
    protected void onCancelClick() {
        Stage stage = (Stage) nicknameField.getScene().getWindow();
        stage.close();
    }
}