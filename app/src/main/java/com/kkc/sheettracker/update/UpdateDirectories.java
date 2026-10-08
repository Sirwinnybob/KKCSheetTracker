package com.kkc.sheettracker.update;

import java.io.File;
import java.util.Locale;

/** Shared update-folder policy. Keep the copies in the Android apps in sync. */
public final class UpdateDirectories {
    private static final String[] JOB_FOLDERS = {"Ready Jobs", "Jobs", "JOBS", "SyncJobs/Ready Jobs"};
    private UpdateDirectories() {}

    public static File[] apks(File directory) {
        File[] files = directory.listFiles(file -> file.isFile()
                && file.getName().toLowerCase(Locale.ROOT).endsWith(".apk"));
        return files == null ? new File[0] : files;
    }

    private static File customForPackage(File directory, String packageName) {
        if (directory.getName().equals(".appupdates")) {
            return new File(directory, "apps/" + packageName);
        }
        if (directory.getName().equals("apps") && directory.getParentFile() != null
                && directory.getParentFile().getName().equals(".appupdates")) {
            return new File(directory, packageName);
        }
        File parent = directory.getParentFile();
        if (parent != null && parent.getName().equals("apps") && parent.getParentFile() != null
                && parent.getParentFile().getName().equals(".appupdates")) {
            return new File(parent, packageName);
        }
        return directory;
    }

    public static File find(File storageRoot, String packageName, boolean debug, String customPath) {
        File custom = customPath == null ? null : customForPackage(new File(customPath), packageName);
        boolean legacyCustom = custom != null && (custom.getName().equalsIgnoreCase(".Updates")
                || custom.getName().equalsIgnoreCase("Updates"));
        if (custom != null && custom.isDirectory() && (debug || !legacyCustom)) return custom;

        File emptyCanonical = null;
        if (!debug) {
            // Search every canonical root before any legacy root, including saved legacy paths.
            for (String folder : JOB_FOLDERS) {
                File canonical = new File(storageRoot, folder + "/.appupdates/apps/" + packageName);
                if (canonical.isDirectory()) {
                    if (apks(canonical).length > 0) return canonical;
                    if (emptyCanonical == null) emptyCanonical = canonical;
                }
            }
        }
        if (custom != null && custom.isDirectory()) return custom;
        for (String folder : JOB_FOLDERS) {
            String[] subfolders = debug ? new String[]{".Testing_Updates"} : new String[]{".Updates", "Updates"};
            for (String subfolder : subfolders) {
                File directory = new File(storageRoot, folder + "/" + subfolder);
                if (directory.isDirectory()) return directory;
            }
        }
        return emptyCanonical;
    }
}

