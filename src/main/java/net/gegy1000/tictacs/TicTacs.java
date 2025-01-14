package net.gegy1000.tictacs;

import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class TicTacs implements ModInitializer {
    public static final String ID = "tic_tacs";
    public static final Logger LOGGER = LogManager.getLogger(ID);

    public static final Identifier DEBUG_CHUNK_TICKETS = new Identifier(ID, "debug_chunk_tickets");

    @Override
    public void onInitialize() {
    }
}
