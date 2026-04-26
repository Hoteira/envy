package com.envy.dotenv

import org.junit.Assert.assertEquals
import org.junit.Test
import com.envy.dotenv.terminal.AhoCorasick

class AhoChunkTest {
    @Test
    fun testChunkedScan() {
        val secrets = listOf("sk_live_51ABC123DEF456GHI789JKL")
        val ac = AhoCorasick.build(secrets)!!
        
        val chunk1 = "STRIPE_SECRET_KEY=sk_li"
        val chunk2 = "ve_51ABC123DEF456GHI789JKL\n"
        
        val result1 = ac.scan(chunk1)
        assertEquals(0, result1.matches.size)
        
        val result2 = ac.scan(chunk2, result1.endState, chunk1.length)
        assertEquals(1, result2.matches.size)
        assertEquals(chunk1.length + chunk2.length - 1, result2.matches[0].endOffset)
    }
}
