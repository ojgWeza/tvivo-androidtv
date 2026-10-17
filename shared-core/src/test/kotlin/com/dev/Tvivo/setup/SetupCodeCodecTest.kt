package com.dev.Tvivo.setup

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class SetupCodeCodecTest {
    @Test fun vectors() {
        val resource = javaClass.getResourceAsStream("/setup-code/codec.json")
            ?: error("Missing setup-code vectors")
        val vectors = resource.bufferedReader(StandardCharsets.UTF_8).use { JsonParser.parseReader(it).asJsonArray }
        for (element in vectors) {
            val vector = element.asJsonObject
            val name = vector.get("name").asString
            val raw = vector.get("input").asString.replace(
                "{repeat}", "x".repeat(vector.get("repeat")?.asInt ?: 0),
            )
            val code = when (vector.get("form").asString) {
                "uri" -> raw
                "json" -> SetupCodeFormat.BASE64_PREFIX + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
                else -> error(name)
            }
            val pasted = vector.get("wrap")?.asString?.replace("{code}", code) ?: code
            val actual = SetupCodeCodec.decode(pasted)
            if (vector.get("valid").asBoolean) {
                assertTrue(name, actual is SetupCodeResult.Ok)
                assertExpected(name, vector.getAsJsonObject("expected"), (actual as SetupCodeResult.Ok).code)
            } else {
                assertTrue(name, actual is SetupCodeResult.Invalid)
                val invalid = actual as SetupCodeResult.Invalid
                assertEquals(name, vector.get("reason").asString, invalid.reason)
                vector.get("secret")?.asString?.let { assertFalse(name, invalid.toString().contains(it)) }
            }
        }
    }

    @Test fun bothEncodingsRoundTripAndRedact() {
        val original = SetupCode(
            name = "غرفة المعيشة & TV",
            server = SetupServer("example.invalid", 8443, "https"),
            user = "user+name",
            pass = "private%&=?\nvalue",
            entitlement = "opaque-token",
        )
        for (encoded in listOf(SetupCodeCodec.encode(original), SetupCodeCodec.encodeBase64Url(original))) {
            val decoded = SetupCodeCodec.decode(encoded)
            assertEquals(original, (decoded as SetupCodeResult.Ok).code)
            assertFalse(decoded.toString().contains(original.pass))
            assertFalse(original.toString().contains(original.pass))
            assertFalse(decoded.toString().contains(original.entitlement!!))
        }
    }

    @Test fun bareBase64TokenAndBoundaryLengths() {
        val source = SetupCode(
            name = "n".repeat(40),
            server = SetupServer("x".repeat(63) + "." + "y".repeat(63) + "." +
                "z".repeat(63) + "." + "w".repeat(61)),
            user = "u".repeat(128),
            pass = "p".repeat(128),
        )
        val token = SetupCodeCodec.encodeBase64Url(source).removePrefix(SetupCodeFormat.BASE64_PREFIX)
        assertEquals(source, (SetupCodeCodec.decode("\n$token\n") as SetupCodeResult.Ok).code)
        assertEquals(source, (SetupCodeCodec.decode(SetupCodeCodec.encode(source)) as SetupCodeResult.Ok).code)
    }

    @Test fun oversizedPasteIsRejectedWithoutEchoingIt() {
        val secret = "SECRET-IN-PASTE"
        val result = SetupCodeCodec.decode(secret + "x".repeat(65_536)) as SetupCodeResult.Invalid
        assertEquals("Input is too long", result.reason)
        assertFalse(result.toString().contains(secret))
    }

    private fun assertExpected(name: String, expected: JsonObject?, actual: SetupCode) {
        if (expected == null) return
        fun check(field: String, value: Any?) {
            if (expected.has(field)) {
                val item = expected.get(field)
                val wanted: Any? = when {
                    item.isJsonNull -> null
                    field == "port" -> item.asInt
                    else -> item.asString
                }
                assertEquals("$name $field", wanted, value)
            }
        }
        check("version", actual.version)
        check("name", actual.name)
        check("host", actual.server.host)
        check("port", actual.server.port)
        check("scheme", actual.server.scheme)
        check("user", actual.user)
        check("pass", actual.pass)
        check("entitlement", actual.entitlement)
    }
}
