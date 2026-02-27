package com.example.addon.modules;

import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import com.example.addon.AddonTemplate;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.AbstractBlock;
import net.minecraft.util.shape.VoxelShape;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PortalLogger extends Module {
    
    // Settings
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    
    // General settings
    private final Setting<Boolean> chatMessages = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-messages")
        .description("Send messages in chat when you go through a portal")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> logToFile = sgGeneral.add(new BoolSetting.Builder()
        .name("log-to-file")
        .description("Save portal locations to a file")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> showCoordinates = sgGeneral.add(new BoolSetting.Builder()
        .name("show-coordinates")
        .description("Show coordinates in the log")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> showDimension = sgGeneral.add(new BoolSetting.Builder()
        .name("show-dimension")
        .description("Show which dimension you were in")
        .defaultValue(true)
        .build()
    );
    
    // Render settings
    private final Setting<Boolean> renderPortals = sgRender.add(new BoolSetting.Builder()
        .name("render-portals")
        .description("Render ESP on portals you've been through")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered")
        .defaultValue(ShapeMode.Both)
        .visible(renderPortals::get)
        .build()
    );
    
    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Color of the sides")
        .defaultValue(new SettingColor(0, 0, 0, 50)) // Black with transparency
        .visible(renderPortals::get)
        .build()
    );
    
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Color of the lines")
        .defaultValue(new SettingColor(0, 0, 0, 255)) // Solid black
        .visible(renderPortals::get)
        .build()
    );
    
    private final Setting<Integer> renderDistance = sgRender.add(new IntSetting.Builder()
        .name("render-distance")
        .description("Maximum distance to render portals")
        .defaultValue(64)
        .min(16)
        .max(256)
        .sliderRange(16, 256)
        .visible(renderPortals::get)
        .build()
    );
    
    private final Setting<Boolean> fadeDistance = sgRender.add(new BoolSetting.Builder()
        .name("fade-distance")
        .description("Make portals fade out based on distance")
        .defaultValue(true)
        .visible(renderPortals::get)
        .build()
    );
    
    private final Setting<Boolean> highlightUnused = sgRender.add(new BoolSetting.Builder()
        .name("highlight-unused")
        .description("Also highlight portals you haven't been through (different color)")
        .defaultValue(false)
        .visible(renderPortals::get)
        .build()
    );
    
    private final Setting<SettingColor> unusedSideColor = sgRender.add(new ColorSetting.Builder()
        .name("unused-side-color")
        .description("Color of the sides for unused portals")
        .defaultValue(new SettingColor(255, 0, 0, 30)) // Red with transparency
        .visible(() -> renderPortals.get() && highlightUnused.get())
        .build()
    );
    
    private final Setting<SettingColor> unusedLineColor = sgRender.add(new ColorSetting.Builder()
        .name("unused-line-color")
        .description("Color of the lines for unused portals")
        .defaultValue(new SettingColor(255, 0, 0, 100)) // Semi-transparent red
        .visible(() -> renderPortals.get() && highlightUnused.get())
        .build()
    );
    
    // Portal tracking
    private static class PortalInfo {
        BlockPos pos;
        String dimension;
        long firstSeenTime;
        long lastUsedTime;
        int useCount;
        Set<String> linkedPortals; // Portals this one connects to
        
        PortalInfo(BlockPos pos, String dimension) {
            this.pos = pos;
            this.dimension = dimension;
            this.firstSeenTime = System.currentTimeMillis();
            this.lastUsedTime = firstSeenTime;
            this.useCount = 1;
            this.linkedPortals = new HashSet<>();
        }
    }
    
    // Store portals by dimension and position
    private Map<String, Map<BlockPos, PortalInfo>> portalDatabase = new ConcurrentHashMap<>();
    private Set<String> loggedPortals = new HashSet<>();
    
    // Tracking current portal entry
    private boolean wasInPortal = false;
    private BlockPos lastPortalPos = null;
    private String lastDimension = "";
    private PortalInfo currentPortal = null;
    
    private File logFile;
    private File databaseFile;
    
    public PortalLogger() {
        super(AddonTemplate.CATEGORY, "portal-logger", "Logs portals you've been through and highlights them with ESP.");
        
        // Create files in the minecraft directory
        logFile = new File("portal_log.txt");
        databaseFile = new File("portal_database.txt");
    }
    
    @Override
    public void onActivate() {
        wasInPortal = false;
        lastPortalPos = null;
        currentPortal = null;
        loadPortalDatabase();
        
        info("Portal Logger activated! Portals you've been through will appear black.");
        
        if (logToFile.get()) {
            try {
                if (!logFile.exists()) {
                    logFile.createNewFile();
                    try (FileWriter writer = new FileWriter(logFile, true)) {
                        writer.write("=== Portal Log Started at " + getCurrentTimestamp() + " ===\n");
                        writer.write("Format: [Timestamp] Dimension: (X, Y, Z) -> Destination\n\n");
                    }
                }
            } catch (IOException e) {
                error("Could not create log file: " + e.getMessage());
            }
        }
    }
    
    @Override
    public void onDeactivate() {
        savePortalDatabase();
        info("Portal Logger deactivated!");
    }
    
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        
        // Check if player is in a portal
        boolean inPortal = mc.player.hasVehicle() ? 
            mc.player.getVehicle().hasPortalCooldown() : 
            mc.player.hasPortalCooldown();
        
        // Check if we're standing in a portal block
        BlockPos playerPos = mc.player.getBlockPos();
        boolean standingInPortal = mc.world.getBlockState(playerPos).getBlock() instanceof NetherPortalBlock;
        
        // If we just entered a portal
        if ((inPortal || standingInPortal) && !wasInPortal) {
            handlePortalEntry(playerPos);
        }
        
        // If we just exited a portal (teleported)
        if (!inPortal && !standingInPortal && wasInPortal && lastPortalPos != null) {
            handlePortalExit();
        }
        
        wasInPortal = inPortal || standingInPortal;
    }
    
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderPortals.get() || mc.world == null || mc.player == null) return;
        
        // Get all portals in render distance
        BlockPos playerPos = mc.player.getBlockPos();
        
        for (Map.Entry<String, Map<BlockPos, PortalInfo>> dimensionEntry : portalDatabase.entrySet()) {
            String dimName = dimensionEntry.getKey();
            
            // Only render portals in current dimension
            if (!dimName.equals(getDimensionName())) continue;
            
            for (Map.Entry<BlockPos, PortalInfo> portalEntry : dimensionEntry.getValue().entrySet()) {
                BlockPos pos = portalEntry.getKey();
                PortalInfo info = portalEntry.getValue();
                
                // Check distance
                double distance = Math.sqrt(playerPos.getSquaredDistance(pos));
                if (distance > renderDistance.get()) continue;
                
                // Calculate alpha based on distance if fading is enabled
                int sideAlpha = sideColor.get().a;
                int lineAlpha = lineColor.get().a;
                
                if (fadeDistance.get()) {
                    double fadeFactor = 1.0 - (distance / renderDistance.get());
                    sideAlpha = (int) (sideAlpha * fadeFactor);
                    lineAlpha = (int) (lineAlpha * fadeFactor);
                }
                
                // Choose colors based on whether portal has been used
                SettingColor useSideColor = sideColor.get();
                SettingColor useLineColor = lineColor.get();
                
                if (highlightUnused.get() && info.useCount == 0) {
                    useSideColor = unusedSideColor.get();
                    useLineColor = unusedLineColor.get();
                }
                
                // Create a copy with calculated alpha
                SettingColor finalSideColor = new SettingColor(
                    useSideColor.r, useSideColor.g, useSideColor.b, sideAlpha
                );
                SettingColor finalLineColor = new SettingColor(
                    useLineColor.r, useLineColor.g, useLineColor.b, lineAlpha
                );
                
                // Render the portal block
                renderPortal(event, pos, finalSideColor, finalLineColor);
            }
        }
    }
    
    private void renderPortal(Render3DEvent event, BlockPos pos, SettingColor sideColor, SettingColor lineColor) {
        BlockState state = mc.world.getBlockState(pos);
        
        // If it's a portal block, get its shape
        if (state.getBlock() instanceof NetherPortalBlock) {
            VoxelShape shape = state.getOutlineShape(mc.world, pos);
            if (!shape.isEmpty()) {
                // Render each box in the shape
                List<Box> boxes = shape.getBoundingBoxes();
                for (Box box : boxes) {
                    event.renderer.box(
                        pos.getX() + box.minX, pos.getY() + box.minY, pos.getZ() + box.minZ,
                        pos.getX() + box.maxX, pos.getY() + box.maxY, pos.getZ() + box.maxZ,
                        sideColor, lineColor, shapeMode.get(), 0
                    );
                }
            } else {
                // Fallback to full block render
                event.renderer.box(pos, sideColor, lineColor, shapeMode.get(), 0);
            }
        }
    }
    
    private void handlePortalEntry(BlockPos pos) {
        lastPortalPos = pos;
        lastDimension = getDimensionName();
        
        // Get or create portal info
        PortalInfo info = getOrCreatePortal(pos, lastDimension);
        currentPortal = info;
        
        // Update last used time and count
        info.lastUsedTime = System.currentTimeMillis();
        info.useCount++;
        
        // Create a unique ID for this portal to avoid duplicate logs
        String portalId = lastDimension + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        
        // Check if we already logged this portal recently
        if (loggedPortals.contains(portalId)) {
            String message = buildLogMessage(pos, true);
            log(message, true);
        } else {
            loggedPortals.add(portalId);
            String message = buildLogMessage(pos, false);
            log(message, false);
        }
        
        // Save database after each use
        savePortalDatabase();
    }
    
    private void handlePortalExit() {
        if (mc.player == null || currentPortal == null) return;
        
        // After teleporting, log the destination
        String currentDim = getDimensionName();
        BlockPos currentPos = mc.player.getBlockPos();
        
        if (lastPortalPos != null && !lastDimension.equals(currentDim)) {
            String message = String.format("Teleported from %s at (%d, %d, %d) to %s at (%d, %d, %d)",
                lastDimension,
                lastPortalPos.getX(), lastPortalPos.getY(), lastPortalPos.getZ(),
                currentDim,
                currentPos.getX(), currentPos.getY(), currentPos.getZ()
            );
            
            log(message, false);
            
            // Link the portals
            if (currentPortal != null) {
                PortalInfo destPortal = getOrCreatePortal(currentPos, currentDim);
                currentPortal.linkedPortals.add(formatPortalId(currentPos, currentDim));
                destPortal.linkedPortals.add(formatPortalId(lastPortalPos, lastDimension));
            }
        }
        
        currentPortal = null;
        lastPortalPos = null;
        lastDimension = "";
    }
    
    private PortalInfo getOrCreatePortal(BlockPos pos, String dimension) {
        portalDatabase.putIfAbsent(dimension, new ConcurrentHashMap<>());
        Map<BlockPos, PortalInfo> dimPortals = portalDatabase.get(dimension);
        
        return dimPortals.computeIfAbsent(pos, k -> new PortalInfo(pos, dimension));
    }
    
    private String formatPortalId(BlockPos pos, String dimension) {
        return dimension + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
    
    private String buildLogMessage(BlockPos pos, boolean isRepeat) {
        StringBuilder message = new StringBuilder();
        
        message.append("[").append(getCurrentTimestamp()).append("] ");
        
        if (isRepeat) {
            message.append("(REPEAT) ");
        }
        
        message.append("Portal entered");
        
        if (showDimension.get()) {
            message.append(" in ").append(getDimensionName());
        }
        
        if (showCoordinates.get()) {
            message.append(" at (").append(pos.getX()).append(", ").append(pos.getY()).append(", ").append(pos.getZ()).append(")");
        }
        
        return message.toString();
    }
    
    private void log(String message, boolean isRepeat) {
        if (chatMessages.get() && (!isRepeat || isRepeat)) {
            ChatUtils.infoPrefix("Portal", message);
        }
        
        if (logToFile.get()) {
            try (FileWriter writer = new FileWriter(logFile, true)) {
                writer.write(message + "\n");
            } catch (IOException e) {
                error("Failed to write to log file: " + e.getMessage());
            }
        }
    }
    
    private String getDimensionName() {
        if (mc.world == null) return "Unknown";
        
        return switch (mc.world.getRegistryKey().getValue().toString()) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> "Unknown";
        };
    }
    
    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
    
    private void savePortalDatabase() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(databaseFile))) {
            for (Map.Entry<String, Map<BlockPos, PortalInfo>> dimensionEntry : portalDatabase.entrySet()) {
                String dimension = dimensionEntry.getKey();
                for (Map.Entry<BlockPos, PortalInfo> portalEntry : dimensionEntry.getValue().entrySet()) {
                    PortalInfo info = portalEntry.getValue();
                    writer.printf("%s;%d;%d;%d;%d;%d;%s%n",
                        dimension,
                        info.pos.getX(), info.pos.getY(), info.pos.getZ(),
                        info.firstSeenTime,
                        info.useCount,
                        String.join(",", info.linkedPortals)
                    );
                }
            }
        } catch (IOException e) {
            error("Failed to save portal database: " + e.getMessage());
        }
    }
    
    private void loadPortalDatabase() {
        portalDatabase.clear();
        
        if (!databaseFile.exists()) return;
        
        try (BufferedReader reader = new BufferedReader(new FileReader(databaseFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(";");
                if (parts.length >= 7) {
                    String dimension = parts[0];
                    int x = Integer.parseInt(parts[1]);
                    int y = Integer.parseInt(parts[2]);
                    int z = Integer.parseInt(parts[3]);
                    long firstSeen = Long.parseLong(parts[4]);
                    int useCount = Integer.parseInt(parts[5]);
                    String[] links = parts[6].split(",");
                    
                    BlockPos pos = new BlockPos(x, y, z);
                    PortalInfo info = new PortalInfo(pos, dimension);
                    info.firstSeenTime = firstSeen;
                    info.useCount = useCount;
                    info.lastUsedTime = firstSeen; // Approximate
                    
                    for (String link : links) {
                        if (!link.isEmpty()) {
                            info.linkedPortals.add(link);
                        }
                    }
                    
                    portalDatabase.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>())
                        .put(pos, info);
                }
            }
        } catch (IOException e) {
            error("Failed to load portal database: " + e.getMessage());
        }
    }
}
Add PortalLogger module with ESP blackout feature