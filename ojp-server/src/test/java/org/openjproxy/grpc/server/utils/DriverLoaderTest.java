package org.openjproxy.grpc.server.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.*;

class DriverLoaderTest {

    @Test
    void testLoadDriversFromPath_NullPath() {
        // Should return true and not fail with null path
        assertTrue(DriverLoader.loadDriversFromPath(null));
    }

    @Test
    void testLoadDriversFromPath_EmptyPath() {
        // Should return true and not fail with empty path
        assertTrue(DriverLoader.loadDriversFromPath(""));
        assertTrue(DriverLoader.loadDriversFromPath("   "));
    }

    @Test
    void testLoadDriversFromPath_NonExistentDirectory(@TempDir Path tempDir) {
        // Should create the directory and return true
        Path driversPath = tempDir.resolve("drivers");
        assertFalse(Files.exists(driversPath));
        
        assertTrue(DriverLoader.loadDriversFromPath(driversPath.toString()));
        assertTrue(Files.exists(driversPath));
        assertTrue(Files.isDirectory(driversPath));
    }

    @Test
    void testLoadDriversFromPath_ExistingEmptyDirectory(@TempDir Path tempDir) throws IOException {
        // Create an empty directory
        Path driversPath = tempDir.resolve("drivers");
        Files.createDirectory(driversPath);
        
        // Should return true even with no JARs
        assertTrue(DriverLoader.loadDriversFromPath(driversPath.toString()));
    }

    @Test
    void testLoadDriversFromPath_WithJarFiles(@TempDir Path tempDir) throws IOException {
        // Create a directory with a dummy JAR file
        Path driversPath = tempDir.resolve("drivers");
        Files.createDirectory(driversPath);
        
        // Create a simple JAR file
        File jarFile = driversPath.resolve("dummy-driver.jar").toFile();
        createDummyJar(jarFile);
        
        // Should load the JAR successfully
        assertTrue(DriverLoader.loadDriversFromPath(driversPath.toString()));
    }

    @Test
    void testLoadDriversFromPath_FileInsteadOfDirectory(@TempDir Path tempDir) throws IOException {
        // Create a file instead of a directory
        Path filePath = tempDir.resolve("not-a-directory.txt");
        Files.createFile(filePath);
        
        // Should return false because it's not a directory
        assertFalse(DriverLoader.loadDriversFromPath(filePath.toString()));
    }

    @Test
    void testLoadDriversFromPath_MultipleJars(@TempDir Path tempDir) throws IOException {
        // Create a directory with multiple JAR files
        Path driversPath = tempDir.resolve("drivers");
        Files.createDirectory(driversPath);
        
        // Create multiple JAR files
        createDummyJar(driversPath.resolve("driver1.jar").toFile());
        createDummyJar(driversPath.resolve("driver2.jar").toFile());
        createDummyJar(driversPath.resolve("driver3.jar").toFile());
        
        // Should load all JARs successfully
        assertTrue(DriverLoader.loadDriversFromPath(driversPath.toString()));
    }

    @Test
    void testLoadDriversFromPath_IgnoresNonJarFiles(@TempDir Path tempDir) throws IOException {
        // Create a directory with JAR and non-JAR files
        Path driversPath = tempDir.resolve("drivers");
        Files.createDirectory(driversPath);
        
        // Create a JAR file
        createDummyJar(driversPath.resolve("driver.jar").toFile());
        
        // Create non-JAR files
        Files.createFile(driversPath.resolve("readme.txt"));
        Files.createFile(driversPath.resolve("config.xml"));
        
        // Should load only the JAR file successfully
        assertTrue(DriverLoader.loadDriversFromPath(driversPath.toString()));
    }

    /**
     * Creates a minimal valid JAR file for testing purposes
     */
    private void createDummyJar(File jarFile) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(jarFile);
             JarOutputStream jos = new JarOutputStream(fos)) {
            
            // Add a dummy entry to make it a valid JAR
            ZipEntry entry = new ZipEntry("META-INF/MANIFEST.MF");
            jos.putNextEntry(entry);
            jos.write("Manifest-Version: 1.0\n".getBytes());
            jos.closeEntry();
        }
    }
}
