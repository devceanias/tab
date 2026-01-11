package me.neznamy.tab.shared.features.layout.impl;

import lombok.Getter;
import me.neznamy.tab.shared.ProtocolVersion;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.chat.component.TabComponent;
import me.neznamy.tab.shared.features.layout.LayoutConfiguration;
import me.neznamy.tab.shared.features.layout.LayoutManagerImpl;
import me.neznamy.tab.shared.features.layout.impl.common.FixedSlot;
import me.neznamy.tab.shared.features.layout.impl.common.PlayerGroup;
import me.neznamy.tab.shared.features.layout.impl.common.PlayerSlot;
import me.neznamy.tab.shared.features.layout.pattern.GroupPattern;
import me.neznamy.tab.shared.features.layout.pattern.LayoutPattern;
import me.neznamy.tab.shared.placeholders.conditions.Condition;
import me.neznamy.tab.shared.platform.TabList;
import me.neznamy.tab.shared.platform.TabPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Layout implementation using fake entries used to fill the tablist.
 * On <1.19.3, it sends all 80 slots, pushing real players out of the tablist.
 * On 1.19.3+, it hides real players using listed option, which means less than 80 slots are supported.
 */
@Getter
public class FakeEntryLayout extends LayoutBase {
    private static final int COLUMN_SIZE = 20;

    @NotNull
    private final List<Integer> emptySlots;

    @NotNull
    private final UUID[] allUUIDs;

    @NotNull
    private final Collection<FixedSlot> fixedSlots;

    @NotNull
    private final List<PlayerGroup> groups = new ArrayList<>();

    private final boolean dynamicColumns;
    private final int slotCount;
    private final int columnsCount;
    private final boolean[] activeColumns;
    private final int[] slotMapping;
    private final boolean hasColumnAfterFirst;

    /**
     * Constructs new instance with given parameters.
     *
     * @param   manager
     *          Layout manager
     * @param   pattern
     *          Layout pattern
     * @param   viewer
     *          Viewer of the layout
     */
    public FakeEntryLayout(
        @NotNull final LayoutManagerImpl manager, @NotNull final LayoutPattern pattern, @NotNull final TabPlayer viewer
    ) {
        super(manager, pattern, viewer);

        int configuredSlots = pattern.getSlotCount();

        final boolean supportsListed =
            viewer.getVersion().getNetworkId() >= ProtocolVersion.V1_19_3.getNetworkId() &&
            TAB.getInstance().getPlatform().supportsListed();

        if (!supportsListed) {
            configuredSlots = 80;
        }

        dynamicColumns =
            pattern.isDynamicColumns() &&
            supportsListed &&
            manager.getConfiguration().getDirection() == LayoutConfiguration.Direction.COLUMNS;

        slotCount = configuredSlots;
        columnsCount = (slotCount + COLUMN_SIZE - 1) / COLUMN_SIZE;

        if (dynamicColumns) {
            activeColumns = computeActive();
        } else {
            activeColumns = new boolean[columnsCount];

            Arrays.fill(activeColumns, true);
        }

        final int activeCount = countActive(activeColumns);

        hasColumnAfterFirst = activeCount > 1;
        slotMapping = buildMapping(activeColumns);

        final int effectiveSlots = dynamicColumns ? activeCount * COLUMN_SIZE : slotCount;
        final Collection<FixedSlot> visibleFixed = new ArrayList<>();

        emptySlots = IntStream.range(1, effectiveSlots + 1)
            .filter(this::shouldSendEmptySlot)
            .boxed()
            .collect(Collectors.toList());

        allUUIDs = Arrays.copyOf(manager.getUuids(), effectiveSlots);

        for (final FixedSlot slot : pattern.getFixedSlots().values()) {
            final int number = slot.getSlot();

            if (!isVisible(number)) {
                continue;
            }

            visibleFixed.add(slot);

            removeEmpty(number);
        }

        fixedSlots = visibleFixed;

        for (final GroupPattern group : pattern.getGroups()) {
            final int[] slots = group.getSlots();
            final int[] mapped = dynamicColumns ? mapSlots(slots) : slots;

            for (final int slot : mapped) {
                emptySlots.remove((Integer) slot);
            }

            if (mapped.length == 0) {
                continue;
            }

            groups.add(new PlayerGroup(this, new GroupPattern(group.getCondition(), mapped, group.isSpectator())));
        }
    }

