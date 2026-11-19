package io.github.uposum.romstore.core

import io.github.uposum.romstore.core.model.Systems
import io.github.uposum.romstore.core.sources.SourceDefs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every source parser against pages recorded from the live site (same
 * recordings and expectations as the plugin's tests/test_sources.py). */
class SourceParsersTest {
    private fun check(sourceId: String) = runBlocking {
        val fixture = FixtureHttp.fixture(sourceId)
        val expected = fixture["expected"]!!.jsonObject
        val http = FixtureHttp(fixture["calls"]!!.jsonObject)
        val def = SourceDefs.get(sourceId)!!
        val source = SourceDefs.build(sourceId, http, def.homepage)!!
        val query = fixture["query"]
        val entries = if (query == null || query is JsonNull) {
            source.browse("switch", limit = 10).take(10)
        } else {
            source.search(query.jsonPrimitive.content, limit = 10, page = 1)
        }
        val resolvedTitle = expected["resolved_title"]!!.jsonPrimitive.content
        val target = entries.firstOrNull { it.title == resolvedTitle }
            ?: error("$sourceId: no entry titled '$resolvedTitle' in ${entries.map { it.title }}")
        val files = source.resolve(target)

        assertEquals(expected["titles"]!!.jsonArray.map { it.jsonPrimitive.content }, entries.map { it.title })
        assertEquals(expected["systems"]!!.jsonArray.map { it.jsonPrimitive.content }, entries.map { it.system })
        assertEquals(expected["files"]!!.jsonArray.map { it.jsonPrimitive.content }, files.map { it.filename })

        for (entry in entries) {
            assertTrue(entry.title, entry.title.isNotBlank())
            assertTrue(entry.url, entry.url.startsWith("http"))
            entry.cover?.let { assertTrue(it, it.startsWith("http")) }
        }
        val known = entries.count { it.system in Systems.ALL }
        assertTrue("most results should map to a known console", known >= entries.size * 0.8)
        for (file in files) {
            assertTrue(file.filename.isNotEmpty() || !file.label.isNullOrEmpty())
            assertTrue(file.url, file.url.startsWith("http"))
        }
    }

    @Test fun romspedia() = check("romspedia")
    @Test fun retrostic() = check("retrostic")
    @Test fun romsdl() = check("romsdl")
    @Test fun myabandonware() = check("myabandonware")
    @Test fun switchroms() = check("switchroms")
    @Test fun homebrewhub() = check("homebrewhub")
    @Test fun romsfun() = check("romsfun")
}
