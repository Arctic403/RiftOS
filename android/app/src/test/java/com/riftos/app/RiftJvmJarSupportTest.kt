package com.riftos.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile

class RiftJvmJarSupportTest {
    @get:Rule val files = TemporaryFolder()

    private fun jar(vararg entries: Pair<String, String>): File {
        val file = files.newFile()
        JarOutputStream(file.outputStream()).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(JarEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    private fun output() = files.newFile()
    private fun entries(file: File): Set<String> =
        ZipFile(file).use { it.entries().asSequence().map { entry -> entry.name }.toSet() }

    @Test fun keepsAndroidClassesAndSkipsDesktopVariants() {
        val input = jar("p/A.class" to "base", "module-info.class" to "module",
            "META-INF/versions/9/p/A.class" to "desktop",
            "META-INF/MANIFEST.MF" to "Multi-Release: true\n")
        val out = output()
        RiftJvmJarSupport.prepare(input, out, HashSet(), linkedMapOf())
        assertEquals(setOf("p/A.class"), entries(out))
    }

    @Test fun rejectsTraversalEvenInIgnoredResource() {
        assertThrows(IllegalArgumentException::class.java) {
            RiftJvmJarSupport.prepare(jar("../escape" to "bad"), output(), HashSet(), linkedMapOf())
        }
    }

    @Test fun rejectsDuplicateAppAndLibraryClass() {
        assertThrows(IllegalArgumentException::class.java) {
            RiftJvmJarSupport.prepare(jar("p/A.class" to "x"), output(),
                hashSetOf("p/A.class"), linkedMapOf())
        }
    }

    @Test fun rejectsDuplicateClassesAcrossLibraries() {
        val names = hashSetOf<String>()
        RiftJvmJarSupport.prepare(jar("p/A.class" to "one"), output(), names, linkedMapOf())
        assertThrows(IllegalArgumentException::class.java) {
            RiftJvmJarSupport.prepare(jar("p/A.class" to "two"), output(), names, linkedMapOf())
        }
    }

    @Test fun mergesServiceProvidersInStableOrder() {
        val name = "META-INF/services/p.Service"
        val resources = linkedMapOf<String, ByteArray>()
        RiftJvmJarSupport.prepare(jar(name to "p.Z\n# comment\np.A\n"),
            output(), HashSet(), resources)
        RiftJvmJarSupport.prepare(jar(name to "p.A\np.B\n"),
            output(), HashSet(), resources)
        assertEquals("p.A\np.B\np.Z\n", String(resources.getValue(name)))
    }

    @Test fun rejectsInvalidServiceProvider() {
        assertThrows(IllegalArgumentException::class.java) {
            RiftJvmJarSupport.prepare(jar("META-INF/services/p.Service" to "../bad"),
                output(), HashSet(), linkedMapOf())
        }
    }

    @Test fun identicalOrdinaryResourcesMayRepeat() {
        val resources = linkedMapOf<String, ByteArray>()
        repeat(2) { RiftJvmJarSupport.prepare(jar("p/data.properties" to "a=b"),
            output(), HashSet(), resources) }
        assertEquals(1, resources.size)
    }

    @Test fun conflictingOrdinaryResourcesFail() {
        val resources = linkedMapOf<String, ByteArray>()
        RiftJvmJarSupport.prepare(jar("p/data.properties" to "one"), output(), HashSet(), resources)
        assertThrows(IllegalArgumentException::class.java) {
            RiftJvmJarSupport.prepare(jar("p/data.properties" to "two"),
                output(), HashSet(), resources)
        }
    }

    @Test fun cannotReplaceAndroidManifestWithJarResource() {
        assertThrows(IllegalArgumentException::class.java) {
            RiftJvmJarSupport.prepare(jar("AndroidManifest.xml" to "bad"),
                output(), HashSet(), linkedMapOf())
        }
    }

    @Test fun dropsJarSigningAndBuildMetadata() {
        val resources = linkedMapOf<String, ByteArray>()
        RiftJvmJarSupport.prepare(jar("META-INF/OLD.RSA" to "sig",
            "META-INF/proguard/rules.pro" to "rule", "META-INF/a.kotlin_module" to "metadata",
            "META-INF/com.android.tools/r8/rules.pro" to "rules"),
            output(), HashSet(), resources)
        assertTrue(resources.isEmpty())
    }

    @Test fun classpathResourcesAreNotPackaged() {
        val out = output()
        RiftJvmJarSupport.prepare(jar("p/A.class" to "x", "p/unused.txt" to "unused"),
            out, HashSet(), null)
        assertEquals(setOf("p/A.class"), entries(out))
    }

    @Test fun interruptedNormalizationStopsBeforePublication() {
        val input = jar("p/A.class" to "x")
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedException::class.java) {
                RiftJvmJarSupport.prepare(input, output(), HashSet(), linkedMapOf())
            }
        } finally { Thread.interrupted() }
    }

