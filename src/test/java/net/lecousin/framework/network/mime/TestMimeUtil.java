package net.lecousin.framework.network.mime;

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
	
}
