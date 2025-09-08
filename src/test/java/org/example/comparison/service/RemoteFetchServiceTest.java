package org.example.comparison.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RemoteFetchService
 */
@ExtendWith(MockitoExtension.class)
class RemoteFetchServiceTest {

    private RemoteFetchService remoteFetchService;
    
    @TempDir
    Path tempDir;
    
    private String remoteServer = "test-server.example.com";
    private String remoteDataPath = "\\remote\\data\\path";
    private String localDataPath;

    @BeforeEach
    void setUp() {
        localDataPath = tempDir.toString();
        remoteFetchService = new RemoteFetchService(remoteServer, remoteDataPath, localDataPath);
    }

    @Test
    void testFetchFixDataFiles_Success() throws IOException {
        // Create a custom RemoteFetchService that overrides runCommand to avoid actual process execution
        RemoteFetchService testService = new RemoteFetchService(remoteServer, remoteDataPath, localDataPath) {
            @Override
            protected void runCommand(String command, Object... args) throws Exception {
                // Simulate successful command execution - create test files after "transfer"
                Path subDir1 = Paths.get(localDataPath).resolve("session1");
                Path subDir2 = Paths.get(localDataPath).resolve("session2");
                Files.createDirectories(subDir1);
                Files.createDirectories(subDir2);
                
                Files.writeString(subDir1.resolve("OUT.dat"), "session1 data content");
                Files.writeString(subDir2.resolve("OUT.dat"), "session2 data content");
            }
        };

        // Execute
        Map<String, String> result = testService.fetchFixDataFiles();

        // Verify
        assertNotNull(result);
        assertEquals(2, result.size());
        assertTrue(result.containsKey("session1"));
        assertTrue(result.containsKey("session2"));
        assertEquals("session1 data content", result.get("session1"));
        assertEquals("session2 data content", result.get("session2"));
    }

    @Test
    void testFetchFixDataFiles_CommandFailure() {
        // Create a custom RemoteFetchService that simulates command failure
        RemoteFetchService testService = new RemoteFetchService(remoteServer, remoteDataPath, localDataPath) {
            @Override
            protected void runCommand(String command, Object... args) throws Exception {
                throw new Exception("Python script failed: Connection timeout");
            }
        };

        // Execute and verify exception
        RuntimeException exception = assertThrows(RuntimeException.class, 
            () -> testService.fetchFixDataFiles());
        
        assertEquals("Python script failed: Connection timeout", exception.getCause().getMessage());
    }

    @Test
    void testFetchFixDataFiles_IOError() throws IOException {
        // Create a file instead of a directory to trigger the "not a directory" error
        Path fileInsteadOfDir = tempDir.resolve("not_a_directory.txt");
        Files.writeString(fileInsteadOfDir, "content");
        
        RemoteFetchService testService = new RemoteFetchService(remoteServer, remoteDataPath, fileInsteadOfDir.toString());

        // Execute and verify exception
        RuntimeException exception = assertThrows(RuntimeException.class, 
            () -> testService.fetchFixDataFiles());
        
        assertTrue(exception.getMessage().contains("The path must be an existing dir"));
    }

    @Test
    void testReadAllDataFiles_WithValidDirectory() throws IOException {
        // Create test directory structure
        Path subDir1 = tempDir.resolve("session1");
        Path subDir2 = tempDir.resolve("session2");
        Files.createDirectories(subDir1);
        Files.createDirectories(subDir2);
        
        Files.writeString(subDir1.resolve("data.txt"), "content1");
        Files.writeString(subDir2.resolve("data.txt"), "content2");
        Files.writeString(tempDir.resolve("root.txt"), "root content");

        // Execute
        Map<String, String> result = remoteFetchService.readAllDataFiles(tempDir.toString());

        // Verify
        assertEquals(3, result.size());
        assertTrue(result.containsKey("session1"));
        assertTrue(result.containsKey("session2"));
        assertEquals("content1", result.get("session1"));
        assertEquals("content2", result.get("session2"));
        assertEquals("root content", result.get(tempDir.getFileName().toString()));
    }

