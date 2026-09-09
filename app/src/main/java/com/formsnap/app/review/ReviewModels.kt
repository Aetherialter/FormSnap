package com.formsnap.app.review

import com.formsnap.app.validation.*

enum class ReviewAction { CONFIRM, EDIT, KEEP, UNREADABLE, KEEP_RECORDS, DISTINCT_RECORDS, EXCLUDE_RECORD }

data class ReviewItem(val key: String, val target: IssueTarget, val targetId: String, val issues: List<ValidationIssue>) {
    val revision: String get() = revisionOf(issues)
}

internal fun revisionOf(issues: List<ValidationIssue>): String = fingerprint(*issues.sortedBy { it.id }
    .map { "${it.id}:${it.fingerprint}:${it.status}" }.toTypedArray())

object ReviewQueue {
    fun items(issues: List<ValidationIssue>): List<ReviewItem> = issues.filter { it.active && it.status != IssueStatus.RESOLVED }
        .groupBy { it.target to it.targetId }.map { (target, group) ->
            ReviewItem("${target.first}:${target.second}", target.first, target.second, group)
        }
}

data class ReviewDecision(
    val target: IssueTarget,
    val targetId: String,
    val action: ReviewAction,
    val editedValue: String? = null,
    val expectedRevision: String? = null,
    val expectedCellValue: String? = null,
)

class StaleReviewException : IllegalStateException("The displayed value or issues changed; reload before reviewing")
