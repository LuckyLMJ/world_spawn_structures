package luckylmj.world_spawn_structures;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.structure.template.PlacementSettings;
import net.minecraft.world.gen.structure.template.Template;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.util.ITeleporter;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent;

import org.apache.logging.log4j.Logger;

@Mod(modid = world_spawn_structures.MODID, name = world_spawn_structures.NAME, version = world_spawn_structures.VERSION)
@Mod.EventBusSubscriber(modid = world_spawn_structures.MODID)
public class world_spawn_structures {
    public static final String MODID = "world_spawn_structures";
    public static final String NAME = "World Spawn Structures";
    public static final String VERSION = "1.0";

    private static final String DATAID = MODID + "_spawn_structure";
    private static final Set<UUID> NEW_PLAYERS = new HashSet<UUID>();
    private static File structure_file;
    private static Logger logger;
    private static int spawn_dimension;
    private static boolean use_custom_coordinates;
    private static boolean spawn_exact;
    private static int spawn_x;
    private static int spawn_z;
    private static int structure_offset_x;
    private static int structure_offset_y;
    private static int structure_offset_z;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        File config_directory = new File(event.getModConfigurationDirectory(), MODID);

        Configuration config = new Configuration(new File(config_directory, "world_spawn_structures.cfg"));
        config.load();
        spawn_dimension = config.getInt("spawn_dimension", Configuration.CATEGORY_GENERAL, 0,
            Integer.MIN_VALUE, Integer.MAX_VALUE, "The world spawn point's dimension.");
        use_custom_coordinates = config.getBoolean("use_custom_coordinates", Configuration.CATEGORY_GENERAL, false,
            "If true, will set world spawn with spawn_x and spawn_z. If false, the spawn point will follow default behaviour.");
        spawn_exact = config.getBoolean("spawn_exact", Configuration.CATEGORY_GENERAL, true,
            "If true, will always spawn the player exactly at world spawn (inside the structure).");
        spawn_x = config.getInt("spawn_x", Configuration.CATEGORY_GENERAL, 0,
            Integer.MIN_VALUE, Integer.MAX_VALUE, "X coordinate for the world spawn point.");
        spawn_z = config.getInt("spawn_z", Configuration.CATEGORY_GENERAL, 0,
            Integer.MIN_VALUE, Integer.MAX_VALUE, "Z coordinate for the world spawn point.");
        structure_offset_x = config.getInt("structure_offset_x", Configuration.CATEGORY_GENERAL, 0,
            Integer.MIN_VALUE, Integer.MAX_VALUE, "X offset of the structure (for placing the player in a different part of the structure).");
        structure_offset_y = config.getInt("structure_offset_y", Configuration.CATEGORY_GENERAL, 0,
            Integer.MIN_VALUE, Integer.MAX_VALUE, "Y offset of the structure (for placing the player in a different part of the structure).");
        structure_offset_z = config.getInt("structure_offset_z", Configuration.CATEGORY_GENERAL, 0,
            Integer.MIN_VALUE, Integer.MAX_VALUE, "Z offset of the structure (for placing the player in a different part of the structure).");
        if (config.hasChanged()) {
            config.save();
        }

