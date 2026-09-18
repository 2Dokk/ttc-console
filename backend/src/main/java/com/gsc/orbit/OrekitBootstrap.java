package com.gsc.orbit;

import java.io.File;
import org.orekit.data.DataContext;
import org.orekit.data.DataProvidersManager;
import org.orekit.data.DirectoryCrawler;

/** Registers the Orekit physical data directory (leap seconds, EOP, ephemerides) exactly once. */
public final class OrekitBootstrap {

    private static boolean loaded;

    private OrekitBootstrap() {
    }

    public static synchronized void ensureLoaded(String path) {
        if (loaded) {
            return;
        }
        File dir = new File(path);
        if (!dir.isDirectory()) {
            throw new IllegalStateException("Orekit data not found at " + dir.getAbsolutePath()
                    + " - run ./gradlew downloadOrekitData");
        }
        DataProvidersManager manager = DataContext.getDefault().getDataProvidersManager();
        manager.addProvider(new DirectoryCrawler(dir));
        loaded = true;
    }
}
