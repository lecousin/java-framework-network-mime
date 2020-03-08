package net.lecousin.framework.network.mime.entity;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.io.FileIO;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.TemporaryFiles;
import net.lecousin.framework.network.mime.header.MimeHeaders;

import org.junit.Assert;
import org.junit.Test;

public class TestBinaryFileEntity extends LCCoreAbstractTest {

	@Test
	public void testRange() throws Exception {
		byte[] data = new byte[84251];
		for (int i = 0; i < data.length; ++i)
			data[i] = (byte)(i + 87);
		FileIO.ReadWrite io = TemporaryFiles.get().createAndOpenFileSync("test", "mime_range");
		io.writeSync(ByteBuffer.wrap(data));
		File f = io.getFile();
		io.close();
		EntityTestUtil.testBodyRangeProducer(() -> {
			return new BinaryFileEntity(f);
		});
	}
	
	@Test
	public void testConsumer() throws Exception {
		try (BinaryFileEntity entity = new BinaryFileEntity(null, new MimeHeaders())) {
			File file = TemporaryFiles.get().createFileSync("test", "binaryfileentity");
			entity.setFile(file);
			AsyncConsumer<ByteBuffer, IOException> consumer = entity.createConsumer(null);
			consumer.consume(ByteBuffer.wrap("hello".getBytes(StandardCharsets.US_ASCII))).blockThrow(0);
			consumer.end().blockThrow(0);
			Assert.assertEquals("hello", IOUtil.readFullyAsStringSync(entity.getFile(), StandardCharsets.US_ASCII));
		}
	}
}
