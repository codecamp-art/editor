package org.example;

import org.junit.jupiter.api.Test;

public class LocatorDebugTest {
    @Test
    void debugRootA() throws java.io.IOException {
        String xml = "<root>\n  <a></a><b></b>\n</root>";
        XmlInPlaceEditor.deleteTag(new java.io.ByteArrayInputStream(xml.getBytes()), "root/a", null, null);
    }

    @Test
    void debugSequential() throws java.io.IOException {
        String xml = "<root>\n  <item>\n    <sub>val</sub>\n  </item>\n  <a></a><b></b>\n</root>";
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("test",".xml");
        java.nio.file.Files.writeString(tmp, xml);
        java.io.File f = tmp.toFile();
        XmlInPlaceEditor.deleteTag(f, "root/item", null);
        System.out.println("After first deletion:\n"+java.nio.file.Files.readString(tmp));
        // try second deletion
        XmlInPlaceEditor.deleteTag(f, "root/a", null);
    }
} 