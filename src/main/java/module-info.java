module me.duckmain.ghostcat {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;
    requires java.logging;

    requires org.controlsfx.controls;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.bootstrapfx.core;
    requires org.bouncycastle.provider;
    requires org.bouncycastle.pkix;
    requires eu.hansolo.tilesfx;

    opens me.duckmain.ghostcat to javafx.fxml;
    opens me.duckmain.ghostcat.controller to javafx.fxml;
    exports me.duckmain.ghostcat;
    exports me.duckmain.ghostcat.controller;
}