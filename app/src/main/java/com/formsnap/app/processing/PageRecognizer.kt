package com.formsnap.app.processing

import com.formsnap.app.domain.model.CandidatePage
import com.formsnap.app.domain.model.SourceDocument
import com.formsnap.app.validation.IssueCode

fun interface PageRecognizer {
    suspend fun recognize(source: SourceDocument): CandidatePage
}

class PageRecognitionException(val code: IssueCode, val explanation: String) : Exception(explanation)
