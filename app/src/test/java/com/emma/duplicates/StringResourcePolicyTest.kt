package com.emma.duplicates

import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class StringResourcePolicyTest {
    @Test
    fun stringResourcesContainNoProhibitedPunctuation() {
        val forbidden = setOf('\u2014', '\u2013', '\u00B7', '\u2022')
        val resourceRoot = Path.of("src", "main", "res")
        val violations = mutableListOf<String>()

        Files.walk(resourceRoot).use { paths ->
            paths
                .filter { path ->
                    Files.isRegularFile(path) &&
                        path.parent.fileName
                            .toString()
                            .startsWith("values") &&
                        path.fileName.toString().endsWith(".xml")
                }.forEach { path ->
                    Files.readAllLines(path, StandardCharsets.UTF_8).forEachIndexed { index, line ->
                        val found = line.filter(forbidden::contains).toSet()
                        val unknownPlaceholder = line.trim() == """<string name="cleanup_unknown">—</string>"""
                        if (found.isNotEmpty() && !unknownPlaceholder) {
                            violations += "$path:${index + 1} contains ${found.joinToString()}"
                        }
                    }
                }
        }

        if (violations.isNotEmpty()) {
            fail(violations.joinToString(separator = "\n"))
        }
    }
}
