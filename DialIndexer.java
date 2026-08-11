/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DialIndexer extends Module {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File DIALS_DIR = new File(MeteorClient.FOLDER, "dials");
    private static final Pattern DIAL_NAME_PATTERN = Pattern.compile("^-(.+)-$");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    @SuppressWarnings("unused")
    private final Setting<Keybind> addBind = sgGeneral.add(new KeybindSetting.Builder()
        .name("add-bind")
        .description("Hotkey to index the sign you are looking at without clicking.")
        .defaultValue(Keybind.none())
        .action(this::addFromCrosshair)
        .build()
    );

    private final Setting<Boolean> cancelInteract = sgGeneral.add(new BoolSetting.Builder()
        .name("cancel-interact")
        .description("Cancel the sign click so the edit screen does not open.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> quiet = sgGeneral.add(new BoolSetting.Builder()
        .name("quiet")
        .description("Only log errors, not each successful add.")
        .defaultValue(false)
        .build()
    );

    private File outputFile;
    private final PointerBuffer filters;

    private WarpFile data;
    private int nextIndex = 1;

    private PendingDuplicate pending;
    private boolean wasSneaking;

    public DialIndexer() {
        super(Categories.Misc, "dial-indexer", "Indexes dial signs into a warps JSON in the dials folder.");

        DIALS_DIR.mkdirs();

        File defaultFile = new File(DIALS_DIR, "warps.json");
        if (defaultFile.exists()) outputFile = defaultFile;

        filters = BufferUtils.createPointerBuffer(1);
        ByteBuffer jsonFilter = MemoryUtil.memASCII("*.json");
        filters.put(jsonFilter);
        filters.rewind();
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WHorizontalList list = theme.horizontalList();

        WButton selectFile = list.add(theme.button("Select JSON")).widget();
        WLabel fileName = list.add(theme.label(outputFile != null && outputFile.exists() ? outputFile.getName() : "No file selected.")).widget();

        selectFile.action = () -> {
            DIALS_DIR.mkdirs();
            String path = TinyFileDialogs.tinyfd_openFileDialog(
                "Select dials JSON",
                DIALS_DIR.getAbsolutePath() + File.separator,
                filters,
                "JSON files",
                false
            );

            if (path != null) {
                outputFile = new File(path);
                fileName.set(outputFile.getName());
                clearPending();
                if (isActive() && !loadData()) {
                    error("Failed to load %s.", outputFile.getName());
                }
            }
        };

        return list;
    }

    @Override
    public String getInfoString() {
        if (!isActive() || data == null || data.markers == null) return null;
        if (pending != null) return "dup @" + pending.indexKey;
        return String.valueOf(data.markers.size());
    }

    @Override
    public void onActivate() {
        DIALS_DIR.mkdirs();
        clearPending();
        wasSneaking = false;

        if (outputFile == null) {
            error("No JSON selected. Put a file in meteor-client/dials/ and select it.");
            toggle();
            return;
        }

        if (!loadData()) {
            toggle();
        }
    }

    @Override
    public void onDeactivate() {
        if (data != null) saveData();
        data = null;
        clearPending();
        wasSneaking = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) {
            wasSneaking = false;
            return;
        }

        boolean sneaking = mc.player.isSneaking();

        if (pending != null && sneaking && !wasSneaking) {
            applyPendingUpdate();
        }

        wasSneaking = sneaking;
    }

    @EventHandler
    private void onInteractBlock(InteractBlockEvent event) {
        if (mc.world == null) return;

        BlockPos pos = event.result.getBlockPos();
        BlockEntity blockEntity = mc.world.getBlockEntity(pos);
        if (!(blockEntity instanceof SignBlockEntity sign)) return;

        if (tryAddSign(sign) && cancelInteract.get()) {
            event.cancel();
        }
    }

    private void addFromCrosshair() {
        if (mc.world == null || mc.crosshairTarget == null) return;
        if (!(mc.crosshairTarget instanceof BlockHitResult hit)) return;
        if (hit.getType() == HitResult.Type.MISS) return;

        BlockEntity blockEntity = mc.world.getBlockEntity(hit.getBlockPos());
        if (!(blockEntity instanceof SignBlockEntity sign)) {
            warning("Look at a dial sign to index it.");
            return;
        }

        tryAddSign(sign);
    }

    private boolean tryAddSign(SignBlockEntity sign) {
        if (data == null || data.markers == null) {
            error("No JSON loaded.");
            return false;
        }

        BlockPos pos = sign.getPos();

        if (pending != null) {
            if (pending.signPos.equals(pos)) {
                info("Cancelled update for dial \"%s\" at index %s.", pending.dialName, pending.indexKey);
                clearPending();
                return true;
            }

            info("Cancelled pending update for dial \"%s\".", pending.dialName);
            clearPending();
        }

        ParsedDial parsed = parseSign(sign);
        if (parsed == null) {
            warning("Sign is not a dial (expected line 2: -name-, line 3: O:owner).");
            return false;
        }

        String existingKey = findDialKey(parsed.name);
        if (existingKey != null) {
            pending = new PendingDuplicate(existingKey, parsed.name, pos.toImmutable(), parsed);
            wasSneaking = mc.player != null && mc.player.isSneaking();
            warning("Dial \"%s\" already exists at index %s. Crouch to update, or click the sign again to cancel.",
                parsed.name, existingKey);
            return true;
        }

        return addNewMarker(parsed, pos);
    }

    private void applyPendingUpdate() {
        if (pending == null || data == null || data.markers == null) {
            clearPending();
            return;
        }

        Marker marker = data.markers.get(pending.indexKey);
        if (marker == null) {
            marker = new Marker();
            data.markers.put(pending.indexKey, marker);
        }

        fillMarker(marker, pending.parsed, pending.signPos);

        if (!saveData()) {
            clearPending();
            return;
        }

        info("Updated [%s] dial=\"%s\" owner=\"%s\" at %d %d %d.",
            pending.indexKey,
            pending.parsed.name,
            marker.desc,
            pending.signPos.getX(),
            pending.signPos.getY(),
            pending.signPos.getZ()
        );

        clearPending();
    }

    private boolean addNewMarker(ParsedDial parsed, BlockPos pos) {
        Marker marker = new Marker();
        fillMarker(marker, parsed, pos);

        String key = String.valueOf(nextIndex++);
        data.markers.put(key, marker);

        if (!saveData()) return false;

        if (!quiet.get()) {
            info("Added [%s] dial=\"%s\" owner=\"%s\" at %d %d %d.",
                key, parsed.name, marker.desc, pos.getX(), pos.getY(), pos.getZ());
        }

        return true;
    }

    private static void fillMarker(Marker marker, ParsedDial parsed, BlockPos pos) {
        marker.label = parsed.name;
        marker.desc = parsed.owner != null ? parsed.owner : "";
        marker.x = Double.toString(pos.getX() + 0.5);
        marker.y = Integer.toString(pos.getY());
        marker.z = Double.toString(pos.getZ() + 0.5);
        marker.dial = parsed.name;
    }

    private ParsedDial parseSign(SignBlockEntity sign) {
        ParsedDial front = parseSignText(sign.getText(true));
        if (front != null) return front;
        return parseSignText(sign.getText(false));
    }

    private ParsedDial parseSignText(SignText text) {
        if (text == null) return null;

        String nameLine = text.getMessage(1, false).getString().trim();
        String ownerLine = text.getMessage(2, false).getString().trim();

        Matcher matcher = DIAL_NAME_PATTERN.matcher(nameLine);
        if (!matcher.matches()) return null;

        String name = matcher.group(1).trim();
        if (name.isEmpty()) return null;

        ParsedDial parsed = new ParsedDial();
        parsed.name = name;

        if (ownerLine.length() >= 2 && ownerLine.regionMatches(true, 0, "O:", 0, 2)) {
            parsed.owner = ownerLine.substring(2).trim();
        } else {
            parsed.owner = "";
        }

        return parsed;
    }

    private String findDialKey(String dial) {
        for (Map.Entry<String, Marker> entry : data.markers.entrySet()) {
            Marker marker = entry.getValue();
            if (marker != null && dial.equalsIgnoreCase(marker.dial)) return entry.getKey();
        }
        return null;
    }

    private void clearPending() {
        pending = null;
    }

    private boolean loadData() {
        if (outputFile == null) return false;

        if (!outputFile.exists()) {
            data = new WarpFile();
            data.group = stripExtension(outputFile.getName());
            data.markers = new LinkedHashMap<>();
            nextIndex = 1;
            return saveData();
        }

        try (Reader reader = new FileReader(outputFile, StandardCharsets.UTF_8)) {
            data = GSON.fromJson(reader, WarpFile.class);
        } catch (IOException e) {
            MeteorClient.LOG.error("Failed to read dials JSON", e);
            error("Failed to read %s.", outputFile.getName());
            data = null;
            return false;
        }

        if (data == null) data = new WarpFile();
        if (data.group == null || data.group.isBlank()) data.group = stripExtension(outputFile.getName());
        if (data.markers == null) data.markers = new LinkedHashMap<>();

        nextIndex = 1;
        for (String key : data.markers.keySet()) {
            try {
                nextIndex = Math.max(nextIndex, Integer.parseInt(key) + 1);
            } catch (NumberFormatException ignored) {
            }
        }

        info("Loaded %d markers from %s (next index %d).", data.markers.size(), outputFile.getName(), nextIndex);
        return true;
    }

    private boolean saveData() {
        if (outputFile == null || data == null) return false;

        try {
            File parent = outputFile.getParentFile();
            if (parent != null) parent.mkdirs();
            try (Writer writer = new FileWriter(outputFile, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
            return true;
        } catch (IOException e) {
            MeteorClient.LOG.error("Failed to write dials JSON", e);
            error("Failed to save %s.", outputFile.getName());
            return false;
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private record PendingDuplicate(String indexKey, String dialName, BlockPos signPos, ParsedDial parsed) {}

    private static class ParsedDial {
        String name;
        String owner;
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
