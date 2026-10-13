/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <student name and student number insert here upon modification>
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.util.List;

import javafx.application.Application;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * Extents JavaFX's Application class and is the entry point for the video player.
 * Provided required JavaFX methods start and stop and also basic on-screen window
 * management functions. Owns the {@link VideoPlayerController}, which in turn owns
 * the {@link VideoPlayerView} and {@link VideoPlayerController}.
 *
 * <p>
 * Creates the JavaFX Stage and Scene, the VideoPlayerController, and applies
 * command-line launch settings, and starts playback. It also resizes the video
 * display to fit the selected screen.
 * </p>
 */
public class VideoPlayerApp extends Application {
    private static CommandLineController commandLineController;

    private VideoPlayerController controller;
    private Stage stage;
    private Rectangle2D selectedScreenBounds;
    private int videoWidth;
    private int videoHeight;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        controller = new VideoPlayerController(
                commandLineController.isAudioRequested(),
                this::setVideoSize);

        Scene scene = new Scene(controller.getView().getRoot());
        controller.installKeyboardShortcuts(scene);

        stage.setTitle("COMP3011 Video Player");
        stage.setScene(scene);
        moveToSelectedScreen(stage);
        stage.setMaximized(commandLineController.isMaximiseRequested());
        stage.show();

        stage.outputScaleXProperty().addListener((_, _, _) -> updateVideoSize());
        stage.outputScaleYProperty().addListener((_, _, _) -> updateVideoSize());
        stage.setOnCloseRequest(_ -> controller.shutdown());

        playVideo();
    }

    private void moveToSelectedScreen(Stage stage) {
        List<Screen> screens = Screen.getScreens();
        Integer displayId = commandLineController.getDisplayId();
        Screen targetScreen;

        if (displayId == null) {
            targetScreen = screens.stream()
                    .filter(screen -> !screen.equals(Screen.getPrimary()))
                    .findFirst()
                    .orElse(Screen.getPrimary());
        } else if (displayId <= screens.size()) {
            targetScreen = screens.get(displayId - 1);
        } else {
            targetScreen = Screen.getPrimary();
            controller.getView().setStatus("Display " + displayId + " not found");
        }

        Rectangle2D bounds = targetScreen.getVisualBounds();
        selectedScreenBounds = bounds;
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
    }

    private void playVideo() {
        controller.play(commandLineController.getVideoFile());
    }

    private void setVideoSize(int width, int height) {
        videoWidth = width;
        videoHeight = height;
        updateVideoSize();
    }

    private void updateVideoSize() {
        if (videoWidth <= 0 || videoHeight <= 0) {
            return;
        }

        double outputScaleX = stage.getOutputScaleX();
        double outputScaleY = stage.getOutputScaleY();

        if (outputScaleX <= 0) {
            outputScaleX = 1;
        }
        if (outputScaleY <= 0) {
            outputScaleY = 1;
        }

        double displayWidth = videoWidth / outputScaleX;
        double displayHeight = videoHeight / outputScaleY;

        controller.getView().setVideoDisplaySize(displayWidth, displayHeight);
        if (!stage.isMaximized()) {
            stage.sizeToScene();
            alignSceneToSelectedScreen();
        }
    }

    private void alignSceneToSelectedScreen() {
        if (selectedScreenBounds == null || stage.getScene() == null) {
            return;
        }

        stage.setX(selectedScreenBounds.getMinX() - stage.getScene().getX());
        stage.setY(selectedScreenBounds.getMinY() - stage.getScene().getY());
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.shutdown();
        }
    }

    // Entry point.
    public static void main(String[] args) {
        commandLineController = new CommandLineController(args);

        if (!commandLineController.shouldLaunchApplication()) {
            System.exit(commandLineController.getExitCode());
        }

        launch(args);
    }
}
