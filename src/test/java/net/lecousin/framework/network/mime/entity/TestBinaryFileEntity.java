package net.lecousin.framework.network.mime.entity;

import java.io.File;
import java.nio.ByteBuffer;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.io.FileIO;
import net.lecousin.framework.io.TemporaryFiles;

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
	
}
