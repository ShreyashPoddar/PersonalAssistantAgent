package com.paa.assistant.core.ai

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RealModelCheck {
    /** Both real Gemma models on this PC must pass the same check the app uses. */
    @Test fun realModelsPassValidity() {
        val dir = File(System.getProperty("user.dir")).parentFile.resolve("models")
        val models = dir.listFiles { f -> f.name.endsWith(".task") }.orEmpty()
        assumeTrue(models.isNotEmpty())
        val m = LocalLlm::class.java.getDeclaredMethod("isZip", File::class.java).apply { isAccessible = true }
        // isZip is an instance method; call it on an instance created without running the constructor
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val inst = unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, LocalLlm::class.java)
        for (f in models) assertTrue(f.name, m.invoke(inst, f) as Boolean)
    }
}
