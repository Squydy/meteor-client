/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;

import java.util.List;
import java.util.Random;

public class AntiAFK extends Module {
    private final SettingGroup sgActions = settings.createGroup("Actions");
    private final SettingGroup sgTimer = settings.createGroup("Timer");
    private final SettingGroup sgMessages = settings.createGroup("Messages");

    // Timer

    private final Setting<Boolean> useTimer = sgTimer.add(new BoolSetting.Builder()
        .name("timer")
        .description("Only run AntiAFK actions for a period of time, then wait, then run again.")
        .defaultValue(false)
        .onChanged(aBoolean -> resetCycle())
        .build()
    );

    private final Setting<Integer> startDelay = sgTimer.add(new IntSetting.Builder()
        .name("start-delay")
        .description("How long to wait (in minutes) before AntiAFK starts doing actions.")
        .defaultValue(0)
        .min(0)
        .sliderMax(60)
        .visible(useTimer::get)
        .onChanged(aInteger -> resetCycle())
        .build()
    );

    private final Setting<Boolean> randomStartDelay = sgTimer.add(new BoolSetting.Builder()
        .name("random-start-delay")
        .description("Randomize the start delay between a minimum and maximum.")
        .defaultValue(false)
        .visible(useTimer::get)
        .onChanged(aBoolean -> resetCycle())
        .build()
    );

    private final Setting<Integer> startDelayMin = sgTimer.add(new IntSetting.Builder()
        .name("start-delay-min")
        .description("Minimum randomized start delay in minutes.")
        .defaultValue(0)
        .min(0)
        .sliderMax(60)
        .visible(() -> useTimer.get() && randomStartDelay.get())
        .onChanged(aInteger -> resetCycle())
        .build()
    );

    private final Setting<Integer> startDelayMax = sgTimer.add(new IntSetting.Builder()
        .name("start-delay-max")
        .description("Maximum randomized start delay in minutes.")
        .defaultValue(5)
        .min(0)
        .sliderMax(120)
        .visible(() -> useTimer.get() && randomStartDelay.get())
        .onChanged(aInteger -> resetCycle())
        .build()
    );

    private final Setting<Integer> activeTime = sgTimer.add(new IntSetting.Builder()
        .name("active-time")
        .description("How long (in seconds) AntiAFK should do actions for once it starts.")
        .defaultValue(60)
        .min(1)
        .sliderMax(600)
        .visible(useTimer::get)
        .onChanged(aInteger -> resetCycle())
        .build()
    );

    private final Setting<Boolean> randomActiveTime = sgTimer.add(new BoolSetting.Builder()
        .name("random-active-time")
        .description("Randomize the active time between a minimum and maximum.")
        .defaultValue(false)
        .visible(useTimer::get)
        .onChanged(aBoolean -> resetCycle())
        .build()
    );

    private final Setting<Integer> activeTimeMin = sgTimer.add(new IntSetting.Builder()
        .name("active-time-min")
        .description("Minimum randomized active time in seconds.")
        .defaultValue(30)
        .min(1)
        .sliderMax(600)
        .visible(() -> useTimer.get() && randomActiveTime.get())
        .onChanged(aInteger -> resetCycle())
        .build()
    );

    private final Setting<Integer> activeTimeMax = sgTimer.add(new IntSetting.Builder()
        .name("active-time-max")
        .description("Maximum randomized active time in seconds.")
        .defaultValue(120)
        .min(1)
        .sliderMax(1200)
        .visible(() -> useTimer.get() && randomActiveTime.get())
        .onChanged(aInteger -> resetCycle())
        .build()
    );

    // Actions