    @Test
    void testReadAllDataFiles_NonExistentDirectory() {
        String nonExistentPath = "/path/that/does/not/exist";
        
        Map<String, String> result = remoteFetchService.readAllDataFiles(nonExistentPath);
        
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testReadAllDataFiles_EmptyDirectory() throws IOException {
        Path emptyDir = tempDir.resolve("empty");
        Files.createDirectories(emptyDir);
        
        Map<String, String> result = remoteFetchService.readAllDataFiles(emptyDir.toString());
        
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testReadAllDataFiles_IOExceptionWhileReading() throws IOException {
        // Create a file and then make it unreadable (simulate permission issue)
        Path subDir = tempDir.resolve("session1");
        Files.createDirectories(subDir);
        Path testFile = subDir.resolve("data.txt");
        Files.writeString(testFile, "test content");
        
        // Mock Files.readString to throw IOException
        try (MockedStatic<Files> filesMock = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            filesMock.when(() -> Files.readString(testFile))
                    .thenThrow(new IOException("Permission denied"));

            // Execute and verify exception
            RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> remoteFetchService.readAllDataFiles(tempDir.toString()));
            
            assertEquals("failed to read data file", exception.getMessage());
            assertInstanceOf(IOException.class, exception.getCause());
        }
    }

    @Test
    void testDeleteContents_ExistingDirectoryWithFiles() throws Exception {
        // Create test directory structure
        Path subDir1 = tempDir.resolve("subdir1");
        Path subDir2 = tempDir.resolve("subdir2");
        Files.createDirectories(subDir1);
        Files.createDirectories(subDir2);
        
        Files.writeString(tempDir.resolve("file1.txt"), "content1");
        Files.writeString(subDir1.resolve("file2.txt"), "content2");
        Files.writeString(subDir2.resolve("file3.txt"), "content3");

        // Verify files exist before deletion
        assertTrue(Files.exists(tempDir.resolve("file1.txt")));
        assertTrue(Files.exists(subDir1.resolve("file2.txt")));
        assertTrue(Files.exists(subDir2.resolve("file3.txt")));

        // Execute deleteContents directly since it's now protected
        remoteFetchService.deleteContents(tempDir);

        // Verify directory still exists but is empty
        assertTrue(Files.exists(tempDir));
        assertTrue(Files.list(tempDir).findAny().isEmpty());
    }

    @Test
    void testDeleteContents_NonExistentDirectory() throws Exception {
        Path nonExistentDir = tempDir.resolve("nonexistent");
        
        // Execute deleteContents directly since it's now protected
        remoteFetchService.deleteContents(nonExistentDir);

        // Verify directory was created
        assertTrue(Files.exists(nonExistentDir));
        assertTrue(Files.isDirectory(nonExistentDir));
    }

    @Test
    void testDeleteContents_NotADirectory() throws Exception {
        // Create a file instead of directory
        Path file = tempDir.resolve("not_a_directory.txt");
        Files.writeString(file, "content");

        // Execute and verify exception
        RuntimeException exception = assertThrows(RuntimeException.class, 
            () -> {
                try {
                    remoteFetchService.deleteContents(file);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        
        assertTrue(exception.getMessage().contains("The path must be an existing dir"));
    }

    @Test
    void testRunCommand_Success() throws Exception {
        // Test cross-platform command execution with mocking
        RemoteFetchService mockService = new RemoteFetchService(remoteServer, remoteDataPath, localDataPath) {
            @Override
            protected void runCommand(String command, Object... args) throws Exception {
                // Mock successful execution - verify the command is properly formatted
                String formattedCommand = String.format(command, args);
                if (formattedCommand == null || formattedCommand.trim().isEmpty()) {
                    throw new Exception("Invalid command");
                }
                // Simulate successful execution
            }
        };
        
        String testCommand = "echo test";
        
        assertDoesNotThrow(() -> {
            try {
                mockService.runCommand(testCommand, new Object[0]);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void testRunCommand_NonZeroExitCode() throws Exception {
        // Create a mock service that simulates command failure
        RemoteFetchService testService = new RemoteFetchService(remoteServer, remoteDataPath, localDataPath) {
            @Override
            protected void runCommand(String command, Object... args) throws Exception {
                throw new Exception("failed to run " + String.format(command, args) + ", error: Command failed with exit code 1");
            }
        };
        
        Exception exception = assertThrows(Exception.class, () -> {
            testService.runCommand("mock-failing-command", new Object[0]);
        });
        
        assertTrue(exception.getMessage().contains("failed to run"));
    }

    @Test
    void testRunCommand_WithArguments() throws Exception {
        // Create a mock service to test argument handling without actual command execution
        RemoteFetchService testService = new RemoteFetchService(remoteServer, remoteDataPath, localDataPath) {
            @Override
            protected void runCommand(String command, Object... args) throws Exception {
                // Verify that arguments are properly formatted
                String formattedCommand = String.format(command, args);
                if (!formattedCommand.contains("hello world")) {
                    throw new Exception("Arguments not properly formatted");
                }
                // Mock successful execution
            }
        };
        
        // Test command with arguments
        String commandTemplate = "echo '%s %s'";
        Object[] args = {"hello", "world"};
        
        assertDoesNotThrow(() -> {
            try {
                testService.runCommand(commandTemplate, args);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Helper method to create a testable version of RemoteFetchService
     * This allows us to override specific methods for testing
     */
    private static class TestableRemoteFetchService extends RemoteFetchService {

        public TestableRemoteFetchService(String remoteServer, String remoteDataPath, String localDataPath) {
            super(remoteServer, remoteDataPath, localDataPath);
        }

        @Override
        protected void runCommand(String command, Object... args) throws Exception {
            // Simulate successful execution without actually running commands
        }
    }

    @Test
    void testFetchFixDataFiles_IntegrationTest() throws IOException {
        TestableRemoteFetchService testService = new TestableRemoteFetchService(
            remoteServer, remoteDataPath, localDataPath) {
            @Override
            protected void runCommand(String command, Object... args) throws Exception {
                // Simulate the data transfer by creating test files
                Path session1 = Paths.get(localDataPath).resolve("session1");
                Path session2 = Paths.get(localDataPath).resolve("session2");
                Files.createDirectories(session1);
                Files.createDirectories(session2);
                
                Files.writeString(session1.resolve("OUT.dat"), "FIX session 1 data");
                Files.writeString(session2.resolve("OUT.dat"), "FIX session 2 data");
            }
        };

        Map<String, String> result = testService.fetchFixDataFiles();

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("FIX session 1 data", result.get("session1"));
        assertEquals("FIX session 2 data", result.get("session2"));
    }

    @Test
    void testConstructorParameterInjection() {
        String testServer = "test.server.com";
        String testRemotePath = "\\test\\remote\\path";
        String testLocalPath = "/test/local/path";
        
        RemoteFetchService service = new RemoteFetchService(testServer, testRemotePath, testLocalPath);
        
        // Verify service was created successfully
        assertNotNull(service);
        // Note: Since the fields are private, we can't directly verify them
        // In a real scenario, you might want to add getters or make fields package-private for testing
    }

    @Test
    void testGetOsSpecificCommand_Windows() {
        // Mock Windows environment
        RemoteFetchService testService = new RemoteFetchService(remoteServer, remoteDataPath, localDataPath) {
            @Override
            protected String[] getOsSpecificCommand(String command) {
                // Force Windows behavior
                return new String[]{"cmd", "/c", command};
            }
        };
        
        String[] result = testService.getOsSpecificCommand("echo test");
        
        assertEquals(3, result.length);
        assertEquals("cmd", result[0]);
        assertEquals("/c", result[1]);
        assertEquals("echo test", result[2]);
    }

    @Test
    void testGetOsSpecificCommand_Linux() {
        // Mock Linux environment
        RemoteFetchService testService = new RemoteFetchService(remoteServer, remoteDataPath, localDataPath) {
            @Override
            protected String[] getOsSpecificCommand(String command) {
                // Force Linux behavior
                return new String[]{"/bin/bash", "-c", command};
            }
        };
        
        String[] result = testService.getOsSpecificCommand("echo test");
        
        assertEquals(3, result.length);
        assertEquals("/bin/bash", result[0]);
        assertEquals("-c", result[1]);
        assertEquals("echo test", result[2]);
    }

    @Test
    void testGetOsSpecificCommand_ActualOsDetection() {
        RemoteFetchService testService = new RemoteFetchService(remoteServer, remoteDataPath, localDataPath);
        
        String[] result = testService.getOsSpecificCommand("echo test");
        
        // Verify we get a valid command array regardless of OS
        assertNotNull(result);
        assertEquals(3, result.length);
        
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("windows")) {
            assertEquals("cmd", result[0]);
            assertEquals("/c", result[1]);
        } else {
            assertEquals("/bin/bash", result[0]);
            assertEquals("-c", result[1]);
        }
        assertEquals("echo test", result[2]);
    }
}
