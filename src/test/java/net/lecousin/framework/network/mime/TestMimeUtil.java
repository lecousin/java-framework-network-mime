package net.lecousin.framework.network.mime;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.core.test.LCCoreAbstractTest;

import org.junit.Assert;
import org.junit.Test;

public class TestMimeUtil extends LCCoreAbstractTest {

	@Test
	public void testEncodeToken() {
		Assert.assertEquals("test", MimeUtil.encodeToken("test"));
		Assert.assertEquals("\"test:2\"", MimeUtil.encodeToken("test:2"));
		Assert.assertEquals("\"test 3\"", MimeUtil.encodeToken("test 3"));
		Assert.assertEquals("\"test\\\\4\"", MimeUtil.encodeToken("test\\4"));
		Assert.assertEquals("test_5", MimeUtil.encodeToken("test_5"));
		Assert.assertEquals("test|6", MimeUtil.encodeToken("test|6"));
		Assert.assertEquals("test~7", MimeUtil.encodeToken("test~7"));
		Assert.assertEquals("\"test}8\"", MimeUtil.encodeToken("test}8"));
		Assert.assertEquals("Test9", MimeUtil.encodeToken("Test9"));
		Assert.assertEquals("\"test]10\"", MimeUtil.encodeToken("test]10"));
		Assert.assertEquals("Test#11", MimeUtil.encodeToken("Test#11"));
		Assert.assertEquals("\"test\\\"12\"", MimeUtil.encodeToken("test\"12"));
		Assert.assertEquals("Test*13", MimeUtil.encodeToken("Test*13"));
		Assert.assertEquals("Test+14", MimeUtil.encodeToken("Test+14"));
		Assert.assertEquals("Test-15", MimeUtil.encodeToken("Test-15"));
		Assert.assertEquals("Test.16", MimeUtil.encodeToken("Test.16"));
		Assert.assertEquals("\"test,17\"", MimeUtil.encodeToken("test,17"));
	}
	
	@Test
	public void testDecodeRFC2047() throws Exception {
		Assert.assertEquals("", MimeUtil.decodeRFC2047(""));
		Assert.assertEquals("test", MimeUtil.decodeRFC2047("test"));
		Assert.assertEquals("test hello world", MimeUtil.decodeRFC2047("test =?utf-8?Q?hello?= world"));
		Assert.assertEquals("test hello world", MimeUtil.decodeRFC2047("t\"est \"=?utf-8?Q?hello?= world"));
		Assert.assertEquals("test =?utf-8?Q?hello?= world", MimeUtil.decodeRFC2047("test \"=?utf-8?Q?hello?= wor\"ld"));
		Assert.assertEquals("test =?utf-8?Q?hello?= wor\"ld", MimeUtil.decodeRFC2047("test \"=?utf-8?Q?hello?= wor\"\"ld"));
		Assert.assertEquals("test", MimeUtil.decodeRFC2047("te\"st\""));
		Assert.assertEquals("test=?", MimeUtil.decodeRFC2047("test=?"));
		Assert.assertEquals("test hello world", MimeUtil.decodeRFC2047("test =?utf-8?Q?hello?= \"world\""));
		Assert.assertEquals("test", MimeUtil.decodeRFC2047("test=??="));
		Assert.assertEquals("test", MimeUtil.decodeRFC2047("test=???="));
		Assert.assertEquals("test", MimeUtil.decodeRFC2047("test=?utf-8?B??="));
		try {
			MimeUtil.decodeRFC2047("test=?utf-8?x??=");
			throw new AssertionError();
		} catch (UnsupportedEncodingException e) {
			// ok
		}
	}
	
	@Test
	public void testEncodeHeaderValue() {
		Assert.assertEquals("test", MimeUtil.encodeHeaderValue("test", StandardCharsets.UTF_8));
		Assert.assertEquals("\"hello world\"", MimeUtil.encodeHeaderValue("hello world", StandardCharsets.UTF_8));
		Assert.assertEquals("\"hello\tworld\"", MimeUtil.encodeHeaderValue("hello\tworld", StandardCharsets.UTF_8));
		Assert.assertEquals("\"hello\\\"world\"", MimeUtil.encodeHeaderValue("hello\"world", StandardCharsets.UTF_8));
		Assert.assertEquals("\"hello=world\"", MimeUtil.encodeHeaderValueWithUTF8("hello=world"));
		Assert.assertEquals("=?UTF-8?B?5q+U6LyD44Gu44Go44GN77yM5aSn5paH5a2X44Go5bCP5paH5a2X44Gu5ZCM5LiA6KaW?=", MimeUtil.encodeHeaderValue("比較のとき，大文字と小文字の同一視", StandardCharsets.UTF_8));
	}
	
}
