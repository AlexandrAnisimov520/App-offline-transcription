package org.gradle.wrapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Small, dependency-free wrapper bootstrap for environments where the standard
 * Gradle wrapper binary is unavailable at authoring time.
 * Uses the Gradle distribution URL in gradle-wrapper.properties.
 */
public final class GradleWrapperMain {
    private GradleWrapperMain() { }

    public static void main(String[] args) throws Exception {
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path propertiesFile = project.resolve("gradle/wrapper/gradle-wrapper.properties");
        if (!Files.isRegularFile(propertiesFile)) {
            System.err.println("Missing Gradle wrapper properties: " + propertiesFile);
            System.exit(1);
        }
        Properties settings = new Properties();
        try (InputStream in = Files.newInputStream(propertiesFile)) { settings.load(in); }
        URI distribution = URI.create(settings.getProperty("distributionUrl"));
        String archive = distribution.getPath().substring(distribution.getPath().lastIndexOf('/') + 1);
        String version = archive.replace("-bin.zip", "").replace("-all.zip", "");
        Path installDir = Path.of(System.getProperty("user.home"), ".gradle", "wrapper", "dists", "bimvo", version);
        String executableName = System.getProperty("os.name").toLowerCase().contains("win") ? "gradle.bat" : "gradle";
        Path runner = installDir.resolve(version).resolve("bin").resolve(executableName);
        if (!Files.isRegularFile(runner)) {
            Files.createDirectories(installDir);
            Path destination = installDir.resolve(archive);
            if (!Files.isRegularFile(destination)) {
                System.out.println("Downloading Gradle distribution: " + distribution);
                HttpURLConnection connection = (HttpURLConnection) distribution.toURL().openConnection();
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(120000);
                connection.setInstanceFollowRedirects(true);
                Path partial = installDir.resolve(archive + ".partial");
                try (InputStream data = connection.getInputStream()) {
                    Files.copy(data, partial, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    connection.disconnect();
                    Files.deleteIfExists(partial);
                }
            }
            System.out.println("Extracting Gradle distribution...");
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(destination))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    Path output = installDir.resolve(entry.getName()).normalize();
                    if (!output.startsWith(installDir)) throw new IOException("Unsafe zip entry: " + entry.getName());
                    if (entry.isDirectory()) Files.createDirectories(output);
                    else {
                        Files.createDirectories(output.getParent());
                        Files.copy(zip, output, StandardCopyOption.REPLACE_EXISTING);
                    }
                    zip.closeEntry();
                }
            }
            if (!Files.isRegularFile(runner)) throw new IOException("Gradle launcher not found after extraction");
            runner.toFile().setExecutable(true);
        }
        List<String> command = new ArrayList<>();
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            command.add("cmd.exe");
            command.add("/c");
        }
        command.add(runner.toString());
        for (String arg : args) command.add(arg);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(project.toFile());
        builder.inheritIO();
        int exit = builder.start().waitFor();
        System.exit(exit);
    }
}
