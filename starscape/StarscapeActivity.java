package com.winlator;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.FileUtils;
import com.winlator.inputcontrols.InputControlsManager;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.RootFSInstaller;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Starscape-specific launcher layered on top of Winlator.
 *
 * The public build never carries the game. If assets/starscape/Starscape.zip
 * is present (added locally after CI), setup is automatic. Otherwise the user
 * is asked to pick their own Starscape.zip once.
 */
public class StarscapeActivity extends MainActivity {
    private static final int REQUEST_STARSCAPE_ZIP = 9101;
    private static final String PREF_SETUP_DONE = "starscape_setup_done_v1";
    private static final String CONTAINER_NAME = "Starscape";
    private static final int PROFILE_ID = 91;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean pickerShown = false;
    private boolean setupRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handler.postDelayed(this::continueWhenRuntimeReady, 700);
    }

    private void continueWhenRuntimeReady() {
        RootFS rootFS = RootFS.find(this);
        if (!rootFS.isValid() || rootFS.getVersion() < RootFSInstaller.LATEST_VERSION) {
            handler.postDelayed(this::continueWhenRuntimeReady, 700);
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean(PREF_SETUP_DONE, false)) {
            if (launchExistingStarscape()) return;
            prefs.edit().remove(PREF_SETUP_DONE).apply();
        }

        if (setupRunning) return;

        if (hasBundledGame()) {
            beginSetup(() -> getAssets().open("starscape/Starscape.zip"));
        }
        else if (!pickerShown) {
            pickerShown = true;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream"});
            startActivityForResult(intent, REQUEST_STARSCAPE_ZIP);
            Toast.makeText(this, "Chọn file Starscape.zip để cài game", Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasBundledGame() {
        try (InputStream in = getAssets().open("starscape/Starscape.zip")) {
            byte[] magic = new byte[2];
            return in.read(magic) == 2 && magic[0] == 'P' && magic[1] == 'K';
        }
        catch (IOException e) {
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_STARSCAPE_ZIP) return;

        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            pickerShown = false;
            Toast.makeText(this, "Chưa chọn Starscape.zip", Toast.LENGTH_SHORT).show();
            return;
        }

        final Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        catch (Exception ignored) {}

        beginSetup(() -> getContentResolver().openInputStream(uri));
    }

    private interface InputStreamFactory {
        InputStream open() throws IOException;
    }

    private void beginSetup(InputStreamFactory sourceFactory) {
        if (setupRunning) return;
        setupRunning = true;
        Toast.makeText(this, "Đang chuẩn bị Starscape…", Toast.LENGTH_LONG).show();

        // Forces bundled control templates to be copied into the app profile store.
        new InputControlsManager(this).getProfiles();

        ContainerManager manager = new ContainerManager(this);
        for (Container existing : manager.getContainers()) {
            if (CONTAINER_NAME.equals(existing.getName()) && gameExe(existing).isFile()) {
                finishSetupAndLaunch(existing);
                return;
            }
        }

        try {
            JSONObject config = new JSONObject();
            config.put("name", CONTAINER_NAME);
            config.put("screenSize", "1280x720");
            config.put("envVars", Container.DEFAULT_ENV_VARS + " MESA_EXTENSION_MAX_YEAR=2003");

            manager.createContainerAsync(config, container -> {
                if (container == null) {
                    setupRunning = false;
                    Toast.makeText(this, "Không tạo được môi trường Starscape", Toast.LENGTH_LONG).show();
                    return;
                }

                Executors.newSingleThreadExecutor().execute(() -> {
                    try (InputStream input = sourceFactory.open()) {
                        if (input == null) throw new IOException("Unable to open Starscape archive");
                        File destination = new File(container.getRootDir(), ".wine/drive_c/Starscape");
                        if (!destination.isDirectory() && !destination.mkdirs()) {
                            throw new IOException("Unable to create Starscape directory");
                        }
                        extractZipSafely(input, destination);
                        if (!gameExe(container).isFile()) throw new IOException("Starscape.exe missing from archive");
                        File shortcut = createShortcut(container);
                        if (!shortcut.isFile()) throw new IOException("Unable to create shortcut");
                        runOnUiThread(() -> finishSetupAndLaunch(container));
                    }
                    catch (Exception e) {
                        runOnUiThread(() -> {
                            setupRunning = false;
                            Toast.makeText(this, "Cài Starscape thất bại: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        });
                    }
                });
            });
        }
        catch (Exception e) {
            setupRunning = false;
            Toast.makeText(this, "Không tạo được cấu hình Starscape", Toast.LENGTH_LONG).show();
        }
    }

    private void extractZipSafely(InputStream input, File destination) throws IOException {
        String destRoot = destination.getCanonicalPath() + File.separator;
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zin = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String rawName = entry.getName().replace('\\', '/');
                while (rawName.startsWith("/")) rawName = rawName.substring(1);
                if (rawName.isEmpty()) continue;

                File out = new File(destination, rawName);
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(destRoot)) throw new IOException("Unsafe archive path");

                if (entry.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs()) throw new IOException("Cannot create " + rawName);
                }
                else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Cannot create folder for " + rawName);
                    }
                    try (FileOutputStream fout = new FileOutputStream(out)) {
                        int count;
                        while ((count = zin.read(buffer)) != -1) fout.write(buffer, 0, count);
                    }
                }
                zin.closeEntry();
            }
        }
    }

    private File createShortcut(Container container) {
        File desktop = new File(container.getUserDir(), "Desktop");
        if (!desktop.isDirectory()) desktop.mkdirs();
        File shortcut = new File(desktop, "Starscape.desktop");
        String content = "[Desktop Entry]\n" +
            "Name=Starscape\n" +
            "Exec=wine \\\"C:\\\\Starscape\\\\Starscape.exe\\\"\n" +
            "Type=Application\n" +
            "StartupNotify=false\n\n" +
            "[Extra Data]\n" +
            "forceFullscreen=1\n" +
            "controlsProfile=" + PROFILE_ID + "\n" +
            "screenSize=1280x720\n" +
            "envVars=MESA_EXTENSION_MAX_YEAR=2003\n";
        FileUtils.writeString(shortcut, content);
        return shortcut;
    }

    private File gameExe(Container container) {
        return new File(container.getRootDir(), ".wine/drive_c/Starscape/Starscape.exe");
    }

    private File shortcutFile(Container container) {
        return new File(container.getUserDir(), "Desktop/Starscape.desktop");
    }

    private boolean launchExistingStarscape() {
        ContainerManager manager = new ContainerManager(this);
        for (Container container : manager.getContainers()) {
            if (CONTAINER_NAME.equals(container.getName()) && gameExe(container).isFile()) {
                File shortcut = shortcutFile(container);
                if (!shortcut.isFile()) shortcut = createShortcut(container);
                launch(container, shortcut);
                return true;
            }
        }
        return false;
    }

    private void finishSetupAndLaunch(Container container) {
        PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean(PREF_SETUP_DONE, true).apply();
        setupRunning = false;
        File shortcut = shortcutFile(container);
        if (!shortcut.isFile()) shortcut = createShortcut(container);
        Toast.makeText(this, "Starscape sẵn sàng", Toast.LENGTH_SHORT).show();
        launch(container, shortcut);
    }

    private void launch(Container container, File shortcut) {
        Intent intent = new Intent(this, XServerDisplayActivity.class);
        intent.putExtra("container_id", container.id);
        intent.putExtra("shortcut_path", shortcut.getPath());
        startActivity(intent);
    }
}
