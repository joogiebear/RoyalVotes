package com.mystipixel.royalvotes.hook;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Switches on libreforge's {@code register_vote} trigger and {@code vote_service} filter, which
 * EcoBattlepass's {@code vote_server} task is built from. libreforge only loads that integration when a
 * plugin really named "Votifier" is installed ({@code provides} does not count), so we call its own
 * {@code load} by reflection: libreforge is loaded at runtime and cannot be linked against.
 */
public final class LibreforgeHook {

    private static final String INTEGRATION = "com.willfp.libreforge.integrations.votifier.VotifierIntegration";

    private LibreforgeHook() {
    }

    public enum Result { HOOKED, ALREADY_LOADED, NOT_INSTALLED, FAILED }

    public static Result hook(Logger logger) {
        Plugin libreforge = Bukkit.getPluginManager().getPlugin("libreforge");
        if (libreforge == null) {
            return Result.NOT_INSTALLED;
        }
        try {
            ClassLoader loader = libreforge.getClass().getClassLoader();
            if (triggerExists(loader)) {
                return Result.ALREADY_LOADED;       // a second call, or a real Votifier got there first
            }
            Class<?> integration = Class.forName(INTEGRATION, true, loader);
            Object instance = integration.getField("INSTANCE").get(null);
            for (Method method : integration.getMethods()) {
                if (method.getName().equals("load") && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isInstance(libreforge)) {
                    method.invoke(instance, libreforge);
                    return Result.HOOKED;
                }
            }
            logger.warning("libreforge's Votifier integration has no load(plugin) method in this"
                    + " version; the register_vote trigger will not exist. Please report this.");
            return Result.FAILED;
        } catch (Throwable broken) {
            logger.warning("Could not enable libreforge's register_vote trigger (" + broken
                    + "). Vote rewards still work; EcoBattlepass vote tasks will not. Please report this.");
            return Result.FAILED;
        }
    }

    // Triggers.get("register_vote") != null, reflectively; a hit also enables it, which is wanted
    private static boolean triggerExists(ClassLoader loader) {
        try {
            Class<?> triggers = Class.forName("com.willfp.libreforge.triggers.Triggers", true, loader);
            Object instance = triggers.getField("INSTANCE").get(null);
            for (Method method : triggers.getMethods()) {
                if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == String.class
                        && (method.getName().equals("getByID") || method.getName().equals("get"))) {
                    return method.invoke(instance, "register_vote") != null;
                }
            }
        } catch (Throwable unknown) {
            // Not being able to ask is not a reason to skip the hook; registering twice is harmless.
        }
        return false;
    }
}
