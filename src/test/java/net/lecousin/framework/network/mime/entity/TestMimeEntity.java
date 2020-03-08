package net.lecousin.framework.network.mime.entity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.io.buffering.ByteArrayIO;

import org.junit.Assert;
import org.junit.Test;

public class TestMimeEntity extends LCCoreAbstractTest {

	@Test
	public void testWrongHeaders() {
		AsyncSupplier<MimeEntity, IOException> parse = MimeEntity.parse(new ByteArrayIO(" not a good header ".getBytes(StandardCharsets.US_ASCII), "test"), DefaultMimeEntityFactory.getInstance());
		parse.block(0);
		Assert.assertTrue(parse.hasError());
	}
	
}
