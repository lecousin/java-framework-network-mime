package net.lecousin.framework.network.mime.entity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;

import org.junit.Assert;
import org.junit.Test;

public class TestBinaryEntity extends LCCoreAbstractTest {

	@Test
	public void testRange() throws Exception {
		byte[] data = new byte[84251];
		for (int i = 0; i < data.length; ++i)
			data[i] = (byte)(i + 87);
		EntityTestUtil.testBodyRangeProducer(() -> {
			return new BinaryEntity(new ByteArrayIO(data, "test"));
		});
	}
	
	@Test
	public void testFromString() throws Exception {
		BinaryEntity entity = BinaryEntity.fromString("the content", StandardCharsets.US_ASCII, "my/content");
		Assert.assertEquals("my/content", entity.getHeaders().getContentTypeValue());
		Assert.assertEquals("the content", IOUtil.readFullyAsString(entity.getContent(), StandardCharsets.US_ASCII, Priority.NORMAL).blockResult(0).asString());
	}
	
	@Test
	public void testConsumer() throws Exception {
		try (BinaryEntity entity = new BinaryEntity(null, new MimeHeaders())) {
			entity.setContentType("custom/type");
			entity.setContentType(new ParameterizedHeaderValue("type/custom", "param", "value"));
			BinaryEntity.Consumer consumer = entity.new Consumer(new ByteArrayIO(new byte[256], "test"));
			consumer.consume(ByteBuffer.wrap("hello".getBytes(StandardCharsets.US_ASCII))).blockThrow(0);
			consumer.end().blockThrow(0);
			Assert.assertEquals("type/custom", entity.getHeaders().getContentTypeValue());
			Assert.assertEquals("value", entity.getHeaders().getContentType().getParameter("param"));
			Assert.assertEquals("hello", IOUtil.readFullyAsString(entity.getContent(), StandardCharsets.US_ASCII, Priority.NORMAL).blockResult(0).asString());
		}
	}
	
}
