package com.paa.assistant.core.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/** A .tar.gz whose first ".task" entry is a PAX metadata header must still yield the real model. */
class TarPaxTest {
    private fun header(name: String, size: Long, type: Char): ByteArray {
        val h = ByteArray(512)
        name.toByteArray().copyInto(h, 0)
        "%011o".format(size).toByteArray().copyInto(h, 124)
        h[156] = type.code.toByte()
        return h
    }

    private fun pad(n: Long) = ByteArray(((512 - n % 512) % 512).toInt())

    @Test fun skipsPaxHeaderWithSameName() {
        val body = ByteArray(101 * 1024 * 1024) { (it % 251).toByte() }.also { it[0] = 'P'.code.toByte(); it[1] = 'K'.code.toByte() }
        // A PAX record is "<length> key=value\n", where <length> counts the whole record
        val kv = "path=gemma-3n-E2B-it-int4.task\n"
        val pax = ("${kv.length + 3} " + kv).toByteArray()
        val raw = ByteArrayOutputStream().apply {
            write(header("PaxHeaders.0/gemma-3n-E2B-it-int4.task", pax.size.toLong(), 'x')); write(pax); write(pad(pax.size.toLong()))
            write(header("gemma-3n-E2B-it-int4.task", body.size.toLong(), '0')); write(body); write(pad(body.size.toLong()))
            write(ByteArray(1024))
        }.toByteArray()
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(raw) } }.toByteArray()
        val out = File.createTempFile("model", ".task")
        try {
            assertTrue(LocalLlm.extractTaskFromTarGz(gz.inputStream(), out))
            assertArrayEquals(body, out.readBytes())
        } finally { out.delete() }
    }
}
