package top.bearcabbage.modpackupdater;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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

    // ==== DATA STRUCTURES ====
    public record FileEntry(String path, String url, String mode, String hash, String version) {}
    public record Manifest(List<FileEntry> files, List<String> delete) {}

    // ==== PUBLIC API ====
    public static void runUpdate(String manifestUrl) throws IOException {
        Manifest manifest = fetchManifest(manifestUrl);
        updateFiles(manifest);
    }

    // ==== CORE LOGIC ====
    private static Manifest fetchManifest(String manifestUrl) throws IOException {
        Request request = new Request.Builder().url(manifestUrl).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Failed to fetch manifest");
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty manifest response");
            return gson.fromJson(body.string(), Manifest.class);
        }
    }

    private static Map<String, String> loadLocalVersions() throws IOException {
        if (!Files.exists(LOCAL_VERSION_FILE)) return new HashMap<>();
        try (Reader reader = Files.newBufferedReader(LOCAL_VERSION_FILE)) {
            return gson.fromJson(reader, Map.class);
        }
    }

    private static void saveLocalVersions(Map<String, String> versions) throws IOException {
        Files.createDirectories(LOCAL_VERSION_FILE.getParent());
        try (Writer writer = Files.newBufferedWriter(LOCAL_VERSION_FILE)) {
            gson.toJson(versions, writer);
        }
    }

    private static String getLocalFileHash(Path filePath) throws IOException {
        if (!Files.exists(filePath)) return "";
        try (InputStream is = Files.newInputStream(filePath)) {
            return DigestUtils.sha256Hex(is);
        }
    }

    private static void downloadFile(String fileUrl, Path targetPath) throws IOException {
        Request request = new Request.Builder().url(fileUrl).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Failed to download: " + fileUrl);
            Files.createDirectories(targetPath.getParent());
            try (InputStream is = Objects.requireNonNull(response.body()).byteStream()) {
                Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void updateFiles(Manifest manifest) throws IOException {
        Map<String, String> localVersions = loadLocalVersions();
        Set<String> validPaths = new HashSet<>();

        for (FileEntry entry : manifest.files) {
            Path localPath = BASE_DIRECTORY.resolve(entry.path);
            validPaths.add(localPath.toAbsolutePath().normalize().toString());

            boolean needsUpdate = switch (entry.mode) {
                case "hash" -> !getLocalFileHash(localPath).equalsIgnoreCase(entry.hash);
                case "version" -> !entry.version.equals(localVersions.getOrDefault(entry.path, ""));
                case "always" -> true;
                default -> false;
            };

            if (needsUpdate) {
                System.out.println("Updating: " + entry.path);
                downloadFile(entry.url, localPath);
                if ("version".equals(entry.mode)) {
                    localVersions.put(entry.path, entry.version);
                }
                ModpackUpdaterClient.updated = true;
            } else {
                System.out.println("Up-to-date: " + entry.path);
            }
            saveLocalVersions(localVersions);
        }
        deleteRedundantFiles(validPaths, manifest.delete);
        saveLocalVersions(localVersions);
    }

    private static void deleteRedundantFiles(Set<String> validPaths, List<String> deleteList) throws IOException {
        for (String delPath : deleteList) {
            Path fullPath = BASE_DIRECTORY.resolve(delPath);
            if (Files.exists(fullPath)) {
                System.out.println("Deleting: " + delPath);
                Files.delete(fullPath);
                ModpackUpdaterClient.updated = true;
            }
        }
    }

    // ==== DEMO MAIN METHOD ====
    public static void main(String[] args) throws IOException {
        String manifestUrl = "https://yourserver.com/update_manifest.json";
        runUpdate(manifestUrl);
        System.out.println("Update completed.");
    }
}
