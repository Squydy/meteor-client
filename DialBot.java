/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.util.math.Vec3d;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public class DialBot extends Module {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File DIALS_DIR = new File(MeteorClient.FOLDER, "dials");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBehavior = settings.createGroup("Behavior");

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks to wait after an attempt before trying the next dial.")
        .defaultValue(40)
        .min(0)
        .sliderMax(200)
        .build()
    );

    private final Setting<Integer> teleportTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("teleport-timeout")
        .description("Ticks to wait for a position change after sending /nxgo.")
        .defaultValue(80)
        .min(1)
        .sliderMax(400)
        .build()
    );

    private final Setting<Double> minDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-distance")
        .description("Minimum blocks moved to count a teleport as successful.")
        .defaultValue(8.0)
        .min(0.5)
        .sliderMax(64)
        .build()
    );

    private final Setting<Integer> startIndex = sgGeneral.add(new IntSetting.Builder()
        .name("start-index")
        .description("Marker key to start from (inclusive).")
        .defaultValue(1)
        .min(1)
        .sliderMax(5000)
        .build()
    );

    private final Setting<Integer> endIndex = sgGeneral.add(new IntSetting.Builder()
        .name("end-index")
        .description("Marker key to stop at (inclusive). 0 means until the end.")
        .defaultValue(0)
        .min(0)
        .sliderMax(5000)
        .build()
    );

    private final Setting<Boolean> disableOnLeave = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-on-leave")
        .description("Disables the module when you leave a server.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> disableOnDisconnect = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-on-disconnect")
        .description("Disables the module when you are disconnected.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> skipVerified = sgBehavior.add(new BoolSetting.Builder()
        .name("skip-verified")
        .description("Skip dials already present in the verified output file.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> autoSaveInterval = sgBehavior.add(new IntSetting.Builder()
        .name("auto-save-interval")
        .description("Flush verified JSON and failure log every N completed attempts. 0 = only on deactivate.")
        .defaultValue(5)
        .min(0)
        .sliderMax(50)
        .build()
    );

    private final Setting<Boolean> quiet = sgBehavior.add(new BoolSetting.Builder()
        .name("quiet")
        .description("Only log start/finish and errors, not each failure.")
        .defaultValue(false)
        .build()
    );

    private File inputFile;

    private WarpFile inputData;
    private WarpFile verifiedData;
    private final Set<String> verifiedDials = new HashSet<>();
    private final List<QueuedMarker> queue = new ArrayList<>();

    private BufferedWriter failLogWriter;
    private File verifiedFile;
    private File failLogFile;

    private State state = State.Idle;
    private int queueIndex;
    private int delayTimer;
    private int watchTimer;
    private int attemptsSinceSave;
    private Vec3d startPos;
    private QueuedMarker current;

    public DialBot() {
        super(Categories.Misc, "dial-bot", "Walks dial markers from a JSON file and verifies /nxgo teleports.");

        DIALS_DIR.mkdirs();

        File defaultFile = new File(DIALS_DIR, "warps.json");
        if (defaultFile.exists()) inputFile = defaultFile;
    }

    public void setInputFile(File file) {
        this.inputFile = file;
    }

    public File getInputFile() {
        return inputFile;
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WHorizontalList list = theme.horizontalList();

        WButton selectFile = list.add(theme.button("Select JSON")).widget();
        list.add(theme.label(inputFile != null && inputFile.exists() ? inputFile.getName() : "No file selected."));

        selectFile.action = () -> mc.setScreen(theme.dialBotFiles());

        return list;
    }

    @Override
    public String getInfoString() {
        if (!isActive() || queue.isEmpty()) return null;
        return (queueIndex + 1) + "/" + queue.size();
    }

    @Override
    public void onActivate() {
        DIALS_DIR.mkdirs();

        if (inputFile == null || !inputFile.exists()) {
            error("No JSON selected. Copy warps.json into meteor-client/dials/ and select it.");
            toggle();
            return;
        }

        if (!loadInput()) {
            toggle();
            return;
        }

        String baseName = stripExtension(inputFile.getName());
        verifiedFile = new File(DIALS_DIR, "verified-" + baseName + ".json");
        failLogFile = new File(DIALS_DIR, "failed-" + baseName + ".log");

        loadVerified();
        buildQueue();

        if (queue.isEmpty()) {
            warning("No dials to process in the selected range.");
            toggle();
            return;
        }

        try {
            failLogWriter = Files.newBufferedWriter(
                failLogFile.toPath(),
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
                java.nio.file.StandardOpenOption.WRITE
            );
        } catch (IOException e) {
            MeteorClient.LOG.error("Failed to open dial failure log", e);
            error("Could not open failure log.");
            toggle();
            return;
        }

        queueIndex = 0;
        attemptsSinceSave = 0;
        delayTimer = 0;
        current = null;
        state = State.WaitingDelay;

        info("Loaded %d dials from %s (start=%d end=%s).",
            queue.size(),
            inputFile.getName(),
            startIndex.get(),
            endIndex.get() == 0 ? "end" : String.valueOf(endIndex.get())
        );
    }

    @Override
    public void onDeactivate() {
        flushOutputs();
        closeFailLog();
        queue.clear();
        verifiedDials.clear();
        inputData = null;
        verifiedData = null;
        current = null;
        startPos = null;
        state = State.Idle;
    }

    @EventHandler
    private void onScreenOpen(OpenScreenEvent event) {
        if (disableOnDisconnect.get() && event.screen instanceof DisconnectedScreen) {
            toggle();
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (disableOnLeave.get()) toggle();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        if (state == State.Idle) return;

        switch (state) {
            case WaitingDelay -> {
                if (delayTimer > 0) {
                    delayTimer--;
                    return;
                }
                advanceToNextOrFinish();
            }
            case Watching -> {
                if (current == null || startPos == null) {
                    state = State.WaitingDelay;
                    delayTimer = delay.get();
                    return;
                }

                double minSq = minDistance.get() * minDistance.get();
                if (mc.player.squaredDistanceTo(startPos) >= minSq) {
                    onSuccess();
                    return;
                }

                watchTimer--;
                if (watchTimer <= 0) {
                    onFail("no_teleport");
                }
            }
            default -> {
            }
        }
    }

    private void advanceToNextOrFinish() {
        while (queueIndex < queue.size()) {
            QueuedMarker next = queue.get(queueIndex);
            queueIndex++;

            if (next.dial == null || next.dial.isBlank()) {
                logFail(next.indexKey, next.dial == null ? "" : next.dial, "invalid_empty");
                maybeAutoSave();
                continue;
            }

            if (skipVerified.get() && verifiedDials.contains(next.dial)) {
                if (!quiet.get()) info("Skipping verified dial \"%s\" [%s].", next.dial, next.indexKey);
                continue;
            }

            current = next;
            startPos = mc.player.getEntityPos();
            watchTimer = teleportTimeout.get();
            ChatUtils.sendPlayerMsg("/nxgo " + next.dial);
            state = State.Watching;
            return;
        }

        info("Finished dial scan (%d entries).", queue.size());
        flushOutputs();
        toggle();
    }

    private void onSuccess() {
        if (current == null) return;

        Marker marker = new Marker();
        marker.label = current.label != null && !current.label.isBlank() ? current.label : current.dial;
        marker.desc = current.desc != null ? current.desc : "";
        marker.x = Double.toString(mc.player.getX());
        marker.y = Double.toString(mc.player.getY());
        marker.z = Double.toString(mc.player.getZ());
        marker.dial = current.dial;

        if (verifiedData == null) {
            verifiedData = new WarpFile();
            verifiedData.group = inputData != null && inputData.group != null ? inputData.group : "verified";
            verifiedData.markers = new LinkedHashMap<>();
        }
        if (verifiedData.markers == null) verifiedData.markers = new LinkedHashMap<>();

        String verifiedIndex = String.valueOf(nextVerifiedIndex());
        verifiedData.markers.put(verifiedIndex, marker);
        verifiedDials.add(current.dial);

        if (!quiet.get()) info("Verified [%s] dial=\"%s\" (source index %s).", verifiedIndex, current.dial, current.indexKey);

        current = null;
        startPos = null;
        maybeAutoSave();
        delayTimer = delay.get();
        state = State.WaitingDelay;
    }

    private int nextVerifiedIndex() {
        int max = 0;
        if (verifiedData != null && verifiedData.markers != null) {
            for (String key : verifiedData.markers.keySet()) {
                try {
                    max = Math.max(max, Integer.parseInt(key));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return max + 1;
    }

    private void onFail(String reason) {
        if (current == null) return;
        logFail(current.indexKey, current.dial, reason);
        if (!quiet.get()) warning("Failed [%s] dial=\"%s\" (%s).", current.indexKey, current.dial, reason);
        current = null;
        startPos = null;
        maybeAutoSave();
        delayTimer = delay.get();
        state = State.WaitingDelay;
    }

    private void logFail(String indexKey, String dial, String reason) {
        String line = "[index=" + indexKey + "] dial=\"" + (dial == null ? "" : dial) + "\" reason=" + reason;
        try {
            if (failLogWriter != null) {
                failLogWriter.write(line);
                failLogWriter.newLine();
                failLogWriter.flush();
            }
        } catch (IOException e) {
            MeteorClient.LOG.error("Failed to write dial failure log", e);
        }
    }

    private void maybeAutoSave() {
        attemptsSinceSave++;
        int interval = autoSaveInterval.get();
        if (interval > 0 && attemptsSinceSave >= interval) {
            flushOutputs();
            attemptsSinceSave = 0;
        }
    }

    private boolean loadInput() {
        try (Reader reader = new FileReader(inputFile, StandardCharsets.UTF_8)) {
            inputData = GSON.fromJson(reader, WarpFile.class);
        } catch (IOException e) {
            MeteorClient.LOG.error("Failed to read dials JSON", e);
            error("Failed to read %s.", inputFile.getName());
            return false;
        }

        if (inputData == null || inputData.markers == null || inputData.markers.isEmpty()) {
            error("JSON has no markers.");
            return false;
        }

        return true;
    }

    private void loadVerified() {
        verifiedDials.clear();
        verifiedData = new WarpFile();
        verifiedData.group = inputData != null && inputData.group != null ? inputData.group : "verified";
        verifiedData.markers = new LinkedHashMap<>();

        if (verifiedFile == null || !verifiedFile.exists()) return;

        try (Reader reader = new FileReader(verifiedFile, StandardCharsets.UTF_8)) {
            WarpFile existing = GSON.fromJson(reader, WarpFile.class);
            if (existing != null) {
                if (existing.group != null) verifiedData.group = existing.group;
                if (existing.markers != null) {
                    verifiedData.markers.putAll(existing.markers);
                    for (Marker m : existing.markers.values()) {
                        if (m != null && m.dial != null && !m.dial.isBlank()) verifiedDials.add(m.dial);
                    }
                }
            }
        } catch (IOException e) {
            MeteorClient.LOG.error("Failed to read verified dials JSON", e);
            warning("Could not load existing verified file; starting fresh.");
        }
    }

    private void buildQueue() {
        queue.clear();

        List<String> keys = new ArrayList<>(inputData.markers.keySet());
        keys.sort((a, b) -> {
            try {
                return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
            } catch (NumberFormatException e) {
                return a.compareTo(b);
            }
        });

        int start = startIndex.get();
        int end = endIndex.get();

        for (String key : keys) {
            int numeric;
            try {
                numeric = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                continue;
            }

            if (numeric < start) continue;
            if (end > 0 && numeric > end) continue;

            Marker marker = inputData.markers.get(key);
            if (marker == null) continue;

            QueuedMarker queued = new QueuedMarker();
            queued.indexKey = key;
            queued.dial = marker.dial;
            queued.label = marker.label;
            queued.desc = marker.desc;
            queue.add(queued);
        }
    }

    private void flushOutputs() {
        if (verifiedFile != null && verifiedData != null && verifiedData.markers != null) {
            try {
                verifiedFile.getParentFile().mkdirs();
                try (Writer writer = new FileWriter(verifiedFile, StandardCharsets.UTF_8)) {
                    GSON.toJson(verifiedData, writer);
                }
            } catch (IOException e) {
                MeteorClient.LOG.error("Failed to write verified dials JSON", e);
                error("Failed to save verified JSON.");
            }
        }

        if (failLogWriter != null) {
            try {
                failLogWriter.flush();
            } catch (IOException e) {
                MeteorClient.LOG.error("Failed to flush dial failure log", e);
            }
        }
    }

    private void closeFailLog() {
        if (failLogWriter != null) {
            try {
                failLogWriter.close();
            } catch (IOException e) {
                MeteorClient.LOG.error("Failed to close dial failure log", e);
            }
            failLogWriter = null;
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private enum State {
        Idle,
        WaitingDelay,
        Watching
    }

    private static class QueuedMarker {
        String indexKey;
        String dial;
        String label;
        String desc;
    }

    private static class WarpFile {
        String group;
        Map<String, Marker> markers;
    }

    private static class Marker {
        String label;
        String desc;
        String x;
        String y;
        String z;
        String dial;
    }
}
