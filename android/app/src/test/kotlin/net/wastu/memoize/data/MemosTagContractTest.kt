package net.wastu.memoize.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class MemosTagContractTest {
    @Test
    fun `tags match Memos and Mac including Markdown contexts and exact Unicode identities`() {
        val fixtures = javaClass.classLoader!!.getResourceAsStream("memos-tags.json")!!
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }
        for (entry in fixtures) {
            val fixture = entry.jsonObject
            val expected = fixture.getValue("expected").jsonArray.map { it.jsonPrimitive.content }
                .distinct().sorted()
            assertEquals(
                fixture.getValue("name").jsonPrimitive.content,
                expected,
                MemosTags.extract(fixture.getValue("content").jsonPrimitive.content),
            )
        }
    }
}
