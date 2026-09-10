package com.formsnap.app

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.formsnap.app.data.source.SourceImageLoader
import com.formsnap.app.processing.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real bundled model, artificial public fixture. No user documents or application database. */
@RunWith(AndroidJUnit4::class)
class RecognitionSmokeTest {
    @Test fun printedComplexFormFinishesWithReviewableStructure()= runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bitmap=instrumentation.context.assets.open("printed-36-column-header.png").use {
            BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply { inSampleSize=2 })!!
        }
        val proposal=AndroidPageRecognizer(SourceImageLoader(instrumentation.targetContext.contentResolver))
            .inspectBitmap("printed-fixture",bitmap)
        assertEquals(StructureOutcome.STRUCTURE_REVIEW_REQUIRED,proposal.grid.outcome)
        assertEquals(36,proposal.grid.columns)
        assertEquals(10,proposal.grid.rows)
        assertEquals(18,proposal.grid.merged.size)
        assertTrue("Actual model must return text evidence",proposal.text.isNotEmpty())
    }
}
