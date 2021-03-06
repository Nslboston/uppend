package com.upserve.uppend.blobs;

import com.upserve.uppend.util.SafeDeleting;
import org.junit.*;

import java.io.IOException;
import java.nio.file.*;

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;

public class VirtualPageFileTest {

    private String name = "virtual_page_file_test";
    private Path rootPath = Paths.get("build/test/blobs/virtual_page_file");
    private Path path = rootPath.resolve(name);

    VirtualPageFile instance;

    @Before
    public void setup() throws IOException {
        SafeDeleting.removeDirectory(rootPath);
        Files.createDirectories(rootPath);
    }

    @After
    public void teardown() throws IOException {
        if (instance != null) instance.close();
    }

    @Test
    public void testReadWritePageAllocation() throws IOException {

        instance = new VirtualPageFile(path, 36, 1024, false);
        byte[] result = new byte[3];

        assertFalse(instance.isPageAvailable(0, 0));
        assertFalse(instance.isPageAvailable(18, 0));

        Page page5 = instance.getCachedOrCreatePage(0, 5, false);
        page5.put(16, "abc".getBytes(), 0);

        assertTrue(instance.isPageAvailable(0, 0));
        assertTrue(instance.isPageAvailable(0, 1));
        assertTrue(instance.isPageAvailable(0, 2));
        assertTrue(instance.isPageAvailable(0, 3));
        assertTrue(instance.isPageAvailable(0, 4));
        assertTrue(instance.isPageAvailable(0, 5));
        assertFalse(instance.isPageAvailable(18, 0));


        Page page4 = instance.getExistingPage(0, 4);
        page4.put(12, "def".getBytes(), 0);

        instance.close();
        instance = new VirtualPageFile(path, 36, 1024, true);

        assertFalse(instance.isPageAvailable(18, 0));
        assertTrue(instance.isPageAvailable(0, 5));

        page5 = instance.getExistingPage(0, 5);
        page5.get(16, result, 0);
        assertArrayEquals("abc".getBytes(), result);

        page4 = instance.getExistingPage(0, 4);
        page4.get(12, result, 0);
        assertArrayEquals("def".getBytes(), result);

        instance.close();
        instance = new VirtualPageFile(path, 36, 1024, false);

        Page page7 = instance.getCachedOrCreatePage(0, 7, false);
        page7.put(28, "ghi".getBytes(), 0);
        page7.get(28, result, 0);

        assertArrayEquals("ghi".getBytes(), result);


    }


}
