package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JobParamResolverTest {

    @Test
    fun valuesAreShellQuotedByDefault() {
        val resolved = JobParamResolver.resolve(
            command = "echo {{target}}",
            declared = listOf(JobParamDef("target", required = true)),
            args = mapOf("target" to "a b; rm -rf /"),
        )
        assertEquals("echo 'a b; rm -rf /'", resolved.command)
        assertTrue(resolved.missing.isEmpty())
    }

    @Test
    fun rawPlaceholderIsNotQuoted() {
        val resolved = JobParamResolver.resolve(
            command = "run {{flags|raw}}",
            declared = listOf(JobParamDef("flags")),
            args = mapOf("flags" to "--jobs 4"),
        )
        assertEquals("run --jobs 4", resolved.command)
    }

    @Test
    fun defaultsAreAppliedAndMissingReported() {
        val resolved = JobParamResolver.resolve(
            command = "build {{target}} {{mode}} {{token}}",
            declared = listOf(
                JobParamDef("target", required = true),
                JobParamDef("mode", default = "release"),
                JobParamDef("token", required = true),
            ),
            args = mapOf("target" to "apk"),
        )
        assertEquals("build 'apk' 'release' {{token}}", resolved.command)
        assertEquals(listOf("token"), resolved.missing)
        assertEquals(listOf("token"), resolved.unresolvedPlaceholders)
    }

    @Test
    fun paramsAreExportedAsEnvVars() {
        val resolved = JobParamResolver.resolve(
            command = "echo hi",
            declared = listOf(JobParamDef("job-name")),
            args = mapOf("job-name" to "x"),
        )
        assertEquals(mapOf("RIKKA_PARAM_JOB_NAME" to "x"), resolved.env)
    }

    @Test
    fun singleQuotesAreEscaped() {
        val resolved = JobParamResolver.resolve(
            command = "echo {{v}}",
            declared = listOf(JobParamDef("v")),
            args = mapOf("v" to "it's"),
        )
        assertEquals("echo 'it'\\''s'", resolved.command)
    }
}
