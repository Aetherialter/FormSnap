package com.formsnap.app.ui

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts

/** Require a file that can be opened, rather than a virtual document needing format conversion. */
class SourceImagePicker : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
}
