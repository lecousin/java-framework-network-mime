package net.lecousin.framework.network.mime.entity;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.math.RangeLong;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.util.Pair;

import org.junit.Assert;
import org.junit.Test;

public class TestEmptyEntity extends LCCoreAbstractTest {

	@Test
	public void test() {
		EmptyEntity entity = new EmptyEntity();
		AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> body = entity.createBodyProducer();
		Assert.assertTrue(body.isDone());
		Assert.assertEquals(0, body.getResult().getValue1().intValue());
		entity = new EmptyEntity(null, new MimeHeaders());
		Assert.assertNull(entity.getParent());
		entity.createConsumer(null);
		entity.canProduceBodyRange();
		entity.createBodyRange(new RangeLong(0L, 1L));
	}
	
}
