package com.formsnap.app.data

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.formsnap.app.data.source.AndroidSourceAccess
import com.formsnap.app.data.source.SourceAccessException
import com.formsnap.app.domain.repository.SourceImportFailure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidSourceAccessTest {
    private lateinit var context: Context
    private lateinit var provider: TestImageProvider
    private lateinit var access: AndroidSourceAccess
    private val uri = "content://${TestImageProvider.AUTHORITY}/page"

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = TestImageProvider.register(context)
        access = AndroidSourceAccess(context.contentResolver)
    }

    @Test fun `read grant and metadata access do not modify or delete original`() = runBlocking {
        val original = provider.original.readBytes()
        access.retainRead(uri)
        assertTrue(uri in access.persistedReadUris())
        val grant = context.contentResolver.persistedUriPermissions.single { it.uri == Uri.parse(uri) }
        assertTrue(grant.isReadPermission)
        assertFalse(grant.isWritePermission)
        assertEquals("页面-page.png", access.inspectImage(uri).displayName)
        access.releaseRead(uri)
        assertFalse(uri in access.persistedReadUris())
        assertArrayEquals(original, provider.original.readBytes())
    }

    @Test fun `non-content references and non-image provider responses are rejected`() = runBlocking {
        val badReference = runCatching { access.retainRead("file:///not-supported.png") }.exceptionOrNull()
        assertEquals(SourceImportFailure.UNSUPPORTED_SOURCE, (badReference as SourceAccessException).reason)
        val badType = runCatching { access.inspectImage("content://${TestImageProvider.AUTHORITY}/pdf") }.exceptionOrNull()
        assertEquals(SourceImportFailure.UNSUPPORTED_SOURCE, (badType as SourceAccessException).reason)
    }

    @Test fun `missing original produces a readable-access failure`() = runBlocking {
        provider.unavailable = true
        val failure = runCatching { access.inspectImage(uri) }.exceptionOrNull()
        assertEquals(SourceImportFailure.UNREADABLE, (failure as SourceAccessException).reason)
    }

    @Test fun `stalled provider times out and receives cancellation`() = runBlocking {
        provider.stall = true
        val timedAccess = AndroidSourceAccess(context.contentResolver, requestTimeoutMillis = 200)
        val failure = runCatching { timedAccess.inspectImage(uri) }.exceptionOrNull()
        assertEquals(SourceImportFailure.TIMEOUT, (failure as SourceAccessException).reason)
        assertTrue(provider.cancellationReceived)
    }
}
