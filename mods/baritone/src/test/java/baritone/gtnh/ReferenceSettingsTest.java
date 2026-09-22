// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.Baritone;
import baritone.ForgePlanningTestRunner;
import baritone.api.utils.SettingsUtil;
import net.minecraft.client.Minecraft;
import org.junit.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Exercises the structured settings boundary without starting a Minecraft client. */
@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class ReferenceSettingsTest {
    private static Path dataDirectory;
    private static Baritone engine;

    @BeforeClass public static void bootstrap() throws Exception {
        cpw.mods.fml.common.Loader.injectData("7","99","40","1614","1.7.10","9.05",new java.io.File("."),List.of());
        net.minecraft.init.Bootstrap.func_151354_b();
        dataDirectory=Files.createTempDirectory("baritone-settings-test");
        installMinecraftDataDirectory(dataDirectory);
        engine=new Baritone();
    }

    @AfterClass public static void cleanup() throws Exception {
        if(dataDirectory!=null)try(var paths=Files.walk(dataDirectory)){
            paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(Exception error){throw new AssertionError(error);}});
        }
    }

    @Before public void defaults(){Baritone.settings().allSettings.forEach(setting->setting.reset());}

    @Test public void invalidLaterSettingLeavesEarlierScalarAndCollectionUntouched(){
        var settings=Baritone.settings();
        settings.allowInventory.value=false;
        SettingsUtil.parseAndApply(settings,"acceptablethrowawayitems","minecraft:dirt");
        String originalItems=SettingsUtil.settingValueToString(settings.acceptableThrowawayItems);
        Map<String,Object> values=new LinkedHashMap<>();
        values.put("allowInventory",true);
        values.put("acceptableThrowawayItems","minecraft:stone");
        values.put("allowParkour","not-a-boolean");
        assertThrows(IllegalArgumentException.class,()->ReferenceSettings.call(engine,Map.of("operation","set","values",values)));
        assertFalse(settings.allowInventory.value);
        assertEquals(originalItems,SettingsUtil.settingValueToString(settings.acceptableThrowawayItems));
    }

    @Test public void failedSaveLeavesRuntimeAndExistingSettingsFileUntouched() throws Exception {
        var settings=Baritone.settings();
        settings.allowInventory.value=false;
        Path settingsDirectory=dataDirectory.resolve("baritone");
        Files.writeString(settingsDirectory,"previous settings must survive");
        assertThrows(IllegalStateException.class,()->ReferenceSettings.call(engine,Map.of(
            "operation","set","values",Map.of("allowInventory",true),"save",true)));
        assertFalse(settings.allowInventory.value);
        assertEquals("previous settings must survive",Files.readString(settingsDirectory));
        assertFalse(Files.exists(settingsDirectory.resolve("settings.txt")));
    }

    @Test public void blockListsAreCheckedAndAnUnsupportedWaterFallIsRefusedOutLoud(){
        var settings=Baritone.settings();
        assertThrows(IllegalArgumentException.class,()->ReferenceSettings.call(engine,Map.of("operation","set","values",Map.of("hazards",List.of("nomod:quicksand")))));
        assertTrue(settings.hazards.value.contains("minecraft:web"));
        ReferenceSettings.call(engine,Map.of("operation","set","values",Map.of("hazards",List.of("minecraft:fire","minecraft:wool:14"))));
        assertEquals(List.of("minecraft:fire","minecraft:wool:14"),settings.hazards.value);
        var refused=assertThrows(IllegalArgumentException.class,()->ReferenceSettings.call(engine,Map.of("operation","set","values",Map.of("allowWaterBucketFall",true))));
        assertTrue(refused.getMessage().contains("provider"));
        assertFalse(settings.allowWaterBucketFall.value);
    }

    private static void installMinecraftDataDirectory(Path directory) throws Exception {
        Class<?> unsafeClass=Class.forName("sun.misc.Unsafe",true,ClassLoader.getSystemClassLoader());
        Field unsafeField=unsafeClass.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);Object unsafe=unsafeField.get(null);
        Minecraft minecraft=(Minecraft)unsafeClass.getMethod("allocateInstance",Class.class).invoke(unsafe,Minecraft.class);
        Field dataDirectoryField=Minecraft.class.getField("mcDataDir");
        long offset=((Number)unsafeClass.getMethod("objectFieldOffset",Field.class).invoke(unsafe,dataDirectoryField)).longValue();
        unsafeClass.getMethod("putObject",Object.class,long.class,Object.class).invoke(unsafe,minecraft,offset,directory.toFile());
        Field singleton=Minecraft.class.getDeclaredField("theMinecraft");
        singleton.setAccessible(true);singleton.set(null,minecraft);
    }
}
