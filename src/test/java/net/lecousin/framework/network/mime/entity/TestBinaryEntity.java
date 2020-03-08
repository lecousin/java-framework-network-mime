package net.lecousin.framework.network.mime.entity;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.io.buffering.ByteArrayIO;

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
	
}