    @Override
    public void send() {
        for (PlayerGroup group : groups) {
            group.sendAll();
        }
        for (FixedSlot slot : fixedSlots) {
            viewer.getTabList().addEntry(slot.createEntry(viewer));
        }

        if (shouldSendEmptyPlayers()) {
            final LayoutConfiguration configuration = manager.getConfiguration();
            final LayoutConfiguration.Direction direction = configuration.getDirection();

            for (final int slot : emptySlots) {
                viewer.getTabList().addEntry(new TabList.Entry(
                    manager.getUUID(slot),
                    direction.getEntryName(viewer, slot, LayoutManagerImpl.isTeamsEnabled()),
                    pattern.getDefaultSkin(slot),
                    true,
                    configuration.getEmptySlotPing(),
                    0,
                    TabComponent.empty(),
                    Integer.MAX_VALUE - direction.translateSlot(slot),
                    true
                ));
            }
        }

        tick();
        viewer.getTabList().hideAllPlayers();
    }

    @Override
    public void destroy() {
        for (UUID id : allUUIDs) {
            viewer.getTabList().removeEntry(id);
        }
        viewer.getTabList().showAllPlayers();
    }

    @Override
    public void tick() {
        List<TabPlayer> players = manager.getSortedPlayers().keySet().stream().filter(viewer::canSee).collect(Collectors.toList());
        for (PlayerGroup group : groups) {
            group.tick(players);
        }

        if (!dynamicColumns) {
            return;
        }

        if (Arrays.equals(activeColumns, computeActive())) {
            return;
        }

        manager.rebuildPlayerLayout(viewer);
    }

    @Override
    @Nullable
    public PlayerSlot getSlot(@NotNull TabPlayer target) {
        for (PlayerGroup group : groups) {
            if (group.getPlayers().containsKey(target)) {
                return group.getPlayers().get(target);
            }
        }
        return null;
    }

    @Override
    public int mapSlot(final int slot) {
        if (slot <= 0 || slot >= slotMapping.length) {
            return 0;
        }

        return slotMapping[slot];
    }

    @Override
    public boolean isVisible(final int slot) {
        if (!dynamicColumns) {
            return true;
        }

        return slot > 0 && slot < slotMapping.length && slotMapping[slot] > 0;
    }

    @Override
    public boolean shouldSendEmptySlot(final int slot) {
        if (!dynamicColumns) {
            return true;
        }

        if (slot <= 0) {
            return false;
        }

        final int column = (slot - 1) / COLUMN_SIZE;

        return column > 0 || hasColumnAfterFirst;
    }

    private void removeEmpty(final int slot) {
        final int mapped = mapSlot(slot);

        if (mapped <= 0) {
            return;
        }

        emptySlots.remove((Integer) mapped);
    }

    private int @NotNull [] mapSlots(final int @NotNull [] slots) {
        return Arrays.stream(slots).map(this::mapSlot).filter(slot -> slot > 0).toArray();
    }

    private boolean @NotNull [] computeActive() {
        final List<TabPlayer> remaining = manager.getSortedPlayers().keySet().stream()
            .filter(viewer::canSee)
            .collect(Collectors.toList());

        final boolean[] active = new boolean[columnsCount];

        for (final GroupPattern group : pattern.getGroups()) {
            final Condition condition = TAB.getInstance()
                .getPlaceholderManager()
                .getConditionManager()
                .getByNameOrExpression(group.getCondition());

            final List<TabPlayer> meeting = new ArrayList<>();

            remaining.removeIf(player -> {
                final boolean met = (condition == null || condition.isMet(viewer, player));

                if (met) {
                    meeting.add(player);
                }

                return met;
            });

            if (meeting.isEmpty()) {
                continue;
            }

            final int[] slots = group.getSlots();
            final int filled = Math.min(meeting.size(), slots.length);

            for (int i = 0; i < filled; i++) {
                final int column = (slots[i] - 1) / COLUMN_SIZE;

                if (column < 0 || column >= active.length) {
                    continue;
                }

                active[column] = true;
            }
        }

        return active;
    }

    private int @NotNull [] buildMapping(final boolean @NotNull [] currentActiveColumns) {
        final int[] slotMapping = new int[slotCount + 1];

        int targetColumn = 0;

        for (int column = 0; column < currentActiveColumns.length; column++) {
            if (!currentActiveColumns[column]) {
                continue;
            }

            for (int row = 0; row < COLUMN_SIZE; row++) {
                final int slot = column * COLUMN_SIZE + row + 1;

                if (slot > slotCount) {
                    continue;
                }

                slotMapping[slot] = targetColumn * COLUMN_SIZE + row + 1;
            }

            targetColumn++;
        }

        return slotMapping;
    }

    private int countActive(final boolean @NotNull [] columns) {
        int count = 0;

        for (final boolean active : columns) {
            if (!active) {
                continue;
            }

            count++;
        }

        return count;
    }
}