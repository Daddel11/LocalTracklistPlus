package com.libreplayer.media.service

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import org.junit.Test

class RadioLoadErrorPolicyTest {
    @Test
    fun `network loads stop after two retries`() {
        val policy = BoundedRadioLoadErrorPolicy()
        assertThat(policy.getRetryDelayMsFor(errorInfo("https", 1))).isEqualTo(0L)
        assertThat(policy.getRetryDelayMsFor(errorInfo("https", 2))).isEqualTo(1000L)
        assertThat(policy.getRetryDelayMsFor(errorInfo("https", 3))).isEqualTo(C.TIME_UNSET)
        assertThat(policy.getRetryDelayMsFor(errorInfo("https", 99))).isEqualTo(C.TIME_UNSET)
    }

    @Test
    fun `local content keeps the default retry policy`() {
        assertThat(BoundedRadioLoadErrorPolicy().getRetryDelayMsFor(errorInfo("content", 3)))
            .isEqualTo(2000L)
    }

    private fun errorInfo(scheme: String, errorCount: Int): LoadErrorHandlingPolicy.LoadErrorInfo {
        val uri = mockk<Uri>()
        every { uri.scheme } returns scheme
        return LoadErrorHandlingPolicy.LoadErrorInfo(
            LoadEventInfo(1L, DataSpec(uri), 0L),
            MediaLoadData(C.DATA_TYPE_MEDIA),
            IOException("Load failed"),
            errorCount,
        )
    }
}
