package net.lecousin.framework.network.mime;

import net.lecousin.framework.core.test.LCCoreAbstractTest;

import org.junit.Test;

public class TestMimeType extends LCCoreAbstractTest {

	@Test
	public void test() {
		MimeType.defaultByExtension.get("html");
	}
	
}
