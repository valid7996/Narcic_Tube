package com.narcictub.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BubbleUrlIntakeTest {

    @Test
    fun `a single supported link is extracted`() {
        assertEquals("https://youtu.be/abc123", BubbleUrlIntake.singleUrlOrNull("https://youtu.be/abc123"))
        assertEquals(
            "https://www.instagram.com/p/x/",
            BubbleUrlIntake.singleUrlOrNull("Check this out: https://www.instagram.com/p/x/ so cool"),
        )
    }

    @Test
    fun `plain text null and ambiguous text yield no suggestion`() {
        assertNull(BubbleUrlIntake.singleUrlOrNull("just some notes"))
        assertNull(BubbleUrlIntake.singleUrlOrNull(null))
        assertNull(BubbleUrlIntake.singleUrlOrNull(""))
        assertNull(
            BubbleUrlIntake.singleUrlOrNull("https://a.example.com/1 and https://b.example.com/2"),
        )
    }
}
