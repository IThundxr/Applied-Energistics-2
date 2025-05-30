/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2015, AlgorithmX2, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.client.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Stopwatch;
import com.mojang.blaze3d.platform.InputConstants;

import org.jetbrains.annotations.MustBeInvokedByOverriders;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

import guideme.GuidesCommon;
import guideme.PageAnchor;
import guideme.color.SymbolicColor;
import guideme.compiler.IdUtils;
import guideme.document.DefaultStyles;
import guideme.indices.ItemIndex;
import guideme.style.ResolvedTextStyle;
import guideme.style.TextStyle;

import appeng.api.behaviors.ContainerItemStrategies;
import appeng.api.behaviors.EmptyingAction;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.parts.IPart;
import appeng.api.stacks.GenericStack;
import appeng.client.Point;
import appeng.client.gui.layout.SlotGridLayout;
import appeng.client.gui.style.BackgroundGenerator;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.SlotPosition;
import appeng.client.gui.style.Text;
import appeng.client.gui.style.TextAlignment;
import appeng.client.gui.widgets.ITickingWidget;
import appeng.client.gui.widgets.ITooltip;
import appeng.client.gui.widgets.OpenGuideButton;
import appeng.client.gui.widgets.VerticalButtonBar;
import appeng.core.AEConfig;
import appeng.core.AELog;
import appeng.core.AppEng;
import appeng.core.AppEngClient;
import appeng.core.localization.ButtonToolTips;
import appeng.core.localization.Tooltips;
import appeng.core.network.ServerboundPacket;
import appeng.core.network.serverbound.InventoryActionPacket;
import appeng.core.network.serverbound.SwapSlotsPacket;
import appeng.helpers.InventoryAction;
import appeng.items.tools.GuideItem;
import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantic;
import appeng.menu.SlotSemantics;
import appeng.menu.slot.AppEngSlot;
import appeng.menu.slot.CraftingTermSlot;
import appeng.menu.slot.DisabledSlot;
import appeng.menu.slot.FakeSlot;
import appeng.menu.slot.IOptionalSlot;
import appeng.menu.slot.ResizableSlot;
import appeng.util.ConfigMenuInventory;

public abstract class AEBaseScreen<T extends AEBaseMenu> extends AbstractContainerScreen<T> {
    private static final Logger LOG = LoggerFactory.getLogger(AEBaseScreen.class);

    private static final Point HIDDEN_SLOT_POS = new Point(-9999, -9999);

    /**
     * Commonly used id for text that is used to show the dialog title.
     */
    public static final String TEXT_ID_DIALOG_TITLE = "dialog_title";

    protected static final ResolvedTextStyle ERROR_TEXT_STYLE = TextStyle.builder()
            .color(SymbolicColor.ERROR_TEXT)
            .font(Minecraft.DEFAULT_FONT)
            .dropShadow(true)
            .build()
            .mergeWith(DefaultStyles.BASE_STYLE);

    private final VerticalButtonBar verticalToolbar;
    private final OpenGuideButton helpButton;

    // drag y
    private final Set<Slot> drag_click = new HashSet<>();
    private boolean disableShiftClick = false;
    private Stopwatch dbl_clickTimer = Stopwatch.createStarted();
    private ItemStack dbl_whichItem = ItemStack.EMPTY;
    private Slot bl_clicked;
    private boolean handlingRightClick;
    private final Map<String, TextOverride> textOverrides = new HashMap<>();
    private final Set<SlotSemantic> hiddenSlots = new HashSet<>();
    protected final WidgetContainer widgets;
    protected final ScreenStyle style;
    protected final AEConfig config = AEConfig.instance();
    /**
     * The positions of all slots when a subscreen is opened.
     */
    private final List<SavedSlotInfo> savedSlotInfos = new ArrayList<>();

    public AEBaseScreen(T menu, Inventory playerInventory, Component title, ScreenStyle style) {
        super(menu, playerInventory, title);

        // Pre-initialize these fields since they're used in our constructors, but Vanilla only initializes them
        // in the init method
        this.font = Minecraft.getInstance().font;

        this.style = Objects.requireNonNull(style, "style");
        this.widgets = new WidgetContainer(style);
        this.verticalToolbar = new VerticalButtonBar();
//        this.widgets.add("verticalToolbar", this.verticalToolbar = new VerticalButtonBar());

        // TODO (RID): Added a check if a Screen should have the Vertical Tool Bar. This was added to avoid rendering
        // the bar from the SkyChestScreen.
        if (shouldAddToolbar()) {
            this.widgets.add("verticalToolbar", this.verticalToolbar);
        }

        // Add a help-button to the vertical button bar
        this.helpButton = addToLeftToolbar(new OpenGuideButton(btn -> openHelp()));

        if (style.getGeneratedBackground() != null) {
            this.imageWidth = style.getGeneratedBackground().getWidth();
            this.imageHeight = style.getGeneratedBackground().getHeight();
        } else if (style.getBackground() != null) {
            this.imageWidth = style.getBackground().getSrcWidth();
            this.imageHeight = style.getBackground().getSrcHeight();
        }
    }

