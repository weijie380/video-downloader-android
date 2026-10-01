package com.videodl.app.ytdlp

import org.junit.Assert.*
import org.junit.Test

/** 全部使用虚构令牌，验证认证范围与失效处理，不访问 X。 */
class XCookieFileTest {
    private fun row(domain: String, name: String, value: String = "fake", expires: Long = 0) =
        "$domain\tTRUE\t/\tTRUE\t$expires\t$name\t$value\n"

    @Test fun httpOnlyAndLegacyDomainBecomeApiCompatibleCookies() {
        val output = XCookieFile.normalize(
            row("#HttpOnly_.twitter.com", "auth_token") + row(".twitter.com", "ct0"))
        assertTrue(output.contains(".x.com\tTRUE\t/\tTRUE\t0\tauth_token\tfake"))
        assertFalse(output.contains("twitter.com"))
    }

    @Test fun unrelatedAndLookalikeDomainsAreNotRetained() {
        val output = XCookieFile.normalize(row(".x.com", "auth_token") + row(".x.com", "ct0") +
            row(".douyin.com", "auth_token", "other") + row(".x.com.evil.test", "ct0", "other") +
            row(".x.com", "unrelated", "other"))
        assertFalse(output.contains("other"))
        assertFalse(output.contains("douyin"))
    }

    @Test fun expiredAuthenticationIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            XCookieFile.normalize(row(".x.com", "auth_token", expires = 123) + row(".x.com", "ct0"), 124)
        }
    }

    @Test fun headerKeepsEqualsInsideValuesAndRejectsLoggedOutState() {
        assertTrue(XCookieFile.fromHeader("auth_token=fake=value; ct0=fake-csrf; guest_id=unused")
            .contains("auth_token\tfake=value"))
        assertThrows(IllegalArgumentException::class.java) { XCookieFile.fromHeader("guest_id=fake") }
    }

    @Test fun csrfIsRequiredAndControlCharactersCannotInjectRows() {
        assertThrows(IllegalArgumentException::class.java) { XCookieFile.fromHeader("auth_token=fake") }
        assertThrows(IllegalArgumentException::class.java) {
            XCookieFile.fromHeader("auth_token=fake\ninvalid; ct0=fake")
        }
    }

    @Test fun futureExpirationIsPreservedRatherThanExtended() {
        val output = XCookieFile.normalize(row(".x.com", "auth_token", expires = 150) +
            row(".x.com", "ct0", expires = 150), 100)
        assertTrue(output.contains("\t150\tauth_token\t"))
    }
}
