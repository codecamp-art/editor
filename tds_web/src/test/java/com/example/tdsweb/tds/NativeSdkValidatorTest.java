package com.example.tdsweb.tds;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.tdsweb.config.TdsProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeSdkValidatorTest {
    @TempDir
    Path tempDir;

    @Test
    void acceptsCompleteLinuxSdkAndAdapterLayout() throws IOException {
        createRequiredFile("include/tds_api.h");
        createRequiredFile("include/tds_api_define.h");
        createRequiredFile("include/tds_api_struct_type.h");
        createRequiredFile("linux_x86_64/libtds_api.so");
        createRequiredFile("linux_x86_64/cpack.dat");
        Path adapter = createRequiredFile("build/native/tds_adapter");

        TdsProperties properties = new TdsProperties();
        properties.setSdkRoot(tempDir.toString());
        properties.getNativeAdapter().setExecutable(adapter.toString());

        new NativeSdkValidator(properties, NativeSdkValidator.Platform.LINUX).validate();
    }

    @Test
    void acceptsCompleteWindowsSdkAndAdapterLayout() throws IOException {
        createRequiredFile("include/tds_api.h");
        createRequiredFile("include/tds_api_define.h");
        createRequiredFile("include/tds_api_struct_type.h");
        createRequiredFile("win32/tds_api.lib");
        createRequiredFile("win32/tds_api.dll");
        createRequiredFile("win32/cpack.dat");
        Path adapter = createRequiredFile("build/native/tds_adapter.exe");

        TdsProperties properties = new TdsProperties();
        properties.setSdkRoot(tempDir.toString());
        properties.getNativeAdapter().setExecutable(adapter.toString());

        new NativeSdkValidator(properties, NativeSdkValidator.Platform.WINDOWS).validate();
    }

    @Test
    void rejectsMissingSdkFiles() {
        TdsProperties properties = new TdsProperties();
        properties.setSdkRoot(tempDir.toString());
        properties.getNativeAdapter().setExecutable(tempDir.resolve("build/native/tds_adapter").toString());

        assertThatThrownBy(() -> new NativeSdkValidator(properties, NativeSdkValidator.Platform.LINUX).validate())
            .isInstanceOf(TdsClientException.class)
            .hasMessageContaining("missing TDS SDK file");
    }

    @Test
    void rejectsWindowsSdkWithoutRuntimeDll() throws IOException {
        createRequiredFile("include/tds_api.h");
        createRequiredFile("include/tds_api_define.h");
        createRequiredFile("include/tds_api_struct_type.h");
        createRequiredFile("win32/tds_api.lib");
        createRequiredFile("win32/cpack.dat");
        Path adapter = createRequiredFile("build/native/tds_adapter.exe");

        TdsProperties properties = new TdsProperties();
        properties.setSdkRoot(tempDir.toString());
        properties.getNativeAdapter().setExecutable(adapter.toString());

        assertThatThrownBy(() -> new NativeSdkValidator(properties, NativeSdkValidator.Platform.WINDOWS).validate())
            .isInstanceOf(TdsClientException.class)
            .hasMessageContaining("win32/*.dll");
    }

    private Path createRequiredFile(String relative) throws IOException {
        Path file = tempDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "stub");
        return file;
    }
}
