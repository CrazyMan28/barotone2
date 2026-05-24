/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.utils.SettingsUtil;
import baritone.command.defaults.UndercoverCommand;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Small in-game tuning screen for the undercover profile.
 */
public final class UndercoverSettingsScreen extends Screen {

    private final Screen parent;

    public UndercoverSettingsScreen(Screen parent) {
        super(Component.literal("Undercover Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Settings s = BaritoneAPI.getSettings();
        int panelWidth = Math.min(330, this.width - 32);
        int x = (this.width - panelWidth) / 2;
        int y = 42;
        int h = 20;
        int gap = 25;

        this.addRenderableWidget(new DoubleSlider(
                x, y, panelWidth, h,
                "Movement aggression", s.undercoverMovementAggression, 0D, 1D, "", 2));
        y += gap;
        this.addRenderableWidget(new DoubleSlider(
                x, y, panelWidth, h,
                "Yaw speed", s.undercoverLookYawSpeed, 1D, 25D, " deg/t", 1));
        y += gap;
        this.addRenderableWidget(new DoubleSlider(
                x, y, panelWidth, h,
                "Pitch speed", s.undercoverLookPitchSpeed, 1D, 20D, " deg/t", 1));
        y += gap;
        this.addRenderableWidget(new IntSlider(
                x, y, panelWidth, h,
                "Head smoothing", s.undercoverSmoothLookTicks, 1, 30, " ticks"));
        y += gap;
        this.addRenderableWidget(new IntSlider(
                x, y, panelWidth, h,
                "Right-click delay", s.undercoverRightClickDelay, 1, 30, " ticks"));
        y += gap;
        this.addRenderableWidget(new IntSlider(
                x, y, panelWidth, h,
                "Block-break delay", s.undercoverBlockBreakDelay, 1, 35, " ticks"));
        y += gap + 3;

        int half = (panelWidth - 6) / 2;
        this.addRenderableWidget(Button.builder(strictMessage(s), button -> {
            s.strictVisibleBlockInteractions.value = !s.strictVisibleBlockInteractions.value;
            saveAndApply(s);
            button.setMessage(strictMessage(s));
        }).bounds(x, y, half, h).build());
        this.addRenderableWidget(Button.builder(smoothMessage(s), button -> {
            s.smoothLook.value = !s.smoothLook.value;
            saveAndApply(s);
            button.setMessage(smoothMessage(s));
        }).bounds(x + half + 6, y, half, h).build());
        y += gap;

        this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> this.onClose())
                .bounds(x, y, panelWidth, h).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        int panelWidth = Math.min(350, this.width - 24);
        int panelHeight = 205;
        int x = (this.width - panelWidth) / 2;
        int y = 8;
        graphics.fill(x - 6, y - 4, x + panelWidth + 6, y + panelHeight, 0xAA101010);
        graphics.fill(x - 6, y - 4, x + panelWidth + 6, y - 3, 0xCC707070);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);
        graphics.drawCenteredString(
                this.font,
                Component.literal(UndercoverCommand.isEnabled() ? "Live profile is active" : "Applies next time you turn it on"),
                this.width / 2,
                29,
                0xA0A0A0);
        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        SettingsUtil.save(BaritoneAPI.getSettings());
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    private static Component strictMessage(Settings s) {
        return Component.literal("Visible guard: " + (s.strictVisibleBlockInteractions.value ? "ON" : "OFF"));
    }

    private static Component smoothMessage(Settings s) {
        return Component.literal("Smooth look: " + (s.smoothLook.value ? "ON" : "OFF"));
    }

    private static void saveAndApply(Settings s) {
        if (UndercoverCommand.isEnabled()) {
            UndercoverCommand.applyUndercoverProfile(s);
        }
        SettingsUtil.save(s);
    }

    private abstract static class BaseSlider extends AbstractSliderButton {

        BaseSlider(int x, int y, int width, int height, double value) {
            super(x, y, width, height, Component.empty(), value);
        }
    }

    private static final class DoubleSlider extends BaseSlider {

        private final String label;
        private final Settings.Setting<Double> setting;
        private final double min;
        private final double max;
        private final String suffix;
        private final int decimals;

        DoubleSlider(
                int x,
                int y,
                int width,
                int height,
                String label,
                Settings.Setting<Double> setting,
                double min,
                double max,
                String suffix,
                int decimals) {
            super(x, y, width, height, normalized(setting.value, min, max));
            this.label = label;
            this.setting = setting;
            this.min = min;
            this.max = max;
            this.suffix = suffix;
            this.decimals = decimals;
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            String format = "%." + decimals + "f";
            this.setMessage(Component.literal(label + ": "
                    + String.format(Locale.ROOT, format, setting.value) + suffix));
        }

        @Override
        protected void applyValue() {
            double raw = min + (max - min) * this.value;
            double scale = Math.pow(10D, decimals);
            setting.value = Math.round(raw * scale) / scale;
            saveAndApply(BaritoneAPI.getSettings());
            this.updateMessage();
        }
    }

    private static final class IntSlider extends BaseSlider {

        private final String label;
        private final Settings.Setting<Integer> setting;
        private final int min;
        private final int max;
        private final String suffix;

        IntSlider(
                int x,
                int y,
                int width,
                int height,
                String label,
                Settings.Setting<Integer> setting,
                int min,
                int max,
                String suffix) {
            super(x, y, width, height, normalized(setting.value, min, max));
            this.label = label;
            this.setting = setting;
            this.min = min;
            this.max = max;
            this.suffix = suffix;
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(label + ": " + setting.value + suffix));
        }

        @Override
        protected void applyValue() {
            setting.value = Math.max(min, Math.min(max, (int) Math.round(min + (max - min) * this.value)));
            saveAndApply(BaritoneAPI.getSettings());
            this.updateMessage();
        }
    }

    private static double normalized(double value, double min, double max) {
        if (max <= min) {
            return 0D;
        }
        return Math.max(0D, Math.min(1D, (value - min) / (max - min)));
    }
}
