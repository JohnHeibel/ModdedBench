// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone;

import net.minecraft.launchwrapper.LaunchClassLoader;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.InitializationError;
import java.io.File;
import java.net.URL;
import java.util.Arrays;

/** Boots real Forge registries under their required loader, without opening a game or replacing game APIs. */
public final class ForgePlanningTestRunner extends BlockJUnit4ClassRunner {
    public ForgePlanningTestRunner(Class<?> type)throws InitializationError{super(load(type));}
    private static Class<?> load(Class<?> type)throws InitializationError{
        try{
            URL[] urls=Arrays.stream(System.getProperty("modbench.test.classpath").split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .map(path->{try{return new File(path).toURI().toURL();}catch(Exception e){throw new IllegalStateException(e);}}).toArray(URL[]::new);
            LaunchClassLoader loader=new LaunchClassLoader(urls);
            loader.addClassLoaderExclusion("org.junit.");loader.addClassLoaderExclusion("org.hamcrest.");
            loader.addClassLoaderExclusion("baritone.ForgePlanningTestRunner");
            return loader.loadClass(type.getName());
        }catch(Exception error){throw new InitializationError(error);}
    }
}
