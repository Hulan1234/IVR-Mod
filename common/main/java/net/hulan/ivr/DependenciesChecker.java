package net.hulan.ivr;

import dev.architectury.platform.Platform;

public class DependenciesChecker {

    public static volatile boolean msdExist;
    public static volatile boolean jcmExist;
    public static volatile boolean nteExist;
    private static volatile boolean existenceKnown;

    public static synchronized void refreshPresence() {
        boolean[] registryReady = {true};
        msdExist = probeMod("msd", msdExist, registryReady);
        jcmExist = probeMod("jsblolck", jcmExist, registryReady);
        nteExist = probeMod("mtrsteamloco", nteExist, registryReady);
        existenceKnown = registryReady[0];
    }

    public static boolean isMsdExist() {
        refreshPresence();
        return msdExist;
    }

    public static boolean isNteExist() {
        refreshPresence();
        return nteExist;
    }

    public static boolean isExistenceKnown() {
        return existenceKnown;
    }

    private static boolean probeMod(String modId, boolean previousValue, boolean[] registryReady) {
        try {
            return Platform.isModLoaded(modId);
        } catch (Throwable exception) {
            registryReady[0] = false;
            IVR.LOGGER.debug("Optional mod registry unavailable while probing {}", modId, exception);
            return previousValue;
        }
    }
}