    @Override
    @MustBeInvokedByOverriders
    protected void init() {
        super.init();
        positionSlots();

        widgets.populateScreen(this::addRenderableWidget, getBounds(true), this);
    }

    protected boolean shouldAddToolbar() {
        return true; // Default behavior is to add the toolbar
    }

    private void positionSlots() {
        for (var entry : style.getSlots().entrySet()) {
            var semantic = SlotSemantics.getOrThrow(entry.getKey());

            // Do not position slots that are hidden
            if (hiddenSlots.contains(semantic)) {
                continue;
            }

            repositionSlots(semantic);
        }
    }

    private Point getSlotPosition(SlotPosition position, int semanticIndex) {
        Point pos = position.resolve(getBounds(false));

        SlotGridLayout grid = position.getGrid();
        if (grid != null) {
            pos = grid.getPosition(pos.getX(), pos.getY(), semanticIndex);
        }
        return pos;
    }

    public final void repositionSlots(SlotSemantic semantic) {
        var position = style.getSlots().get(semantic.id());

        if (position.isHidden()) {
            menu.hideSlot(semantic.id());
            setSlotsHidden(semantic, true);
            return;
        }

        var slots = menu.getSlots(semantic);
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);

            // Special case for slots with overridable width/height, which use widget styles instead of
            // semantic based slot positioning
            if (slot instanceof ResizableSlot resizableSlot) {
                var widgetStyle = style.getWidget(resizableSlot.getStyleId());
                var pos = widgetStyle.resolve(getBounds(false));
                slot.x = pos.getX();
                slot.y = pos.getY();
                resizableSlot.setWidth(widgetStyle.getWidth());
                resizableSlot.setHeight(widgetStyle.getHeight());
            } else {
                Point pos = getSlotPosition(position, i);

                slot.x = pos.getX();
                slot.y = pos.getY();
            }
        }
    }

    private Rect2i getBounds(boolean absolute) {
        if (absolute) {
            return new Rect2i(leftPos, topPos, imageWidth, imageHeight);
        } else {
            return new Rect2i(0, 0, imageWidth, imageHeight);
        }
    }

    private List<Slot> getInventorySlots() {
        return this.menu.slots;
    }

    /**
     * This method is called directly before rendering the screen, and should be used to perform layout, and other
     * rendering-related updates.
     */
    @MustBeInvokedByOverriders
    protected void updateBeforeRender() {
        helpButton.setVisibility(getHelpTopic() != null);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        this.updateBeforeRender();
        this.widgets.updateBeforeRender();

        super.render(guiGraphics, mouseX, mouseY, partialTicks);

        renderTooltips(guiGraphics, mouseX, mouseY);

        if (AEConfig.instance().isShowDebugGuiOverlays()) {
            // Show a green overlay on exclusion zones
            List<Rect2i> exclusionZones = getExclusionZones();
            for (Rect2i rectangle2d : exclusionZones) {
                fillRect(guiGraphics, rectangle2d, 0x7f00FF00);
            }

            guiGraphics.hLine(leftPos, leftPos + imageWidth - 1, topPos, 0xFFFFFFFF);
            guiGraphics.hLine(leftPos, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xFFFFFFFF);
            guiGraphics.vLine(leftPos, topPos, topPos + imageHeight, 0xFFFFFFFF);
            guiGraphics.vLine(leftPos + imageWidth - 1, topPos, topPos + imageHeight - 1, 0xFFFFFFFF);
        }
    }

    /**
     * Get the potential result of emptying an item for the purposes of setting a filter. Null even if the item could be
     * emptied, but the filter doesn't support the resulting key.
     */
    protected EmptyingAction getEmptyingAction(Slot slot, ItemStack carried) {
        if (!(slot instanceof AppEngSlot appEngSlot) || carried.isEmpty()) {
            return null;
        }

        if (!(appEngSlot.getInventory() instanceof ConfigMenuInventory configInv)) {
            return null;
        }

        // See if we should offer the left-/right-click differentiation for setting a different filter
        var emptyingAction = ContainerItemStrategies.getEmptyingAction(carried);
        if (emptyingAction != null) {
            var wrappedStack = GenericStack.wrapInItemStack(new GenericStack(emptyingAction.what(), 1));
            if (configInv.isItemValid(slot.slot, wrappedStack)) {
                return emptyingAction;
            }
        }

        return null;
    }

    private boolean renderEmptyingTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // See if we should offer the left-/right-click differentiation for setting a different filter
        var emptyingAction = getEmptyingAction(this.hoveredSlot, menu.getCarried());
        if (emptyingAction != null) {
            drawTooltip(
                    guiGraphics,
                    mouseX,
                    mouseY,
                    Tooltips.getEmptyingTooltip(ButtonToolTips.SetAction, menu.getCarried(), emptyingAction));
            return true;
        }

        return false;
    }

    /**
     * Renders a potential tooltip (from one of the possible tooltip sources)
     */
    private void renderTooltips(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (renderEmptyingTooltip(guiGraphics, mouseX, mouseY)) {
            return;
        } else if (this.hoveredSlot instanceof AppEngSlot appEngSlot) {
            var customTooltip = appEngSlot.getCustomTooltip(menu.getCarried());
            if (customTooltip != null) {
                drawTooltip(guiGraphics, mouseX, mouseY, customTooltip);
            }
        }

        this.renderTooltip(guiGraphics, mouseX, mouseY);

        // The line above should have render a tooltip if this condition is true, and no
        // additional tooltips should be shown
        if (this.hoveredSlot != null && this.hoveredSlot.hasItem()) {
            return;
        }

        for (var c : this.renderables) {
            if (c instanceof ITooltip tooltipWidget) {
                if (!tooltipWidget.isTooltipAreaVisible()) {
                    continue;
                }

                var area = tooltipWidget.getTooltipArea();
                if (mouseX >= area.getX() && mouseY >= area.getY() &&
                        mouseX < area.getX() + area.getWidth()
                        && mouseY < area.getY() + area.getHeight()) {
                    var tooltip = new Tooltip(tooltipWidget.getTooltipMessage());
                    if (!tooltip.getContent().isEmpty()) {
                        drawTooltipWithHeader(guiGraphics, tooltip, mouseX, mouseY);
                    }
                }
            }
        }

        // Widget-container uses screen-relative coordinates while the rest uses window-relative
        Tooltip tooltip = this.widgets.getTooltip(mouseX - leftPos, mouseY - topPos);
        if (tooltip != null) {
            drawTooltipWithHeader(guiGraphics, tooltip, mouseX, mouseY);
        }
    }

    private void drawTooltipWithHeader(GuiGraphics guiGraphics, Tooltip tooltip, int mouseX, int mouseY) {
        drawTooltipWithHeader(guiGraphics, mouseX, mouseY, tooltip.getContent());
    }

    /**
     * Draws a tooltip and word-wraps it to a maximum width.
     */
    public void drawTooltip(GuiGraphics guiGraphics, int x, int y, List<Component> lines) {
        if (lines.isEmpty()) {
            return;
        }

        // Max width should be half screen with some padding.
        // Vanilla will place the tooltip on the right or left of the cursor
        // automatically, but uses a 12px offset (we use 40px for some extra space)
        int maxWidth = width / 2 - 40;

        // Make the first line white
        // All lines after the first are colored gray
        List<FormattedCharSequence> styledLines = new ArrayList<>(lines.size());
        for (Component line : lines) {
            styledLines.addAll(ComponentRenderUtils.wrapComponents(line, maxWidth, font));
        }
        guiGraphics.renderTooltip(font, styledLines, x, y);

    }

    /**
     * Draws a tooltip and word-wraps it to a maximum width.
     */
    public void drawTooltipWithHeader(GuiGraphics guiGraphics, int x, int y, List<Component> lines) {
        if (lines.isEmpty()) {
            return;
        }

        var formattedLines = new ArrayList<Component>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            if (i == 0) {
                formattedLines.add(lines.get(i).copy().withStyle(s -> s.withColor(ChatFormatting.WHITE)));
            } else {
                formattedLines.add(lines.get(i).copy().withStyle(s -> {
                    if (s.getColor() != null) {
                        return s;
                    } else {
                        return s.withColor(ChatFormatting.GRAY);
                    }
                }));
            }
        }
        drawTooltip(guiGraphics, x, y, formattedLines);
    }

    @Override
    protected final void renderLabels(GuiGraphics guiGraphics, int x, int y) {
        final int ox = this.leftPos;
        final int oy = this.topPos;

        widgets.drawForegroundLayer(guiGraphics, getBounds(false), new Point(x - ox, y - oy));

        this.drawFG(guiGraphics, ox, oy, x, y);

        if (style != null) {
            for (var entry : style.getText().entrySet()) {
                // Process text overrides
                var override = textOverrides.get(entry.getKey());
                drawText(guiGraphics, entry.getValue(), override);
            }
        }
    }

    private void drawText(GuiGraphics guiGraphics, Text text, @Nullable TextOverride override) {
        // Don't draw if the screen decided to hide this
        if (override != null && override.isHidden()) {
            return;
        }

        int color = style.getColor(text.getColor()).toARGB();

        Point pos = text.getPosition().resolve(getBounds(false));

        float scale = text.getScale();

        // Allow overrides for which content is shown
        Component content = text.getText();
        if (override != null && override.getContent() != null) {
            content = override.getContent().copy().withStyle(content.getStyle());
        }

        // Account for max width and split into lines
        List<FormattedCharSequence> lines;
        if (text.getMaxWidth() <= 0) {
            var line = content.getVisualOrderText();
            lines = List.of(line);
        } else {
            lines = this.font.split(content, text.getMaxWidth());
        }

        int y = pos.getY();
        for (var line : lines) {
            int lineWidth = this.font.width(line);
            int x = pos.getX();
            if (text.getAlign() == TextAlignment.CENTER) {
                int textWidth = Math.round(lineWidth * scale);
                x -= textWidth / 2;
            } else if (text.getAlign() == TextAlignment.RIGHT) {
                int textWidth = Math.round(lineWidth * scale);
                x -= textWidth;
            }

            if (text.getScale() == 1) {
                guiGraphics.drawString(font, line, x, y, color, false);
            } else {
                guiGraphics.pose().pushPose();
                guiGraphics.pose().translate(x, y, 1);
                guiGraphics.pose().scale(scale, scale, 1);
                guiGraphics.drawString(
                        font,
                        line,
                        0,
                        0,
                        color,
                        false);
                guiGraphics.pose().popPose();
            }
            y += text.getScale() * this.font.lineHeight;
        }
    }

    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
    }

    @Override
    protected final void renderBg(GuiGraphics guiGraphics, float f, int x,
            int y) {

        this.drawBG(guiGraphics, leftPos, topPos, x, y, f);

        widgets.drawBackgroundLayer(guiGraphics, getBounds(true), new Point(x - leftPos, y - topPos));

        for (Slot slot : this.getInventorySlots()) {
            if (slot instanceof IOptionalSlot) {
                drawOptionalSlotBackground(guiGraphics, (IOptionalSlot) slot, false);
            }
        }
    }

    private void drawOptionalSlotBackground(GuiGraphics guiGraphics, IOptionalSlot slot, boolean alwaysDraw) {
        // If a slot is optional and doesn't currently render, we still need to provide a background for it
        if (alwaysDraw || slot.isRenderDisabled()) {
            // If the slot is disabled, shade the background overlay
            float alpha = slot.isSlotEnabled() ? 1.0f : 0.2f;

            Point pos = slot.getBackgroundPos();

            Icon.SLOT_BACKGROUND.getBlitter()
                    .dest(leftPos + pos.getX(), topPos + pos.getY())
                    .color(1, 1, 1, alpha)
                    .blit(guiGraphics);
        }
    }

    // Convert global mouse x,y to relative Point
    private Point getMousePoint(double x, double y) {
        return new Point((int) Math.round(x - leftPos), (int) Math.round(y - topPos));
    }

    private boolean focusChangedToSomething = false;

    @Override
    public void setFocused(@Nullable GuiEventListener listener) {
        if (listener != null) {
            this.focusChangedToSomething = true;
        }
        super.setFocused(listener);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double deltaX, double deltaY) {
        if (deltaY != 0 && widgets.onMouseWheel(getMousePoint(x, y), deltaY)) {
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double xCoord, double yCoord, int btn) {
        this.drag_click.clear();

        // Forward right-clicks as-if they were left-clicks
        if (btn == 1) {
            handlingRightClick = true;
            try {
                for (var widget : this.children()) {
                    if (widget.isMouseOver(xCoord, yCoord)) {
                        return super.mouseClicked(xCoord, yCoord, 0);
                    }
                }
            } finally {
                handlingRightClick = false;
            }
        }

        if (widgets.onMouseDown(getMousePoint(xCoord, yCoord), btn)) {
            return true;
        }

        // super.mouseClicked will always return true, so we try to capture if
        // anything received focus due to the mouse click (see setFocused override)
        focusChangedToSomething = false;
        var result = super.mouseClicked(xCoord, yCoord, btn);
        if (!focusChangedToSomething) {
            var currentFocus = getCurrentFocusPath();
            if (currentFocus != null) {
                currentFocus.applyFocus(false);
            }
        }
        return result;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (widgets.onMouseUp(getMousePoint(mouseX, mouseY), button)) {
            return true;
        }

        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int mouseButton, double dragX, double dragY) {
        final Slot slot = this.findSlot(mouseX, mouseY);
        var itemstack = getMenu().getCarried();

        Point mousePos = new Point((int) Math.round(mouseX - leftPos), (int) Math.round(mouseY - topPos));
        if (widgets.onMouseDrag(mousePos, mouseButton)) {
            return true;
        }

        if (slot instanceof FakeSlot && !itemstack.isEmpty()) {
            this.drag_click.add(slot);
            if (this.drag_click.size() > 1) {
                for (Slot dr : this.drag_click) {
                    var p = new InventoryActionPacket(
                            mouseButton == 0 ? InventoryAction.PICKUP_OR_SET_DOWN : InventoryAction.PLACE_SINGLE,
                            dr.index, 0);
                    PacketDistributor.sendToServer(p);
                }
            }

            return true;
        } else {
            return super.mouseDragged(mouseX, mouseY, mouseButton, dragX, dragY);
        }
    }

    @Override
    protected void slotClicked(@Nullable Slot slot, int slotIdx, int mouseButton, ClickType clickType) {

        // Do not further process clicks on client-only slots
        if (getMenu().isClientSideSlot(slot)) {
            return;
        }

        // Do not allow clicks on disabled player inventory slots
        if (slot instanceof DisabledSlot) {
            return;
        }

        // Prevent cloning of wrapped itemstacks
        if (clickType == ClickType.CLONE && slot != null && GenericStack.isWrapped(slot.getItem())) {
            return;
        }

        if (this.drag_click.size() <= 1
                && mouseButton == InputConstants.MOUSE_BUTTON_RIGHT
                && getEmptyingAction(slot, menu.getCarried()) != null) {
            var p = new InventoryActionPacket(InventoryAction.EMPTY_ITEM, slotIdx, 0);
            PacketDistributor.sendToServer(p);
            return;
        }

        if (slot instanceof FakeSlot) {
            if (this.drag_click.size() > 1) {
                return;
            }

            var action = mouseButton == 1 ? InventoryAction.SPLIT_OR_PLACE_SINGLE
                    : InventoryAction.PICKUP_OR_SET_DOWN;
            var p = new InventoryActionPacket(action, slotIdx, 0);
            PacketDistributor.sendToServer(p);
            return;
        }

        if (slot instanceof CraftingTermSlot) {
            InventoryAction action;
            if (hasShiftDown()) {
                action = InventoryAction.CRAFT_SHIFT;
            } else if (InputConstants.isKeyDown(getMinecraft().getWindow().getWindow(), GLFW.GLFW_KEY_SPACE)) {
                action = InventoryAction.CRAFT_ALL;
            } else {
                // Craft stack on right-click, craft single on left-click
                action = mouseButton == 1 ? InventoryAction.CRAFT_STACK : InventoryAction.CRAFT_ITEM;
            }

            final InventoryActionPacket p = new InventoryActionPacket(action, slotIdx, 0);
            PacketDistributor.sendToServer(p);

            return;
        }

        if (slot != null && InputConstants.isKeyDown(getMinecraft().getWindow().getWindow(), GLFW.GLFW_KEY_SPACE)) {
            int slotNum = slot.index;
            final InventoryActionPacket p = new InventoryActionPacket(InventoryAction.MOVE_REGION, slotNum, 0);
            PacketDistributor.sendToServer(p);
            return;
        }

        if (slot != null && !this.disableShiftClick && hasShiftDown() && mouseButton == 0) {
            this.disableShiftClick = true;

            if (this.dbl_whichItem.isEmpty() || this.bl_clicked != slot
                    || this.dbl_clickTimer.elapsed(TimeUnit.MILLISECONDS) > 250) {
                // some simple double click logic.
                this.bl_clicked = slot;
                this.dbl_clickTimer = Stopwatch.createStarted();
                this.dbl_whichItem = slot.hasItem() ? slot.getItem().copy() : ItemStack.EMPTY;
            } else if (!this.dbl_whichItem.isEmpty()) {
                // a replica of the weird broken vanilla feature.

                final List<Slot> slots = this.getInventorySlots();
                for (Slot inventorySlot : slots) {
                    if (inventorySlot != null && inventorySlot.mayPickup(getPlayer()) && inventorySlot.hasItem()
                            && isSameInventory(inventorySlot, slot)
                            && AbstractContainerMenu.canItemQuickReplace(inventorySlot, this.dbl_whichItem, true)) {
                        this.slotClicked(inventorySlot, inventorySlot.index, 0, ClickType.QUICK_MOVE);
                    }
                }
                this.dbl_whichItem = ItemStack.EMPTY;
            }

            this.disableShiftClick = false;
        }

        super.slotClicked(slot, slotIdx, mouseButton, clickType);
    }

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int screenX, int screenY, int button) {
        // Consider clicks within attached compound widgets as still inside the screen
        var mousePos = new Point((int) Math.round(mouseX - screenX), (int) Math.round(mouseY - screenY));
        if (widgets.hitTest(mousePos)) {
            return false;
        }

        return super.hasClickedOutside(mouseX, mouseY, screenX, screenY, button);
    }

    protected LocalPlayer getPlayer() {
        // Our UIs are usually not opened when not in-game, so this should not be a
        // problem
        return Objects.requireNonNull(getMinecraft().player);
    }

    @Override
    protected boolean checkHotbarKeyPressed(int keyCode, int scanCode) {
        final Slot theSlot = this.getSlotUnderMouse();

        if (getMenu().getCarried().isEmpty() && theSlot != null) {
            if (this.minecraft.options.keySwapOffhand.matches(keyCode, scanCode)) {
                this.slotClicked(theSlot, theSlot.index, Inventory.SLOT_OFFHAND, ClickType.SWAP);
                return true;
            }

            for (int j = 0; j < 9; ++j) {
                if (getMinecraft().options.keyHotbarSlots[j].matches(keyCode, scanCode)) {
                    final List<Slot> slots = this.getInventorySlots();
                    for (Slot s : slots) {
                        if (s.slot == j && s.container == this.menu.getPlayerInventory()
                                && !s.mayPickup(this.menu.getPlayerInventory().player)) {
                            return false;
                        }
                    }

                    if (theSlot.getMaxStackSize() == 64) {
                        this.slotClicked(theSlot, theSlot.index, j, ClickType.SWAP);
                        return true;
                    } else {
                        for (Slot s : slots) {
                            if (s.slot == j
                                    && s.container == this.menu.getPlayerInventory()) {
                                ServerboundPacket message = new SwapSlotsPacket(s.index, theSlot.index);
                                PacketDistributor.sendToServer(message);
                                return true;
                            }
                        }
                    }
                }
            }
        }

        return false;
    }

    @Override
    protected boolean isHovering(Slot slot, double x, double y) {
        if (slot instanceof ResizableSlot resizableSlot) {
            var width = resizableSlot.getWidth();
            var height = resizableSlot.getHeight();
            return this.isHovering(slot.x, slot.y, width, height, x, y);
        }
        return super.isHovering(slot, x, y);
    }

    public void drawBG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY,
            float partialTicks) {

        var generatedBackground = style.getGeneratedBackground();
        if (generatedBackground != null) {
            BackgroundGenerator.draw(
                    generatedBackground.getWidth(),
                    generatedBackground.getHeight(),
                    guiGraphics,
                    offsetX,
                    offsetY);
        }

        var background = style.getBackground();
        if (background != null) {
            background.dest(offsetX, offsetY).blit(guiGraphics);
        }

    }

    public void drawItem(GuiGraphics guiGraphics, int x, int y, ItemStack is) {
        guiGraphics.renderItem(is, x, y);
        guiGraphics.renderItemDecorations(font, is, x, y);
    }

    protected Component getGuiDisplayName(Component in) {
        return title.getString().isEmpty() ? in : title;
    }

    /**
     * This overrides the base-class method through some access transformer hackery...
     */
    @Override
    public void renderSlot(GuiGraphics guiGraphics, Slot s) {
        if (s instanceof AppEngSlot appEngSlot) {
            try {
                renderAppEngSlot(guiGraphics, appEngSlot);
            } catch (Exception err) {
                AELog.warn("[AppEng] AE prevented crash while drawing slot: " + err);
            }
        } else {
            super.renderSlot(guiGraphics, s);
        }
    }

    private void renderAppEngSlot(GuiGraphics guiGraphics, AppEngSlot s) {
        var is = s.getItem();

        // If the slot has a background icon, render it, but only if the slot is empty
        // or it requests the icon to be always drawn
        if ((s.renderIconWithItem() || is.isEmpty()) && s.isSlotEnabled() && s.getIcon() != null) {
            s.getIcon().getBlitter()
                    .dest(s.x, s.y)
                    .opacity(s.getOpacityOfIcon())
                    .blit(guiGraphics);
        }

        // Draw a red background for slots that are in an invalid state
        if (!s.isValid()) {
            guiGraphics.fill(s.x, s.y, 16 + s.x, 16 + s.y, 0x66ff6666);
        }

        super.renderSlot(guiGraphics, s);
    }

    @Override
    public void containerTick() {
        super.containerTick();

        widgets.tick();

        for (var child : children()) {
            if (child instanceof ITickingWidget) {
                ((ITickingWidget) child).tick();
            }
        }
    }

    /**
     * Returns true while the current event being handled is a click of the right mouse button.
     */
    public boolean isHandlingRightClick() {
        return handlingRightClick;
    }

    /**
     * Adds a button to the vertical toolbar to the left of the screen and returns that button to the caller. The button
     * will automatically be positioned. This button will automatically be re-added to the screen when it's resized.
     */
    protected final <B extends Button> B addToLeftToolbar(B button) {
        verticalToolbar.add(button);
        return button;
    }

    /**
     * Returns rectangles in UI-space that define areas of the screen occluded by this GUI, in addition to the rectangle
     * defined by [guiLeft, guiTop, xSize, ySize], which is assumed to be occluded. This is used for moving JEI items
     * out of the way.
     */
    public List<Rect2i> getExclusionZones() {
        List<Rect2i> result = new ArrayList<>(2);
        widgets.addExclusionZones(result, getBounds(true));
        return result;
    }

    protected void fillRect(GuiGraphics guiGraphics, Rect2i rect, int color) {
        guiGraphics.fill(rect.getX(), rect.getY(), rect.getX() + rect.getWidth(), rect.getY() + rect.getHeight(),
                color);
    }

    private TextOverride getOrCreateTextOverride(String id) {
        return textOverrides.computeIfAbsent(id, x -> new TextOverride());
    }

    /**
     * Hides (or shows) a text that is defined in this screen's style file.
     */
    protected final void setTextHidden(String id, boolean hidden) {
        getOrCreateTextOverride(id).setHidden(hidden);
    }

    /**
     * Hides (or shows) a group of slots based on semantic.
     */
    public final void setSlotsHidden(SlotSemantic semantic, boolean hidden) {
        if (hidden) {
            if (hiddenSlots.add(semantic)) {
                // This isn't the greatest tactic but allows us to do this for every slot-type.
                // This approach has been used to hide slots since 1.7
                for (Slot slot : menu.getSlots(semantic)) {
                    slot.x = HIDDEN_SLOT_POS.getX();
                    slot.y = HIDDEN_SLOT_POS.getY();
                }
            }
        } else if (hiddenSlots.remove(semantic) && style != null) {
            positionSlots();
        }
    }

    /**
     * Changes the text that will be displayed for a text defined in this screen's style file.
     */
    protected final void setTextContent(String id, Component content) {
        getOrCreateTextOverride(id).setContent(content);
    }

    public ScreenStyle getStyle() {
        return style;
    }

    /**
     * Return the key of the resource if there is one at the given window coordinates. This is used by JEI/REI to allow
     * the U and R hotkeys to work for ingredients (such as fluids) that are not stored in normal slots, and for
     * ingredients wrapped in {@link appeng.items.misc.WrappedGenericStack}.
     * <p/>
     * The given coordinates are in window space.
     */
    @Nullable
    public StackWithBounds getStackUnderMouse(double mouseX, double mouseY) {
        // First check the vanilla slots
        if (hoveredSlot != null) {
            return StackWithBounds.fromSlot(this, hoveredSlot);
        }

        return null;
    }

    public final int getGuiLeft() {
        return this.leftPos;
    }

    public final int getGuiTop() {
        return this.topPos;
    }

    public final Minecraft getMinecraft() {
        return minecraft;
    }

    public final Slot getSlotUnderMouse() {
        return hoveredSlot;
    }

    public static boolean isSameInventory(Slot a, Slot b) {
        if (a instanceof AppEngSlot appEngSlotA && b instanceof AppEngSlot appEngSlotB) {
            return appEngSlotA.container == appEngSlotB.container;
        }
        return a.container == b.container;
    }

    /**
     * Renders a highlight for the given slot to indicate the mouse is currently hovering over it.
     */
    protected void renderSlotHighlight(GuiGraphics guiGraphics, Slot slot, int mouseX, int mouseY, float partialTick) {
        if (!slot.isHighlightable()) {
            return;
        }

        int x = slot.x;
        int y = slot.y;
        int w, h;
        if (slot instanceof ResizableSlot resizableSlot) {
            w = resizableSlot.getWidth();
            h = resizableSlot.getHeight();
        } else {
            w = 16;
            h = 16;
        }

        // Same as the Vanilla method, just with dynamic width and height
        // Added a custom slot highlight effect - RID
        guiGraphics.hLine(x, x + w, y - 1, 0xFFdaffff);
        guiGraphics.hLine(x - 1, x + w, y + h, 0xFFdaffff);
        guiGraphics.vLine(x - 1, y - 2, y + h, 0xFFdaffff);
        guiGraphics.vLine(x + w, y - 2, y + h, 0xFFdaffff);
        guiGraphics.fillGradient(RenderType.guiOverlay(), x, y, x + w, y + h, 0x669cd3ff, 0x669cd3ff, 0);
    }

    public final void switchToScreen(AEBaseScreen<?> screen) {
        savedSlotInfos.clear();
        for (var slot : menu.slots) {
            savedSlotInfos.add(new SavedSlotInfo(slot));
            // Hide all slots and give the sub-screen a chance to reposition whatever slots it wants
            slot.x = HIDDEN_SLOT_POS.getX();
            slot.y = HIDDEN_SLOT_POS.getY();
        }

        minecraft.screen = null;
        minecraft.setScreen(screen);

        if (!screen.savedSlotInfos.isEmpty()) {
            // Restore slot state to that of the new screen
            for (var savedSlotInfo : screen.savedSlotInfos) {
                savedSlotInfo.restore();
            }
            screen.savedSlotInfos.clear();
        }
    }

    /**
     * Called when a sub-screen returns to this screen.
     */
    protected <P extends AEBaseScreen<T>> void onReturnFromSubScreen(AESubScreen<T, P> subScreen) {
    }

    record SavedSlotInfo(Slot slot, boolean active, int x, int y) {
        public SavedSlotInfo(Slot slot) {
            this(slot, slot.isActive(), slot.x, slot.y);
        }

        public void restore() {
            if (slot instanceof AppEngSlot appEngSlot) {
                appEngSlot.setActive(active);
            }
            slot.x = x;
            slot.y = y;
        }
    }

    protected void openHelp() {
        var topic = getHelpTopic();
        if (topic != null) {
            GuidesCommon.openGuide(getPlayer(), GuideItem.GUIDE_ID, topic);
        } else {
            LOG.warn("No topic assigned to screen {}, but button was clicked", this);
        }
    }

    @Nullable
    protected PageAnchor getHelpTopic() {
        // Help topic may be overridden via screen style
        String helpTopic = style.getHelpTopic();
        if (helpTopic != null) {
            var sep = helpTopic.indexOf('#');
            String fragment = null;
            if (sep != -1) {
                fragment = helpTopic.substring(sep + 1);
                helpTopic = helpTopic.substring(0, sep);
            }
            try {
                return new PageAnchor(IdUtils.resolveId(helpTopic, AppEng.MOD_ID), fragment);
            } catch (Exception e) {
                LOG.warn("Invalid helpTopic for screen {}: {}", this, helpTopic);
            }
        }

        // Try finding the help topic automatically via the guidebook item index
        var guide = AppEngClient.instance().getGuide();
        var itemIndex = guide.getIndex(ItemIndex.class);

        Object target = getMenu().getTarget();
        if (target instanceof BlockEntity be) {
            var block = be.getBlockState().getBlock();
            var blockId = BuiltInRegistries.BLOCK.getKey(block);
            return itemIndex.get(blockId);
        } else if (target instanceof IPart part) {
            var item = part.getPartItem().asItem();
            var itemId = BuiltInRegistries.ITEM.getKey(item);
            return itemIndex.get(itemId);
        } else if (target instanceof ItemMenuHost<?> menuHost) {
            var item = menuHost.getItem();
            var itemId = BuiltInRegistries.ITEM.getKey(item);
            return itemIndex.get(itemId);
        }
        return null;
    }
}
