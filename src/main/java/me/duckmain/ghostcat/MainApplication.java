package me.duckmain.ghostcat;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;


public class MainApplication extends Application {
    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("LoginView.fxml"));
        Scene scene = new Scene(loader.load(), 480, 260);
        stage.setTitle("GhostCat - Secure Chat");
        stage.setScene(scene);
        stage.show();
    }
}
