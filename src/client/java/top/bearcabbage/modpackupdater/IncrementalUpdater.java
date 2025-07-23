package top.bearcabbage.modpackupdater;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.codec.digest.DigestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Incremental updater with hash-check, versioning, and delete support.
 */
public class IncrementalUpdater {

    private static final OkHttpClient httpClient = new OkHttpClient();
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // ==== CONFIGURABLE CONSTANTS ====
    public static final Path BASE_DIRECTORY = FabricLoader.getInstance().getGameDir();
    public static final Path LOCAL_VERSION_FILE = BASE_DIRECTORY.resolve("modpack_update_versions.json");
    private static final String SELF_MOD_ID = "modpackupdater";

    // ==== DATA STRUCTURES ====
    public record FileEntry(String path, String url, String mode, String hash, String version, String id, String type) {
    }

    public record Manifest(List<FileEntry> files, List<String> delete) {
    }

    // ==== PUBLIC API ====
    public static void runUpdate(String manifestUrl) throws IOException {
        try {
            Manifest manifest = fetchManifest(manifestUrl);
            updateFiles(manifest);
        } catch (Exception e) {
            System.err.println("[ERROR] Update failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ==== CORE LOGIC ====
    private static Manifest fetchManifest(String manifestUrl) throws IOException {
        Request request = new Request.Builder().url(manifestUrl).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful())
                throw new IOException("Failed to fetch manifest");
            ResponseBody body = response.body();
            if (body == null)
                throw new IOException("Empty manifest response");
            return gson.fromJson(body.string(), Manifest.class);
        }
    }

    private static Map<String, String> loadLocalVersions() throws IOException {
        if (!Files.exists(LOCAL_VERSION_FILE))
            return new HashMap<>();
        try (Reader reader = Files.newBufferedReader(LOCAL_VERSION_FILE)) {
            return gson.fromJson(reader, new TypeToken<Map<String, String>>() {
            }.getType());
        }
    }

    private static void saveLocalVersions(Map<String, String> versions) throws IOException {
        Files.createDirectories(LOCAL_VERSION_FILE.getParent());
        try (Writer writer = Files.newBufferedWriter(LOCAL_VERSION_FILE)) {
            gson.toJson(versions, writer);
        }
    }

    private static String getLocalFileHash(Path filePath) throws IOException {
        if (!Files.exists(filePath))
            return "";
        try (InputStream is = Files.newInputStream(filePath)) {
            return DigestUtils.sha256Hex(is);
        }
    }

    private static void downloadFile(String fileUrl, Path targetPath) throws IOException {
        Request request = new Request.Builder().url(fileUrl).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful())
                throw new IOException("Failed to download: " + fileUrl);
            Files.createDirectories(targetPath.getParent());
            try (InputStream is = Objects.requireNonNull(response.body()).byteStream()) {
                Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void updateFiles(Manifest manifest) throws IOException {
        Map<String, String> localVersions = loadLocalVersions();
        Set<String> validPaths = new HashSet<>();
        Map<String, FileEntry> manifestIdMap = new HashMap<>();
        for (FileEntry entry : manifest.files) {
            if (SELF_MOD_ID.equals(entry.id)) {
                // 跳过自身更新
                continue;
            }
            if ("mod".equals(entry.type)) {
                manifestIdMap.put(entry.id, entry);
            }
        }
        boolean changed = false;
        // 首次同步本地记录，保证本地version文件不存在时也能生成
        for (FileEntry entry : manifest.files) {
            if ("version".equals(entry.mode)) {
                Path localPath = BASE_DIRECTORY.resolve(entry.path);
                if (Files.exists(localPath) && !entry.version.equals(localVersions.get(entry.path))) {
                    localVersions.put(entry.path, entry.version);
                    changed = true;
                }
            }
        }
        // 主循环，统一只在最后保存一次
        for (FileEntry entry : manifest.files) {
            Path localPath = BASE_DIRECTORY.resolve(entry.path);
            validPaths.add(localPath.toAbsolutePath().normalize().toString());
            boolean needsUpdate = false;
            try {
                needsUpdate = switch (entry.mode) {
                    case "hash" -> !getLocalFileHash(localPath).equalsIgnoreCase(entry.hash);
                    case "version" -> !entry.version.equals(localVersions.getOrDefault(entry.path, ""));
                    case "always" -> true;
                    default -> false;
                };
                System.out.println(
                        "[INFO] Check update for " + entry.path + ": mode=" + entry.mode + ", needsUpdate="
                                + needsUpdate);
            } catch (Exception e) {
                System.err.println("[ERROR] Check update failed for " + entry.path + ": " + e.getMessage());
                e.printStackTrace();
                continue;
            }
            if (needsUpdate) {
                try {
                    System.out.println("[INFO] Downloading new file: " + entry.path);
                    downloadFile(entry.url, localPath);
                    if ("version".equals(entry.mode)) {
                        localVersions.put(entry.path, entry.version);
                        changed = true;
                    }
                    ModpackUpdaterClient.updated = true;
                    System.out.println("[INFO] Update finished: " + entry.path);
                } catch (Exception e) {
                    System.err.println("[ERROR] Download or replace failed for " + entry.path + ": " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                // 本地文件存在且版本一致但本地记录缺失时补写记录
                if ("version".equals(entry.mode) && !entry.version.equals(localVersions.get(entry.path))
                        && Files.exists(localPath)) {
                    localVersions.put(entry.path, entry.version);
                    changed = true;
                }
                System.out.println("[INFO] Up-to-date: " + entry.path);
            }
        }
        try {
            deleteRedundantFiles(validPaths, manifest.delete);
        } catch (Exception e) {
            System.err.println("[ERROR] Delete redundant files failed: " + e.getMessage());
            e.printStackTrace();
        }

        // 自我审查
        cleanOldSelfVersions();
        cleanModsFolder(manifestIdMap);

        if (changed) {
            try {
                saveLocalVersions(localVersions);
            } catch (Exception e) {
                System.err.println("[ERROR] Save local versions failed: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private static void deleteRedundantFiles(Set<String> validPaths, List<String> deleteList) throws IOException {
        for (String delPath : deleteList) {
            Path fullPath = BASE_DIRECTORY.resolve(delPath);
            try {
                if (Files.exists(fullPath)) {
                    System.out.println("[INFO] Deleting: " + delPath);
                    Files.delete(fullPath);
                    ModpackUpdaterClient.updated = true;
                }
            } catch (Exception e) {
                System.err.println("[ERROR] Delete failed for " + delPath + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // ==== MODS FOLDER CLEANUP ====
    private static void cleanModsFolder(Map<String, FileEntry> manifestIdMap) throws IOException {
        Path modsDir = BASE_DIRECTORY.resolve("mods");
        if (Files.exists(modsDir) && Files.isDirectory(modsDir)) {
            try (var stream = Files.list(modsDir)) {
                for (Path jarPath : (Iterable<Path>) stream::iterator) {
                    if (jarPath.toString().endsWith(".jar")) {
                        ModInfo modInfo = readModInfoFromJar(jarPath);
                        if (modInfo == null)
                            continue;
                        FileEntry manifestEntry = manifestIdMap.get(modInfo.id);
                        if (manifestEntry != null) {
                            // debug输出
                            System.out.println("[DEBUG] Compare mod id: " + modInfo.id + ", local version: "
                                    + modInfo.version + ", manifest version: " + manifestEntry.version);
                            if (!modInfo.version.toString().equals(manifestEntry.version.toString())) {
                                // id在manifest但版本不一致，删除
                                System.out.println("[INFO] Deleting outdated mod: " + jarPath.getFileName());
                                Files.delete(jarPath);
                            }
                        }
                        // id不在manifest，保留本地mod
                    }
                }
            }
        }
    }

    private static class ModInfo {
        String id;
        String version;

        ModInfo(String id, String version) {
            this.id = id;
            this.version = version;
        }
    }

    private static ModInfo readModInfoFromJar(Path jarPath) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jarPath.toFile())) {
            java.util.zip.ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry != null) {
                try (InputStream is = zip.getInputStream(entry)) {
                    Gson gson = new Gson();
                    Map<?, ?> modJson = gson.fromJson(new java.io.InputStreamReader(is), Map.class);
                    String id = (String) modJson.get("id");
                    String version = (String) modJson.get("version");
                    if (id != null && version != null) {
                        return new ModInfo(id, version);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[ERROR] Failed to read mod info from jar: " + jarPath.getFileName() + ", reason: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    // ==== SPECIAL: CLEAN OLD VERSIONS OF SELF MOD ====
    public static void cleanOldSelfVersions() {
        try {
            Path modsDir = BASE_DIRECTORY.resolve("mods");
            if (Files.exists(modsDir) && Files.isDirectory(modsDir)) {
                List<ModInfo> selfMods = new ArrayList<>();
                Map<ModInfo, Path> modToPath = new HashMap<>();
                try (var stream = Files.list(modsDir)) {
                    for (Path jarPath : (Iterable<Path>) stream::iterator) {
                        if (jarPath.toString().endsWith(".jar")) {
                            ModInfo modInfo = readModInfoFromJar(jarPath);
                            if (modInfo != null && SELF_MOD_ID.equals(modInfo.id)) {
                                selfMods.add(modInfo);
                                modToPath.put(modInfo, jarPath);
                            }
                        }
                    }
                }
                if (!selfMods.isEmpty()) {
                    // 找到版本号最高的
                    ModInfo latest = Collections.max(selfMods,
                            Comparator.comparing(m -> m.version, IncrementalUpdater::compareVersionString));
                    for (ModInfo mod : selfMods) {
                        if (mod != latest) {
                            Path path = modToPath.get(mod);
                            System.out.println("[INFO] Deleting old self mod: " + path.getFileName());
                            try {
                                Files.delete(path);
                            } catch (Exception e) {
                                System.err.println("[ERROR] Delete old self mod failed: " + e.getMessage());
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[ERROR] Clean old self versions failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 版本号字符串比较，支持数字和点分隔，非数字部分按字典序
    private static int compareVersionString(String v1, String v2) {
        String[] a1 = v1.split("[.-]");
        String[] a2 = v2.split("[.-]");
        int len = Math.max(a1.length, a2.length);
        for (int i = 0; i < len; i++) {
            String s1 = i < a1.length ? a1[i] : "0";
            String s2 = i < a2.length ? a2[i] : "0";
            int cmp;
            try {
                cmp = Integer.compare(Integer.parseInt(s1), Integer.parseInt(s2));
            } catch (NumberFormatException e) {
                cmp = s1.compareTo(s2);
            }
            if (cmp != 0)
                return cmp;
        }
        return 0;
    }
}
