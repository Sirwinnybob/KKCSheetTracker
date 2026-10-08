package com.kkc.sheettracker.update;

import java.io.File;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class UpdateDirectoriesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final String pkg = "com.example.first";
    private File dir(String path) {
        File file = new File(temporary.getRoot(), path);
        assertTrue(file.mkdirs() || file.isDirectory());
        return file;
    }
    private File apk(String path) throws IOException {
        File folder = dir(path);
        assertTrue(new File(folder, "release.apk").createNewFile());
        return folder;
    }
    @Test public void canonicalPackageWinsOverLegacySavedPath() throws Exception {
        File legacy = apk("Ready Jobs/.Updates");
        File canonical = apk("Ready Jobs/.appupdates/apps/" + pkg);
        assertEquals(canonical, UpdateDirectories.find(temporary.getRoot(), pkg, false, legacy.getPath()));
    }
    @Test public void customFeedRootAndSiblingPackageResolvePerApp() throws Exception {
        File own = apk("Ready Jobs/.appupdates/apps/" + pkg);
        File other = apk("Ready Jobs/.appupdates/apps/com.example.second");
        File feed = new File(temporary.getRoot(), "Ready Jobs/.appupdates");
        assertEquals(own, UpdateDirectories.find(temporary.getRoot(), pkg, false, feed.getPath()));
        assertEquals(other, UpdateDirectories.find(temporary.getRoot(), "com.example.second", false, own.getPath()));
    }
    @Test public void arbitraryCustomDirectoryRemainsAnOverride() throws Exception {
        File custom = apk("my-custom-feed");
        apk("Ready Jobs/.appupdates/apps/" + pkg);
        assertEquals(custom, UpdateDirectories.find(temporary.getRoot(), pkg, false, custom.getPath()));
    }
    @Test public void partialCanonicalSyncFallsBackToLegacy() throws Exception {
        File canonical = dir("Ready Jobs/.appupdates/apps/" + pkg);
        assertTrue(new File(canonical, "release.apk.tmp").createNewFile());
        File legacy = apk("Ready Jobs/.Updates");
        assertEquals(legacy, UpdateDirectories.find(temporary.getRoot(), pkg, false, null));
    }
    @Test public void missingPackageDoesNotUseAnotherAppsDirectory() throws Exception {
        apk("Ready Jobs/.appupdates/apps/com.example.second");
        assertNull(UpdateDirectories.find(temporary.getRoot(), pkg, false, null));
    }
    @Test public void debugBuildsKeepTheirSeparateFeed() throws Exception {
        apk("Ready Jobs/.appupdates/apps/" + pkg);
        apk("Ready Jobs/.Updates");
        File testing = apk("Ready Jobs/.Testing_Updates");
        assertEquals(testing, UpdateDirectories.find(temporary.getRoot(), pkg, true, null));
    }
    @Test public void emptyCanonicalFolderStillResolvesWithoutPrompting() {
        File empty = dir("Ready Jobs/.appupdates/apps/" + pkg);
        assertEquals(empty, UpdateDirectories.find(temporary.getRoot(), pkg, false, null));
    }
    @Test public void syncDirectoriesAndTemporaryFilesAreNotArtifacts() throws Exception {
        File folder = dir("feed");
        dir("feed/fake.apk");
        assertTrue(new File(folder, "release.apk.tmp").createNewFile());
        assertEquals(0, UpdateDirectories.apks(folder).length);
        assertTrue(new File(folder, "RELEASE.APK").createNewFile());
        assertEquals(1, UpdateDirectories.apks(folder).length);
    }
}

