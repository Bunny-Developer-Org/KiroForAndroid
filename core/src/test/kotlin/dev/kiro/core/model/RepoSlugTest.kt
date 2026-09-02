package dev.kiro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Table-driven per the shape rules [RepoSlug.parse] documents: trim, reject
 * empty, reject URLs (but normalize a leading github.com/gitlab.com https
 * prefix rather than only rejecting), strip a trailing `.git`, reject a
 * leading/trailing slash, require exactly one `/` with both halves matching
 * `[A-Za-z0-9._-]+`.
 */
class RepoSlugTest {

    @Test
    fun `valid inputs parse to the canonical slug`() {
        val cases = listOf(
            "owner/repo" to "owner/repo",
            "  owner/repo  " to "owner/repo",
            "owner/repo.git" to "owner/repo",
            "https://github.com/owner/repo" to "owner/repo",
            "https://github.com/owner/repo.git" to "owner/repo",
            "HTTPS://GitHub.com/owner/repo" to "owner/repo",
            "https://gitlab.com/owner/repo" to "owner/repo",
            "https://gitlab.com/owner/repo.git" to "owner/repo",
            "some-org/service-payments-api" to "some-org/service-payments-api",
            "owner.name/repo_name-2" to "owner.name/repo_name-2",
        )
        cases.forEach { (input, expected) ->
            val result = RepoSlug.parse(input)
            assertTrue(result.isSuccess, "expected \"$input\" to parse, got $result")
            assertEquals(expected, result.getOrNull(), "for input \"$input\"")
        }
    }

    @Test
    fun `empty or blank input is rejected`() {
        listOf("", "   ").forEach { input ->
            val result = RepoSlug.parse(input)
            assertTrue(result.isFailure, "expected \"$input\" to fail")
            assertEquals("Enter a repository.", result.exceptionOrNull()?.message)
        }
    }

    @Test
    fun `an ssh url is rejected`() {
        val result = RepoSlug.parse("git@github.com:owner/repo.git")
        assertTrue(result.isFailure)
        assertEquals("Paste owner/repo, not a URL.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a non-github non-gitlab url is rejected`() {
        val result = RepoSlug.parse("https://example.com/owner/repo")
        assertTrue(result.isFailure)
        assertEquals("Paste owner/repo, not a URL.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `an ftp-style scheme is rejected`() {
        val result = RepoSlug.parse("ftp://owner/repo")
        assertTrue(result.isFailure)
        assertEquals("Paste owner/repo, not a URL.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a leading slash is rejected`() {
        val result = RepoSlug.parse("/owner/repo")
        assertTrue(result.isFailure)
        assertEquals("Remove the leading or trailing slash.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a trailing slash is rejected`() {
        val result = RepoSlug.parse("owner/repo/")
        assertTrue(result.isFailure)
        assertEquals("Remove the leading or trailing slash.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `no slash at all is rejected`() {
        val result = RepoSlug.parse("ownerrepo")
        assertTrue(result.isFailure)
        assertEquals("Enter it as owner/repo.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `more than one slash is rejected`() {
        val result = RepoSlug.parse("owner/repo/extra")
        assertTrue(result.isFailure)
        assertEquals("Enter it as owner/repo.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `invalid characters in either half are rejected`() {
        listOf("own er/repo", "owner/re po", "owner/repo!", "own\$er/repo").forEach { input ->
            val result = RepoSlug.parse(input)
            assertTrue(result.isFailure, "expected \"$input\" to fail")
            assertEquals(
                "Owner and repository names can only contain letters, numbers, " +
                    "dots, underscores and hyphens.",
                result.exceptionOrNull()?.message,
            )
        }
    }
}
