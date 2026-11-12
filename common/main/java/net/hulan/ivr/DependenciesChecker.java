package net.hulan.ivr;

import net.hulan.ksd.utils.Utilities;

public class DependenciesChecker {

    public static volatile boolean msdExist;
    public static volatile boolean jcmExist;
    public static volatile boolean nteExist;
    public static volatile boolean sodiumExist;
    public static volatile boolean embeddiumExist;
    public static volatile boolean irisExist;
    private static volatile boolean existenceKnown;

    public static synchronized void refreshPresence() {
        boolean[] registryReady = {true};
        msdExist = probeMod("msd", msdExist, registryReady);
        jcmExist = probeMod("jsblolck", jcmExist, registryReady);
        nteExist = probeMod("mtrsteamloco", nteExist, registryReady);
        sodiumExist = probeMod("sodium", sodiumExist, registryReady);
        embeddiumExist = probeMod("embeddium", embeddiumExist, registryReady);
        irisExist = probeMod("iris", irisExist, registryReady);
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

    public static boolean isSodiumExist() {
        refreshPresence();
        return sodiumExist;
    }

    public static boolean isIrisExist() {
        refreshPresence();
        return irisExist;
    }

    public static boolean isGpuMeshBackendBlocked() {
        refreshPresence();
        return !gpuMeshBackendAllowedState();
    }

    public static boolean isCooperativeGpuMeshBackend() {
        refreshPresence();
        return gpuMeshBackendAllowedState();
    }

    public static boolean isGpuMeshBackendAllowed() {
        refreshPresence();
        return gpuMeshBackendAllowedState();
    }

    public static String gpuMeshBackendStatus() {
        refreshPresence();
        StringBuilder status = new StringBuilder();
        if (sodiumExist) status.append("sodium,");
        if (embeddiumExist) status.append("embeddium,");
        if (irisExist) status.append("iris,");
        return status.isEmpty() ? "vanilla" : status.deleteCharAt(status.length() - 1).toString();
    }

    private static boolean probeMod(String modId,
                                    boolean previousValue, boolean[] registryReady) {
        try {
            return Utilities.hasModLoaded(modId);
        } catch (Throwable exception) {
            registryReady[0] = false;
            IVR.LOGGER.debug("Optional mod registry unavailable while probing {}", modId, exception);
            return previousValue;
        }
    }

    private static boolean gpuMeshBackendAllowedState() {
        return existenceKnown && !irisExist && (sodiumExist || embeddiumExist);
    }
}
