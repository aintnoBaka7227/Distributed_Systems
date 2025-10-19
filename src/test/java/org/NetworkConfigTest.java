package org;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NetworkConfigTest:
 * Ensures network configuration files are parsed correctly and majority/quorum math is computed
 */
public class NetworkConfigTest {

    /** Writes the provided content to a temporary config file and returns it */
    private File writeTempConfig(String content) throws Exception {
        File f = File.createTempFile("mock", ".config");
        try (FileWriter fw = new FileWriter(f)) {
            fw.write(content);
        }
        f.deleteOnExit();
        return f;
    }

    /** Parsing of IDs/hosts/ports and correct majority calculation for N=3 -> 2 */
    @Test
    void loadParsesIdsHostsPortsAndMajority() throws Exception {
        File f = writeTempConfig(
                "# comment\n" +
                "M1,localhost,9001\n" +
                "M2,localhost,9002\n" +
                "M3,localhost,9003\n"
        );

        NetworkConfig cfg = NetworkConfig.loadConfigFile(f.getAbsolutePath());

        // Size and majority
        assertEquals(3, cfg.size());
        assertEquals(2, cfg.majority());

        // Endpoint lookup and fields
        Endpoint e2 = cfg.getEndpoint("M2");
        assertNotNull(e2);
        assertEquals("M2", e2.id);
        assertEquals("localhost", e2.host);
        assertEquals(9002, e2.port);
    }
}
