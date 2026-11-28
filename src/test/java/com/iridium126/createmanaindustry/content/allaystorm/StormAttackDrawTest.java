package com.iridium126.createmanaindustry.content.allaystorm;

import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StormAttackDrawTest {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3);

    private static List<UUID> due(StormAttackDraw draw, StormAttackKind kind, List<UUID> players, long tick) {
        return draw.due(kind, players, id -> id, tick, () -> 0.0, new StormAttackStats());
    }

    @Test void perPlayerColumnsAndRingsDrawImmediatelyButMajorsWaitOneInterval() {
        double columnInterval = ServerConfig.stormStarfallInterval, ringInterval = ServerConfig.stormRingInterval;
        double helixMin = ServerConfig.stormHelixIntervalMin, helixMax = ServerConfig.stormHelixIntervalMax;
        try {
            ServerConfig.stormStarfallInterval = .5;
            ServerConfig.stormRingInterval = .5;
            ServerConfig.stormHelixIntervalMin = ServerConfig.stormHelixIntervalMax = 30;
            var draw = new StormAttackDraw();
            assertEquals(List.of(A, B), due(draw, StormAttackKind.COLUMNS, List.of(A, B), 100));
            assertEquals(List.of(A, B), due(draw, StormAttackKind.RING, List.of(A, B), 100));
            assertEquals(List.of(), due(draw, StormAttackKind.COLUMNS, List.of(A, B), 109));
            assertEquals(List.of(B), due(draw, StormAttackKind.COLUMNS, List.of(B), 110));
            assertEquals(List.of(A), due(draw, StormAttackKind.COLUMNS, List.of(A), 110));
            assertEquals(List.of(), due(draw, StormAttackKind.HELIX, List.of(A), 100));
            assertEquals(List.of(), due(draw, StormAttackKind.HELIX, List.of(A), 699));
            assertEquals(List.of(A), due(draw, StormAttackKind.HELIX, List.of(A), 700));
        } finally {
            ServerConfig.stormStarfallInterval = columnInterval;
            ServerConfig.stormRingInterval = ringInterval;
            ServerConfig.stormHelixIntervalMin = helixMin;
            ServerConfig.stormHelixIntervalMax = helixMax;
        }
    }

    @Test void chanceFailureStillConsumesThePlayersOpportunity() {
        double chance = ServerConfig.stormStarfallChance, interval = ServerConfig.stormStarfallInterval;
        try {
            ServerConfig.stormStarfallChance = 0;
            ServerConfig.stormStarfallInterval = .5;
            var draw = new StormAttackDraw();
            var stats = new StormAttackStats();
            assertEquals(List.of(), draw.due(StormAttackKind.COLUMNS, List.of(A), id -> id, 100, () -> 0.0, stats));
            assertEquals(1, stats.get(StormAttackStats.Reason.DRAW_MISSED));
            ServerConfig.stormStarfallChance = 1;
            assertEquals(List.of(), due(draw, StormAttackKind.COLUMNS, List.of(A), 109));
            assertEquals(List.of(A), due(draw, StormAttackKind.COLUMNS, List.of(A), 110));
        } finally {
            ServerConfig.stormStarfallChance = chance;
            ServerConfig.stormStarfallInterval = interval;
        }
    }

    @Test void clocksAndCursorsArePerKindAndPerPlayer() {
        double ringChance = ServerConfig.stormRingChance, ringInterval = ServerConfig.stormRingInterval;
        try {
            ServerConfig.stormRingChance = 1;
            ServerConfig.stormRingInterval = .5;
            var draw = new StormAttackDraw();
            assertEquals(List.of(A, B), due(draw, StormAttackKind.RING, List.of(A, B), 100));
            assertEquals(List.of(), due(draw, StormAttackKind.RING, List.of(B), 109));
            assertEquals(List.of(A), due(draw, StormAttackKind.RING, List.of(A), 110));
            assertEquals(List.of(B), due(draw, StormAttackKind.RING, List.of(B), 110));
            assertEquals(List.of(B, C, A), due(draw, StormAttackKind.RING, List.of(A, B, C), 120));
            assertEquals(List.of(C, A, B), due(draw, StormAttackKind.RING, List.of(A, B, C), 130));
        } finally {
            ServerConfig.stormRingChance = ringChance;
            ServerConfig.stormRingInterval = ringInterval;
        }
    }

    @Test void drawOrderRotatesAllKindsOnlyWhenTwoCompete() {
        var draw = new StormAttackDraw();
        int columns = 1 << StormAttackKind.COLUMNS.ordinal();
        int ring = 1 << StormAttackKind.RING.ordinal();
        int helix = 1 << StormAttackKind.HELIX.ordinal();
        int dome = 1 << StormAttackKind.DOME.ordinal();
        assertEquals(0, draw.start(columns | helix));
        assertEquals(1, draw.start(ring | dome));
        assertEquals(2, draw.start(columns));
        assertEquals(2, draw.start(dome));
        assertEquals(2, draw.start(columns | ring));
        assertEquals(3, draw.start(helix | dome));
        assertEquals(0, draw.start(columns | helix));
        draw.reset();
        assertEquals(0, draw.start(columns | ring));
        assertEquals(StormAttackKind.COLUMNS, StormAttackKind.at(0));
        assertEquals(StormAttackKind.RING, StormAttackKind.at(1));
        assertEquals(StormAttackKind.HELIX, StormAttackKind.at(2));
        assertEquals(StormAttackKind.DOME, StormAttackKind.at(3));
        assertEquals(StormAttackKind.COLUMNS, StormAttackKind.at(4));
        assertEquals(StormAttackKind.DOME, StormAttackKind.at(-1));
    }

    @Test void resetAndForgetClearOnlyTheRequestedState() {
        double columnChance = ServerConfig.stormStarfallChance, interval = ServerConfig.stormStarfallInterval;
        try {
            ServerConfig.stormStarfallChance = 1;
            ServerConfig.stormStarfallInterval = .5;
            var draw = new StormAttackDraw();
            assertEquals(List.of(A), due(draw, StormAttackKind.COLUMNS, List.of(A), 100));
            assertEquals(List.of(), due(draw, StormAttackKind.COLUMNS, List.of(A), 101));
            draw.forget(A);
            assertEquals(List.of(A), due(draw, StormAttackKind.COLUMNS, List.of(A), 101));
            draw.reset();
            assertEquals(List.of(A), due(draw, StormAttackKind.COLUMNS, List.of(A), 102));
        } finally {
            ServerConfig.stormStarfallChance = columnChance;
            ServerConfig.stormStarfallInterval = interval;
        }
    }
}