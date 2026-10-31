package app.starter.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareBookTest {
    private val book = ShareBook<String> { it }

    @Test
    fun `a pick before the return keeps the share from being taken as cancelled`() {
        book.opened("a")
        assertFalse(book.picked("a"))
        assertNull(book.returned())
    }

    @Test
    fun `a return with no pick cancels the share, and a late pick on it is owed its grant once`() {
        book.opened("a")
        assertEquals("a", book.returned())
        assertNull(book.returned())
        assertTrue(book.picked("a"))
        assertFalse(book.picked("a"))
    }

    @Test
    fun `a late pick on an older share is owed nothing`() {
        book.opened("a")
        book.returned()
        book.opened("b")
        assertFalse(book.picked("a"))
        assertEquals("b", book.returned())
        assertFalse(book.picked(null))
        assertFalse(book.picked("a"))
        assertTrue(book.picked("b"))
    }
}
