package com.elia.evoconti;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class LocalModelManagerTest {
    @Test
    public void sha256MatchesKnownDigest() throws Exception {
        File file = File.createTempFile("evoconti-model-check", ".bin");
        try {
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write("abc".getBytes(StandardCharsets.US_ASCII));
            }
            assertEquals(
                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    LocalModelManager.sha256(file));
        } finally {
            file.delete();
        }
    }
}
