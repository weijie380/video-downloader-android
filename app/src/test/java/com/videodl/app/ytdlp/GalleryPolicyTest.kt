package com.videodl.app.ytdlp

import com.videodl.app.download.ImageFileType
import org.junit.Assert.*
import org.junit.Test

class GalleryPolicyTest {
    @Test fun noteShareKeepsItsOwnOfficialPage() {
        assertEquals("https://www.iesdouyin.com/share/note/123/",
            DouyinGuestParser.shareUrl("https://www.douyin.com/note/123"))
        assertEquals("https://www.iesdouyin.com/share/note/123/",
            DouyinGuestParser.shareUrl("https://www.iesdouyin.com/share/note/123/"))
        assertEquals("https://www.iesdouyin.com/share/video/123/",
            DouyinGuestParser.shareUrl("https://www.douyin.com/video/123"))
    }

    @Test fun slidesShareUsesNotePageAndKeepsTheWorkId() {
        // 真实短链的 HTTP Location，mid 是配乐编号，不能当作品编号。
        val target = "https://www.iesdouyin.com/share/slides/7691196598550698993/" +
            "?mid=7672618211948021810&schema_type=37&is_slides=1"
        assertEquals("7691196598550698993", DouyinGuestParser.videoId(target))
        assertEquals("https://www.iesdouyin.com/share/note/7691196598550698993/",
            DouyinGuestParser.shareUrl(target))
    }

    @Test fun slidesIdWorksForDirectLinksAndDoesNotAcceptArbitraryIds() {
        assertEquals("https://www.iesdouyin.com/share/note/7691196598550698993/",
            DouyinGuestParser.shareUrl("https://www.douyin.com/slides/7691196598550698993"))
        assertNull(DouyinGuestParser.videoId("https://www.iesdouyin.com/share/slides/not-a-work-id/"))
        assertNull(DouyinGuestParser.videoId("https://www.douyin.com/user/7691196598550698993"))
        assertNull(DouyinGuestParser.videoId("https://www.douyin.com/?mid=7672618211948021810"))
    }

    @Test fun extensionFollowsActualBytesInsteadOfRemoteUrlSuffix() {
        assertEquals("jpg", ImageFileType.extension(byteArrayOf(-1, -40, -1, 0)))
        assertEquals("png", ImageFileType.extension(byteArrayOf(-119,80,78,71,13,10,26,10)))
        assertEquals("webp", ImageFileType.extension("RIFF1234WEBP".toByteArray()))
        assertEquals("gif", ImageFileType.extension("GIF89a".toByteArray()))
        assertEquals("avif", ImageFileType.extension("0000ftypavif".toByteArray()))
    }

    @Test fun emptyTruncatedOrHtmlResponsesAreNotImages() {
        assertNull(ImageFileType.extension(byteArrayOf()))
        assertNull(ImageFileType.extension(byteArrayOf(-1, -40)))
        assertNull(ImageFileType.extension("<!DOCTYPE html><html>Blocked</html>".toByteArray()))
        assertNull(ImageFileType.extension("RIFF1234WAVE".toByteArray()))
    }
}
