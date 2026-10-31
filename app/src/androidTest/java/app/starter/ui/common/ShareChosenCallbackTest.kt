package app.starter.ui.common

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareChosenCallbackTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun each_share_gets_its_own_pick_callback_so_a_newer_share_cannot_rewrite_an_older_ones_extras() {
        val a = context.shareChosenCallback("a", listOf("com.example.a"), listOf(Uri.parse("content://x/backups/a")))
        val b = context.shareChosenCallback("b", listOf("com.example.b"), listOf(Uri.parse("content://x/backups/b")))
        assertNotEquals(a, b)
    }
}
