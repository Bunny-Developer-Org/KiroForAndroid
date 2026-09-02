package dev.kiro.core.model

private val GITHUB_PREFIX = Regex("^https://github\\.com/", RegexOption.IGNORE_CASE)
private val GITLAB_PREFIX = Regex("^https://gitlab\\.com/", RegexOption.IGNORE_CASE)
private val SEGMENT = Regex("^[A-Za-z0-9._-]+$")

/**
 * Shape-only validation for a manually typed repository slug (ADR-004 §5).
 *
 * A repository is bound to a cloud session by an `owner/repo` name, and this
 * checks only that a string is *shaped* like one. Success does not imply the
 * repository exists, that Kiro can reach it, or that the Kiro Agent App is
 * installed for it — only the server can say that, and its rejection at
 * session creation is what the user sees (mirrors the comment on
 * [dev.kiro.core.session.CloudSessionGateway.createSession]).
 */
public object RepoSlug {

    public fun parse(input: String): Result<String> {
        var value = input.trim()

        if (value.isEmpty()) {
            return Result.failure(IllegalArgumentException("Enter a repository."))
        }

        if (value.startsWith("git@")) {
            return Result.failure(IllegalArgumentException("Paste owner/repo, not a URL."))
        }

        value = GITHUB_PREFIX.replaceFirst(value, "")
        value = GITLAB_PREFIX.replaceFirst(value, "")

        if (value.contains("://")) {
            return Result.failure(IllegalArgumentException("Paste owner/repo, not a URL."))
        }

        if (value.endsWith(".git")) {
            value = value.removeSuffix(".git")
        }

        if (value.startsWith("/") || value.endsWith("/")) {
            return Result.failure(IllegalArgumentException("Remove the leading or trailing slash."))
        }

        val parts = value.split("/")
        if (parts.size != 2) {
            return Result.failure(IllegalArgumentException("Enter it as owner/repo."))
        }

        val (owner, repo) = parts
        if (!SEGMENT.matches(owner) || !SEGMENT.matches(repo)) {
            return Result.failure(
                IllegalArgumentException(
                    "Owner and repository names can only contain letters, numbers, " +
                        "dots, underscores and hyphens.",
                ),
            )
        }

        return Result.success("$owner/$repo")
    }
}
