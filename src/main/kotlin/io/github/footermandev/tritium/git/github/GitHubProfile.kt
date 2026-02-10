package io.github.footermandev.tritium.git.github

data class GitHubProfile(
    val id: String,
    val login: String,
    val name: String?,
    val avatarUrl: String?
)
