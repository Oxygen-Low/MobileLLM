package com.example

import com.example.model.OutputContinuation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the cutoff detection and the stitching of a continued answer. */
class OutputContinuationTest {

    private val unfinished = "The result depends on several factors. " +
        "First, the device has to keep enough memory for the model, and then the sampler"

    @Test
    fun `an answer ending mid sentence is detected`() {
        assertTrue(OutputContinuation.looksCutOff(unfinished))
    }

    @Test
    fun `a finished answer is left alone`() {
        assertFalse(OutputContinuation.looksCutOff("$unfinished finishes here."))
        assertFalse(OutputContinuation.looksCutOff("Done."))
        assertFalse(OutputContinuation.looksCutOff("A short answer"))
    }

    @Test
    fun `the continuation is appended without repeating the overlap`() {
        val continued = "the sampler picks the next token from the distribution."
        assertEquals(
            "The result depends on several factors. First, the device has to keep " +
                "enough memory for the model, and then the sampler picks the next " +
                "token from the distribution.",
            OutputContinuation.merge(unfinished, continued)
        )
    }

    @Test
    fun `a continuation with no overlap is simply appended`() {
        assertEquals("one two three", OutputContinuation.merge("one two", " three"))
    }

    @Test
    fun `only the new text is reported as appended`() {
        val merged = OutputContinuation.merge(unfinished, "the sampler continues here.")
        assertTrue(merged.startsWith(unfinished))
        assertEquals(
            merged.removePrefix(unfinished),
            OutputContinuation.appended(unfinished, merged)
        )
    }
}
