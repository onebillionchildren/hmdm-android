package com.hmdm.launcher.util;

import android.content.Context;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.LocaleList;
import android.util.DisplayMetrics;
import android.util.Log;

import com.hmdm.launcher.Const;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.monitor.FileEntry;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public class ApksUtils
{

    private static Map<String,Integer> DENSITY_TO_VALUE = new LinkedHashMap<>();
    static {
        DENSITY_TO_VALUE.put("ldpi", DisplayMetrics.DENSITY_LOW);
        DENSITY_TO_VALUE.put("mdpi", DisplayMetrics.DENSITY_MEDIUM);
        DENSITY_TO_VALUE.put("tvdpi", DisplayMetrics.DENSITY_TV);
        DENSITY_TO_VALUE.put("hdpi", DisplayMetrics.DENSITY_HIGH);
        DENSITY_TO_VALUE.put("xhdpi", DisplayMetrics.DENSITY_XHIGH);
        DENSITY_TO_VALUE.put("xxhdpi", DisplayMetrics.DENSITY_XXHIGH);
        DENSITY_TO_VALUE.put("xxxhdpi", DisplayMetrics.DENSITY_XXXHIGH);
    }

    private static Set<String> ABIS = new HashSet<>();

    static {
        ABIS.add("armeabi");
        ABIS.add("armeabi_v7a");
        ABIS.add("arm64_v8a");
        ABIS.add("x86");
        ABIS.add("x86_64");
        ABIS.add("mips");
        ABIS.add("mips64");
    }

    private static Set<String> LOCALES = new HashSet<>();

    static {
        for (Locale locale : Locale.getAvailableLocales()) {
            String language = locale.getLanguage();
            if(!LOCALES.contains(language))
                LOCALES.add(language);
        }
    }

    public static boolean extractAndInstall(Context context, File apks, String packageName, InstallUtils.InstallErrorHandler errorHandler) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            if (errorHandler != null) {
                errorHandler.onInstallError();
            }
            return false;
        }

        // Here we presume that apks file name ends with .apks
        try {
            List<ZipEntry> selectedEntries = new ArrayList<>();

            List<ZipEntry> abiFiles = new ArrayList<>();
            List<ZipEntry> densityFiles = new ArrayList<>();
            List<ZipEntry> localeFiles = new ArrayList<>();

            ZipFile zipFile = new ZipFile(apks);
            Enumeration<? extends ZipEntry> entries = zipFile.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory())
                    continue;

                if (entry.getName().endsWith(".apk")) {
                    // collect all splits (assets files)
                    if (entry.getName().contains("asset-slices/")) {
                        selectedEntries.add(entry);
                    }

                    if (entry.getName().contains("splits/")) {
                        //add base-master
                        if (entry.getName().endsWith("base-master.apk"))
                            selectedEntries.add(entry);

                        if (entryInSet(entry, ABIS))
                            abiFiles.add(entry);
                        else if (entryInSet(entry, DENSITY_TO_VALUE.keySet()))
                            densityFiles.add(entry);
                        else if (entryInSet(entry, LOCALES))
                            localeFiles.add(entry);

                    }
                }
            }

            addPreferredEntriesOrAll(selectedEntries, abiFiles, getPreferredAbis());

            String density = getPreferredDensity(context);
            HashSet<String> densitySet = new HashSet<>();
            if (density != null)
                densitySet.add(density);
            addPreferredEntriesOrAll(selectedEntries, densityFiles, densitySet);

            addPreferredEntriesOrAll(selectedEntries, localeFiles, getPreferredLanguages(context));

            if (selectedEntries == null || selectedEntries.size() == 0) {
                RemoteLogger.log(context, Const.LOG_WARN, "Failed to unpack APKS for " + packageName + " - ignoring installation");
                if (errorHandler != null) {
                    errorHandler.onInstallError();
                }
                return false;
            }

            long totalSize = 0;
            for (ZipEntry entry : selectedEntries) {
                totalSize += entry.getSize();
            }

            Log.i(Const.LOG_TAG, "Installing APKS " + packageName);
            PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);

            if (packageName != null) {
                params.setAppPackageName(packageName);
            }
            params.setSize(totalSize);
            int sessionId = packageInstaller.createSession(params);

            PackageInstaller.Session session = packageInstaller.openSession(sessionId);

            for (int i=0; i<selectedEntries.size(); i++) {
                ZipEntry entry = selectedEntries.get(i);
                try (InputStream in = zipFile.getInputStream(selectedEntries.get(i));
                    OutputStream out = session.openWrite(String.format("split-%d.apk", i), 0, entry.getSize())) {
                    IOUtils.copy(in, out);
                    session.fsync(out);
                }
            }
            zipFile.close();

            session.commit(InstallUtils.createIntentSender(context, sessionId, packageName));
            session.close();

            Log.i(Const.LOG_TAG, "Installation session committed");


            return true;

        }
        catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        catch (ZipException e) {
            e.printStackTrace();
        }
        catch (IOException e) {
            e.printStackTrace();
        }


        if (errorHandler != null) {
            errorHandler.onInstallError();
        }

        return false;
    }
    
    private static void addPreferredEntriesOrAll(List<ZipEntry> selectedEntries, List<ZipEntry> sourceEntries, Set<String> preferred) {
        boolean found = false;
        if(preferred != null && preferred.size() > 0) {

            for(ZipEntry fileEntry : sourceEntries) {
                if(entryInSet(fileEntry, preferred)) {
                    selectedEntries.add(fileEntry);
                    found = true;
                }
            }
        }

        if(!found)
            selectedEntries.addAll(sourceEntries);
    }

    private static boolean entryInSet(ZipEntry zipEntry, Set<String> set) {
        String entryName = zipEntry.getName();
        for(String name : set) {
            if(entryName.endsWith("base-"+name.toLowerCase()+".apk"))
                return true;
        }

        return false;
    }


    private static HashSet<String> getPreferredAbis() {
        HashSet<String> abis = new HashSet<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            for (int i = 0; i < Build.SUPPORTED_ABIS.length; i++) {
                abis.add(Build.SUPPORTED_ABIS[i].replace("-", "_"));
            }
        }

        return abis;
    }

    private static HashSet<String> getPreferredLanguages(Context context) {
        HashSet<String> languages = new HashSet<>();
        if (Build.VERSION.SDK_INT >=  Build.VERSION_CODES.N) {
            LocaleList localeList = context.getResources().getConfiguration().getLocales();
            for (int i = 0; i < localeList.size(); i++) {
                languages.add(localeList.get(i).getLanguage());
            }

        }
        else {

            languages.add(context.getResources().getConfiguration().locale.getLanguage());
        }


        return languages;
    }

    private static String getPreferredDensity(Context context)
    {
        int deviceDpi = context.getResources().getDisplayMetrics().densityDpi;

        String selectedDensity = null;
        int selectedDensityDelta = Integer.MAX_VALUE;

        for (String density : DENSITY_TO_VALUE.keySet()) {
            int delta = Math.abs(deviceDpi - DENSITY_TO_VALUE.get(density));
            if (delta < selectedDensityDelta) {
                selectedDensity = density;
                selectedDensityDelta = delta;
            }
        }

        return selectedDensity;
    }


}