    @Test fun exactClasspathAcceptsOnlyDeclaredJars() {
        RiftJvmClasspathPolicy.requireExact(
            listOf("/sdk/android.jar", "/kotlin/stdlib.jar", "/deps/engine.jar"),
            listOf("/sdk/android.jar", "/kotlin/stdlib.jar", "/deps/engine.jar")
        )
    }

    @Test fun exactClasspathRejectsUndeclaredExtras() {
        assertThrows(IllegalArgumentException::class.java) {
            RiftJvmClasspathPolicy.requireExact(
                listOf("/sdk/android.jar", "/kotlin/stdlib.jar", "/deps/engine.jar", "/deps/hidden.jar"),
                listOf("/sdk/android.jar", "/kotlin/stdlib.jar", "/deps/engine.jar")
            )
        }
    }

    @Test fun exactClasspathRejectsMissingOrDuplicateJars() {
        assertThrows(IllegalArgumentException::class.java) {
            RiftJvmClasspathPolicy.requireExact(
                listOf("/sdk/android.jar", "/kotlin/stdlib.jar"),
                listOf("/sdk/android.jar", "/kotlin/stdlib.jar", "/deps/engine.jar")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RiftJvmClasspathPolicy.requireExact(
                listOf("/sdk/android.jar", "/kotlin/stdlib.jar", "/deps/engine.jar", "/deps/engine.jar"),
                listOf("/sdk/android.jar", "/kotlin/stdlib.jar", "/deps/engine.jar")
            )
        }
    }

    @Test fun dexPublicationCommitsValidatedFirstOutput() {
        val prepared = files.newFolder("first-candidate")
        File(prepared, "classes.dex").writeText("new")
        val output = File(files.root, "first-output")
        RiftJvmDexPublication.publish(prepared, output)
        assertEquals("new", File(output, "classes.dex").readText())
        assertFalse(prepared.exists())
    }

    @Test fun dexPublicationReplacesLastGoodOutputAfterValidation() {
        val output = files.newFolder("replace-output")
        File(output, "classes.dex").writeText("old")
        val prepared = files.newFolder("replace-candidate")
        File(prepared, "classes.dex").writeText("new")
        RiftJvmDexPublication.publish(prepared, output)
        assertEquals("new", File(output, "classes.dex").readText())
        assertFalse(prepared.exists())
        assertTrue(files.root.listFiles().orEmpty().none {
            it.name.startsWith(".replace-output-previous-")
        })
    }

    @Test fun dexPublicationRestoresPreviousOutputWhenPublishFails() {
        val output = files.newFolder("rollback-output")
        File(output, "classes.dex").writeText("old")
        val prepared = files.newFolder("rollback-candidate")
        File(prepared, "classes.dex").writeText("new")
        var moves = 0
        assertThrows(IllegalArgumentException::class.java) {
            RiftJvmDexPublication.publish(prepared, output) { from, to ->
                moves++
                if (moves == 2) false else from.renameTo(to)
            }
        }
        assertEquals(3, moves)
        assertEquals("old", File(output, "classes.dex").readText())
        assertEquals("new", File(prepared, "classes.dex").readText())
    }

    @Test fun dexPublicationPreservesOriginalWhenBackupFails() {
        val output = files.newFolder("blocked-output")
        File(output, "classes.dex").writeText("old")
        val prepared = files.newFolder("blocked-candidate")
        File(prepared, "classes.dex").writeText("new")
        assertThrows(IllegalArgumentException::class.java) {
            RiftJvmDexPublication.publish(prepared, output) { _, _ -> false }
        }
        assertEquals("old", File(output, "classes.dex").readText())
        assertEquals("new", File(prepared, "classes.dex").readText())
    }

    @Test fun dexPublicationKeepsBackupIfRollbackAlsoFails() {
        val output = files.newFolder("recover-output")
        File(output, "classes.dex").writeText("old")
        val prepared = files.newFolder("recover-candidate")
        File(prepared, "classes.dex").writeText("new")
        var moves = 0
        val error = assertThrows(IllegalArgumentException::class.java) {
            RiftJvmDexPublication.publish(prepared, output) { from, to ->
                moves++
                if (moves > 1) false else from.renameTo(to)
            }
        }
        assertEquals(3, moves)
        assertFalse(output.exists())
        assertTrue(error.suppressed.any { it.message.orEmpty().contains("Previous DEX preserved") })
        val backup = files.root.listFiles().orEmpty().single {
            it.name.startsWith(".recover-output-previous-")
        }
        assertEquals("old", File(backup, "classes.dex").readText())
    }
}