    private final Setting<Boolean> jump = sgActions.add(new BoolSetting.Builder()
        .name("jump")
        .description("Jump randomly.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swing = sgActions.add(new BoolSetting.Builder()
        .name("swing")
        .description("Swings your hand.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> sneak = sgActions.add(new BoolSetting.Builder()
        .name("sneak")
        .description("Sneaks and unsneaks quickly.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> sneakTime = sgActions.add(new IntSetting.Builder()
        .name("sneak-time")
        .description("How many ticks to stay sneaked.")
        .defaultValue(5)
        .min(1)
        .sliderMin(1)
        .visible(sneak::get)
        .build()
    );

    private final Setting<Boolean> strafe = sgActions.add(new BoolSetting.Builder()
        .name("strafe")
        .description("Strafe right and left.")
        .defaultValue(false)
        .onChanged(aBoolean -> {
            strafeTimer = 0;
            direction = false;

            if (isActive()) {
                mc.options.leftKey.setPressed(false);
                mc.options.rightKey.setPressed(false);
            }
        })
        .build()
    );

    private final Setting<Boolean> spin = sgActions.add(new BoolSetting.Builder()
        .name("spin")
        .description("Spins the player in place.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SpinMode> spinMode = sgActions.add(new EnumSetting.Builder<SpinMode>()
        .name("spin-mode")
        .description("The method of rotating.")
        .defaultValue(SpinMode.Server)
        .visible(spin::get)
        .build()
    );

    private final Setting<Integer> spinSpeed = sgActions.add(new IntSetting.Builder()
        .name("speed")
        .description("The speed to spin you.")
        .defaultValue(7)
        .visible(spin::get)
        .build()
    );

    private final Setting<Integer> pitch = sgActions.add(new IntSetting.Builder()
        .name("pitch")
        .description("The pitch to send to the server.")
        .defaultValue(0)
        .range(-90, 90)
        .sliderRange(-90, 90)
        .visible(() -> spin.get() && spinMode.get() == SpinMode.Server)
        .build()
    );


    // Messages

    private final Setting<Boolean> sendMessages = sgMessages.add(new BoolSetting.Builder()
        .name("send-messages")
        .description("Sends messages to prevent getting kicked for AFK.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> randomMessage = sgMessages.add(new BoolSetting.Builder()
        .name("random")
        .description("Selects a random message from your message list.")
        .defaultValue(false)
        .visible(sendMessages::get)
        .build()
    );

    private final Setting<Integer> delay = sgMessages.add(new IntSetting.Builder()
        .name("delay")
        .description("The delay between specified messages in seconds.")
        .defaultValue(15)
        .min(0)
        .sliderMax(30)
        .visible(sendMessages::get)
        .build()
    );

    private final Setting<List<String>> messages = sgMessages.add(new StringListSetting.Builder()
        .name("messages")
        .description("The messages to choose from.")
        .defaultValue(
            "Meteor on top!",
            "Meteor on crack!"
        )
        .visible(sendMessages::get)
        .build()
    );

    public AntiAFK() {
        super(Categories.Player, "anti-afk", "Performs different actions to prevent getting kicked while AFK.");
    }

    private final Random random = new Random();
    private int messageTimer = 0;
    private int messageI = 0;
    private int sneakTimer = 0;
    private int strafeTimer = 0;
    private boolean direction = false;
    private float lastYaw;
    private int cycleWaitTimer = 0;
    private int cycleActiveTimer = 0;

    @Override
    public void onActivate() {
        if (sendMessages.get() && messages.get().isEmpty()) {
            warning("Message list is empty, disabling messages...");
            sendMessages.set(false);
        }

        lastYaw = mc.player.getYaw();
        messageTimer = delay.get() * 20;
        resetCycle();
    }

    @Override
    public void onDeactivate() {
        stopActionKeys();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Utils.canUpdate()) return;

        if (useTimer.get() && !updateCycle()) {
            stopActionKeys();
            return;
        }

        // Jump
        if (jump.get()) {
            if (mc.options.jumpKey.isPressed()) mc.options.jumpKey.setPressed(false);
            else if (random.nextInt(99) == 0) mc.options.jumpKey.setPressed(true);
        }

        // Swing
        if (swing.get() && random.nextInt(99) == 0) {
            mc.player.swingHand(mc.player.getActiveHand());
        }

        // Sneak
        if (sneak.get()) {
            if (sneakTimer++ >= sneakTime.get()) {
                mc.options.sneakKey.setPressed(false);
                if (random.nextInt(99) == 0) sneakTimer = 0; // Sneak after ~5 seconds
            } else mc.options.sneakKey.setPressed(true);
        }

        // Strafe
        if (strafe.get() && strafeTimer-- <= 0) {
            mc.options.leftKey.setPressed(!direction);
            mc.options.rightKey.setPressed(direction);
            direction = !direction;
            strafeTimer = 20;
        }

        // Spin
        if (spin.get()) {
            lastYaw += spinSpeed.get();
            switch (spinMode.get()) {
                case Client -> mc.player.setYaw(lastYaw);
                case Server -> Rotations.rotate(lastYaw, pitch.get(), -15);
            }
        }

        // Messages
        if (sendMessages.get() && !messages.get().isEmpty() && messageTimer-- <= 0) {
            if (randomMessage.get()) messageI = random.nextInt(messages.get().size());
            else if (++messageI >= messages.get().size()) messageI = 0;

            ChatUtils.sendPlayerMsg(messages.get().get(messageI));
            messageTimer = delay.get() * 20;
        }
    }

    private void resetCycle() {
        cycleWaitTimer = 0;
        cycleActiveTimer = 0;

        if (!isActive() || !useTimer.get()) return;

        cycleWaitTimer = minutesToTicks(getStartDelayMinutes());
        cycleActiveTimer = secondsToTicks(getActiveTimeSeconds());
        sneakTimer = 0;
        strafeTimer = 0;
        direction = false;
    }

    private boolean updateCycle() {
        if (cycleWaitTimer > 0) {
            cycleWaitTimer--;
            return false;
        }

        if (cycleActiveTimer > 0) {
            cycleActiveTimer--;
            return true;
        }

        cycleWaitTimer = minutesToTicks(getStartDelayMinutes());
        cycleActiveTimer = secondsToTicks(getActiveTimeSeconds());
        sneakTimer = 0;
        strafeTimer = 0;
        direction = false;
        return false;
    }

    private int getStartDelayMinutes() {
        if (!randomStartDelay.get()) return startDelay.get();

        int min = Math.min(startDelayMin.get(), startDelayMax.get());
        int max = Math.max(startDelayMin.get(), startDelayMax.get());
        if (max == min) return min;
        return min + random.nextInt(max - min + 1);
    }

    private int getActiveTimeSeconds() {
        if (!randomActiveTime.get()) return activeTime.get();

        int min = Math.min(activeTimeMin.get(), activeTimeMax.get());
        int max = Math.max(activeTimeMin.get(), activeTimeMax.get());
        if (max == min) return min;
        return min + random.nextInt(max - min + 1);
    }

    private int secondsToTicks(int seconds) {
        return seconds * 20;
    }

    private int minutesToTicks(int minutes) {
        return minutes * 60 * 20;
    }

    private void stopActionKeys() {
        // Only release keys this module may press, so we don't override normal movement inputs.
        if (jump.get()) mc.options.jumpKey.setPressed(false);
        if (sneak.get()) mc.options.sneakKey.setPressed(false);
        if (strafe.get()) {
            mc.options.leftKey.setPressed(false);
            mc.options.rightKey.setPressed(false);
        }
    }

    public enum SpinMode {
        Server,
        Client
    }
}
