package com.example.tdsweb.tds;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NativeBuildAssetTest {
    @Test
    void nativeCmakeSupportsWindowsAndLinuxVendorLayouts() throws IOException {
        String cmake = read("src/native/CMakeLists.txt");

        assertThat(cmake).contains("WIN32", "win32", "tds_api.lib", "linux_x86_64", "libtds_api.so");
        assertThat(cmake).contains("RUNTIME_OUTPUT_DIRECTORY", "copy_if_different");
    }

    @Test
    void gradleBuildUsesManuallyPreparedSdkForLocalNativeBuilds() throws IOException {
        String gradle = read("build.gradle");

        assertThat(gradle).contains("orElse('tds')");
        assertThat(gradle).contains("verifyFullTdsSdk", "verifyNativeSdk", "nativeBuildType");
        assertThat(gradle).contains("nativeCmakeGenerator", "nativeCmakePlatform");
        assertThat(gradle).contains("Debug");
        assertThat(gradle).contains("buildNativeAdapter", "--config");
        assertThat(gradle).doesNotContain("prepareTdsSdk", "TDS_SDK_AUTH", "TDS_SDK_URL", "tdsSdkUrl");
        assertThat(Files.exists(Path.of("tds.properties.example"))).isFalse();
    }

    @Test
    void tdsSdkDownloadScriptIsJenkinsOnly() throws IOException {
        String prepare = read("cmake/PrepareTdsSdk.cmake");

        assertThat(prepare).contains("TDS SDK download is Jenkins-only", "TDS_SDK_CONTEXT");
        assertThat(prepare).contains("_context STREQUAL \"jenkins\"");
    }

    @Test
    void jenkinsHelperUsesCertificateAuthForArtifactorySdkDownload() throws IOException {
        String groovy = read("jenkins/reporting_web_common.groovy");

        assertThat(groovy).contains("TDS_PACKAGE_URL", "ARTIFACTORY_CERT_FILE", "ARTIFACTORY_KEY_FILE");
        assertThat(groovy).contains("-DTDS_SDK_CONTEXT=jenkins", "-DTDS_SDK_AUTH=cert");
        assertThat(groovy).contains("cmake/PrepareTdsSdk.cmake");
        assertThat(groovy).contains("stageReleasePackage", "runStubSmoke");
    }

    @Test
    void jenkinsUsesSeparatePrAndReleasePipelines() throws IOException {
        String pr = read("jenkins/Jenkinsfile.pr");
        String release = read("jenkins/Jenkinsfile.release");

        assertThat(Files.exists(Path.of("jenkins/Jenkinsfile"))).isFalse();
        assertThat(pr).contains("fixedIntegrationConfig('pr')", "Build And Test", "Stub Smoke");
        assertThat(pr).doesNotContain("Stage Release");
        assertThat(release).contains("fixedIntegrationConfig('release')", "Stage Release", "stageReleasePackage");
        assertThat(release).contains("tds-client-query-web-rhel8-${env.BUILD_NUMBER}.tar.gz");
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
    }
}
