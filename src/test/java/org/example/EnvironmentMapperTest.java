package org.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Test class for EnvironmentMapper utility.
 * Tests all the defined mapping scenarios to ensure they work correctly.
 */
public class EnvironmentMapperTest {

    private String currentDate;

    @BeforeEach
    void setUp() {
        currentDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    @Test
    void testRefProdToCurProd() {
        String result = EnvironmentMapper.mapEnvironment("ref.prod", "cur.prod");
        assertEquals(currentDate + " -> T", result);
    }

    @Test
    void testRefQaToCurQa() {
        String result = EnvironmentMapper.mapEnvironment("ref.qa", "cur.qa");
        assertEquals(currentDate + " -> T", result);
    }

    @Test
    void testRefDrToCurDr() {
        String result = EnvironmentMapper.mapEnvironment("ref.dr", "cur.dr");
        assertEquals(currentDate + " -> T", result);
    }

    @Test
    void testCurDrToCurProd() {
        String result = EnvironmentMapper.mapEnvironment("cur.dr", "cur.prod");
        assertEquals("dr -> prod", result);
    }

    @Test
    void testCurProdV8sToCurProd() {
        String result = EnvironmentMapper.mapEnvironment("cur.prod.v8s", "cur.prod");
        assertEquals("v8s -> v8t", result);
    }

    @Test
    void testRefQaToCurProd() {
        String result = EnvironmentMapper.mapEnvironment("ref.qa", "cur.prod");
        assertEquals("qa -> prod", result);
    }

    @Test
    void testRefQaToCurDr() {
        String result = EnvironmentMapper.mapEnvironment("ref.qa", "cur.dr");
        assertEquals("qa -> dr", result);
    }

    @Test
    void testCurProdToRefProd() {
        String result = EnvironmentMapper.mapEnvironment("cur.prod", "ref.prod");
        assertEquals("T -> " + currentDate, result);
    }

    @Test
    void testCurQaToRefQa() {
        String result = EnvironmentMapper.mapEnvironment("cur.qa", "ref.qa");
        assertEquals("T -> " + currentDate, result);
    }

    @Test
    void testCurDrToRefDr() {
        String result = EnvironmentMapper.mapEnvironment("cur.dr", "ref.dr");
        assertEquals("T -> " + currentDate, result);
    }

    @Test
    void testCurProdToCurProdV8s() {
        String result = EnvironmentMapper.mapEnvironment("cur.prod", "cur.prod.v8s");
        assertEquals("v8t -> v8s", result);
    }

    @Test
    void testCurProdToRefQa() {
        String result = EnvironmentMapper.mapEnvironment("cur.prod", "ref.qa");
        assertEquals("prod -> qa", result);
    }

    @Test
    void testCurDrToRefQa() {
        String result = EnvironmentMapper.mapEnvironment("cur.dr", "ref.qa");
        assertEquals("dr -> qa", result);
    }

    // Additional tests for dynamic environment names
    @Test
    void testRefStagingToCurStaging() {
        String result = EnvironmentMapper.mapEnvironment("ref.staging", "cur.staging");
        assertEquals(currentDate + " -> T", result);
    }

    @Test
    void testRefDevToCurDev() {
        String result = EnvironmentMapper.mapEnvironment("ref.dev", "cur.dev");
        assertEquals(currentDate + " -> T", result);
    }

    @Test
    void testCurStagingToRefStaging() {
        String result = EnvironmentMapper.mapEnvironment("cur.staging", "ref.staging");
        assertEquals("T -> " + currentDate, result);
    }

    @Test
    void testCurDevToRefDev() {
        String result = EnvironmentMapper.mapEnvironment("cur.dev", "ref.dev");
        assertEquals("T -> " + currentDate, result);
    }

    @Test
    void testCurStagingToCurProd() {
        String result = EnvironmentMapper.mapEnvironment("cur.staging", "cur.prod");
        assertEquals("staging -> prod", result);
    }

    @Test
    void testRefStagingToCurProd() {
        String result = EnvironmentMapper.mapEnvironment("ref.staging", "cur.prod");
        assertEquals("staging -> prod", result);
    }

    @Test
    void testCurProdToRefStaging() {
        String result = EnvironmentMapper.mapEnvironment("cur.prod", "ref.staging");
        assertEquals("prod -> staging", result);
    }

    @Test
    void testCurStagingV8sToCurStaging() {
        String result = EnvironmentMapper.mapEnvironment("cur.staging.v8s", "cur.staging");
        assertEquals("v8s -> v8t", result);
    }

    @Test
    void testCurStagingToCurStagingV8s() {
        String result = EnvironmentMapper.mapEnvironment("cur.staging", "cur.staging.v8s");
        assertEquals("v8t -> v8s", result);
    }

    // Tests for new system parameter functionality
    @Test
    void testCurProdV2ToCurProdWithSystem() {
        String result = EnvironmentMapper.mapEnvironment("cur.prod.v2", "cur.prod", "system");
        assertEquals("v2 -> system", result);
    }

    @Test
    void testRefQaV3ToRefQaWithSystem() {
        String result = EnvironmentMapper.mapEnvironment("ref.qa.v3", "ref.qa", "system");
        assertEquals("v3 -> system", result);
    }

    @Test
    void testCurProdToCurProdV4WithSystem() {
        String result = EnvironmentMapper.mapEnvironment("cur.prod", "cur.prod.v4", "system");
        assertEquals("system -> v4", result);
    }

    @Test
    void testRefStagingToRefStagingV5WithSystem() {
        String result = EnvironmentMapper.mapEnvironment("ref.staging", "ref.staging.v5", "system");
        assertEquals("system -> v5", result);
    }

    @Test
    void testCurDevV6ToCurDevWithSystem() {
        String result = EnvironmentMapper.mapEnvironment("cur.dev.v6", "cur.dev", "system");
        assertEquals("v6 -> system", result);
    }

    @Test
    void testCurDrToCurDrV7WithSystem() {
        String result = EnvironmentMapper.mapEnvironment("cur.dr", "cur.dr.v7", "system");
        assertEquals("system -> v7", result);
    }

    @Test
    void testCurProdV8sToCurProdWithSystem() {
        String result = EnvironmentMapper.mapEnvironment("cur.prod.v8s", "cur.prod", "system");
        assertEquals("v8s -> system", result);
    }

    @Test
    void testCurProdToCurProdV8sWithSystem() {
        String result = EnvironmentMapper.mapEnvironment("cur.prod", "cur.prod.v8s", "system");
        assertEquals("system -> v8s", result);
    }

    @Test
    void testVersionMappingWithoutSystem() {
        // Should fall back to legacy v8s handling when system is null
        String result = EnvironmentMapper.mapEnvironment("cur.prod.v8s", "cur.prod", null);
        assertEquals("v8s -> v8t", result);
    }

    @Test
    void testNullSource() {
        assertThrows(IllegalArgumentException.class, () -> {
            EnvironmentMapper.mapEnvironment(null, "cur.prod");
        });
    }

    @Test
    void testNullTarget() {
        assertThrows(IllegalArgumentException.class, () -> {
            EnvironmentMapper.mapEnvironment("ref.prod", null);
        });
    }

    @Test
    void testUnknownMapping() {
        String result = EnvironmentMapper.mapEnvironment("unknown.source", "unknown.target");
        assertEquals("Unknown mapping: unknown.source -> unknown.target", result);
    }

    @Test
    void testAllMappingsWithCurrentDate() {
        // Test that all date-dependent mappings use the current date
        String result1 = EnvironmentMapper.mapEnvironment("ref.prod", "cur.prod");
        assertTrue(result1.contains(currentDate));
        
        String result2 = EnvironmentMapper.mapEnvironment("cur.prod", "ref.prod");
        assertTrue(result2.contains(currentDate));
        
        String result3 = EnvironmentMapper.mapEnvironment("ref.qa", "cur.qa");
        assertTrue(result3.contains(currentDate));
        
        String result4 = EnvironmentMapper.mapEnvironment("cur.qa", "ref.qa");
        assertTrue(result4.contains(currentDate));
        
        String result5 = EnvironmentMapper.mapEnvironment("ref.dr", "cur.dr");
        assertTrue(result5.contains(currentDate));
        
        String result6 = EnvironmentMapper.mapEnvironment("cur.dr", "ref.dr");
        assertTrue(result6.contains(currentDate));
    }
} 