        structure_file = new File(config_directory, "spawn_structure.nbt");
        if (!structure_file.isFile()) {
            try (InputStream resource = world_spawn_structures.class.getResourceAsStream("/spawn_structure.nbt")) {
                if (resource == null) {
                    logger.error("Default structure is missing");
                } else {
                    Files.copy(resource, structure_file.toPath());
                }
            } catch (IOException exception) {
                logger.error("Could not copy default spawn structure to {}", structure_file, exception);
            }
        }
    }

    @SubscribeEvent
    public static void onWorldLoad(WorldEvent.Load event) {
        World world = event.getWorld();
        if (world.isRemote) {
            return;
        }

        if (world.provider.getDimension() != spawn_dimension) {
            return;
        }

        if (spawn_exact) {
            world.getGameRules().setOrCreateGameRule("spawnRadius", "0");
        }

        MapStorage storage = world.getPerWorldStorage();
        spawn_structure_data data = (spawn_structure_data) storage.getOrLoadData(spawn_structure_data.class, DATAID);

        if (data != null) {
            return;
        }

        if (structure_file == null || !structure_file.isFile()) {
            logger.warn("Spawn structure file not found: {}", structure_file);
            return;
        }

        try (FileInputStream input = new FileInputStream(structure_file)) {
            NBTTagCompound nbt = CompressedStreamTools.readCompressed(input);
            Template template = new Template();
            template.read(nbt);

            PlacementSettings settings = new PlacementSettings()
                .setMirror(Mirror.NONE)
                .setRotation(Rotation.NONE)
                .setIgnoreEntities(false)
                .setIgnoreStructureBlock(false);
            BlockPos old_spawn = world.getSpawnPoint();
            int target_x = use_custom_coordinates ? spawn_x : old_spawn.getX();
            int target_z = use_custom_coordinates ? spawn_z : old_spawn.getZ();
            int target_y = find_spawn_y(world, target_x, target_z, old_spawn.getY());

            BlockPos origin = new BlockPos(
                target_x - template.getSize().getX() / 2 + structure_offset_x,
                target_y + structure_offset_y - 1,
                target_z - template.getSize().getZ() / 2 + structure_offset_z
            );

            template.addBlocksToWorld(world, origin, settings);

            BlockPos spawn_position = new BlockPos(target_x, target_y, target_z);
            if (use_custom_coordinates) {
                world.getWorldInfo().setSpawn(spawn_position);
            }

            if (data == null) {
                data = new spawn_structure_data();
                storage.setData(DATAID, data);
            } // this isn't really necessary, because we're checking if it exists and not using this directly here, but...
            data.markDirty();
            logger.info("Placed spawn structure in dimension {} around {}", spawn_dimension, spawn_position);
        } catch (IOException | RuntimeException exception) {
            logger.error("Could not load or place spawn structure:", exception);
        }
    }

    private static int find_spawn_y(World world, int x, int z, int yHint) {
        for (int y = yHint; y < 255; y++) {
            BlockPos feet = new BlockPos(x, y, z);
            if (world.isAirBlock(feet) && world.isAirBlock(feet.up())) {
                return y;
            }
        }

        return yHint;
    }

    private static BlockPos get_spawn_pos(WorldServer world) {
        BlockPos worldSpawn = world.getSpawnPoint();
        int spawn_y = find_spawn_y(world, spawn_x, spawn_z, worldSpawn.getY());
        return new BlockPos(spawn_x, spawn_y, spawn_z);
    }

    @SubscribeEvent
    public static void onPlayerDataLoad(PlayerEvent.LoadFromFile event) {
        net.minecraft.entity.player.EntityPlayer player = event.getEntityPlayer();
        File playerData = new File(event.getPlayerDirectory(), player.getUniqueID().toString() + ".dat");
        boolean hasPlayerData = playerData.isFile();

        World world = player.world;
        MinecraftServer server = world.getMinecraftServer();
        if (!hasPlayerData && server != null && server.isSinglePlayer()
                && player.getName().equals(server.getServerOwner())) {
            NBTTagCompound savedPlayer = server.getWorld(0).getWorldInfo().getPlayerNBTTagCompound();
            hasPlayerData = savedPlayer != null;
        }

        UUID playerId = player.getUniqueID();
        if (hasPlayerData) {
            NEW_PLAYERS.remove(playerId);
            return;
        }

        NEW_PLAYERS.add(playerId);
        if (server == null) {
            return;
        }

        WorldServer target_world = server.getWorld(spawn_dimension);
        if (target_world == null) {
            logger.error("Configured spawn dimension {} could not be initialized", spawn_dimension);
            return;
        }

        BlockPos spawn = get_spawn_pos(target_world);
        player.dimension = spawn_dimension;
        player.setSpawnDimension(spawn_dimension);
        player.setSpawnChunk(spawn, true, spawn_dimension);
        if (use_custom_coordinates) {
            // target_world.getWorldInfo().setSpawn(spawn);
            player.setLocationAndAngles(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D, player.rotationYaw, player.rotationPitch);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerLoggedInEvent event) {
        if (!NEW_PLAYERS.remove(event.player.getUniqueID()) || !(event.player instanceof EntityPlayerMP)) {
            return;
        }

        EntityPlayerMP player = (EntityPlayerMP) event.player;
        MinecraftServer server = player.world.getMinecraftServer();
        WorldServer target_world = server.getWorld(spawn_dimension);
        if (target_world == null) {
            logger.error("Configured spawn dimension {} is not loaded!", spawn_dimension);
            return;
        }

        BlockPos spawn = get_spawn_pos(target_world);

        if (player.dimension != spawn_dimension) {
            player.setSpawnDimension(spawn_dimension);
            player.setSpawnChunk(spawn, true, spawn_dimension);

            ITeleporter direct_spawn = (destination, entity, yaw) -> entity.setLocationAndAngles(
                    spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
                    yaw, entity.rotationPitch);
            server.getPlayerList().transferPlayerToDimension(player, spawn_dimension, direct_spawn);
        }
        
        if (use_custom_coordinates) {
            player.setLocationAndAngles(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
                player.rotationYaw, player.rotationPitch);
            player.connection.setPlayerLocation(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
                player.rotationYaw, player.rotationPitch);
        }
        logger.info("Placed player {} at dimension {} position {}, {}, {}", player.getName(),
            spawn_dimension, player.posX, player.posY, player.posZ);
    }

    public static class spawn_structure_data extends WorldSavedData {
        private boolean generated;
        public spawn_structure_data() {
            super(DATAID);
        }

        public spawn_structure_data(String name) {
            super(name);
        }

        @Override
        public void readFromNBT(NBTTagCompound nbt) {
            generated = nbt.getBoolean("generated");
        }

        @Override
        public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
            nbt.setBoolean("generated", generated);
            return nbt;
        }
    }
}
